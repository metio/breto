;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.commands
  "The commands. Each takes a context and its arguments and returns the exit
  code. The context holds :config, :registry, :opts, :config-path, the
  network :io, a :reporter making the progress callback for a command, and
  :sleep-second!, so a test can run any command without network or waits."
  (:refer-clojure :exclude [update])
  (:require [babashka.fs :as fs]
            [breto.batch :as batch]
            [breto.check :as check]
            [breto.doctor :as doctor]
            [breto.env :as env]
            [breto.hooks :as hooks]
            [breto.install :as install]
            [breto.lockfile :as lockfile]
            [breto.package :as package]
            [breto.registry :as registry]
            [breto.report :as report]
            [breto.sealed :as sealed]
            [breto.search :as search]
            [breto.signature :as signature]
            [breto.source :as source]
            [breto.store :as store]
            [breto.template :as template]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn- targets
  "The packages a command acts on: the named ones, groups expanded, else the
  configured ones."
  [{:keys [config groups]} args]
  (if (seq args) (registry/expand groups args) (:packages config)))

(defn- tracker
  "The progress callback for `command`: the live display and the recorder."
  [{:keys [reporter]} command]
  (batch/broadcast (reporter command) (batch/recorder command)))

(defn- hook-deps
  "What hooks need to run, or nil without a hook runner in the io."
  [{:keys [io]}]
  (when-let [run-hook (:run-hook io)]
    {:run-hook run-hook
     :warn #(binding [*out* *err*] (println (str "[WARN] " %)))
     :os (System/getProperty "os.name")
     :which #(some-> (fs/which %) str)}))

(defn- hook-listener
  "Runs the configured hooks on the events of `command`; without a hook runner
  in the io, none."
  [{:keys [config] :as ctx} command]
  (if-let [deps (hook-deps ctx)]
    (hooks/listener config command deps)
    (fn [_])))

(defn locked!
  "Runs the :locked hook after `command` rewrote the lock file at `path`."
  [{:keys [config] :as ctx} command path]
  (when-let [deps (hook-deps ctx)]
    (hooks/locked! config command path deps)))

(defn- pacer
  "Paces a batch so that :delay follows only packages that received download
  bytes; a package found up to date, or a download refused outright, costs one
  small request."
  [{:keys [config opts io sleep-second!]} progress transferred]
  (fn [f]
    (batch/paced (or (:delay opts) (:delay config) 0)
                 #(pos? (first (reset-vals! transferred 0)))
                 (:upstream-wait io (constantly 0))
                 (batch/counting-down progress sleep-second!)
                 f)))

(defn- installed-result [name {:keys [version installed? verified? signed? bootstrap? hash-recorded? warnings]}]
  (cond-> {:package name :version version :status (if installed? :installed :current)}
    (and installed? (not verified?)) (assoc :unverified true)
    signed? (assoc :signed true)
    bootstrap? (assoc :signature-bootstrap true)
    hash-recorded? (assoc :hash-recorded hash-recorded?)
    (seq warnings) (assoc :warnings warnings)))

(defn- tools-first
  "`specs` with the packages that provide cosign, go, uv, node or rustup moved to the
  front, so that on a fresh host those are installed before they are needed
  to check the signatures of the others or to build them."
  [registry specs]
  (let [provides? #(let [[name] (batch/split-spec %)]
                     (or (#{"go" "uv" "node" "rustup" "ghcup" "coursier"} name) (some-> (get registry name) signature/provides-cosign?)))]
    (concat (filter provides? specs) (remove provides? specs))))

(defn tool-command
  "The command `command` of the package `name`, by default the command of the
  same name, which builds the packages of a :go, :pypi, :npm, :cargo, :ghcup or :coursier source: that of
  its active version, else of its newest installed one."
  ([config registry name] (tool-command config registry name name))
  ([config registry name command]
   (when-let [pkg (get registry name)]
     (when-let [version (or (store/current-version config name) (:version (first (store/versions config name))))]
       (get (install/bin-targets config pkg version) command)))))

(defn- lock-options
  "install! options checking `name` at `version` against the lock files of
  the registry directories, when one of them lists it."
  [locks name version]
  (when-let [{:keys [sha256 sum exclude-newer]} (and version (get locks (str name "@" version)))]
    (cond-> {}
      sha256 (assoc :expect-sha256 sha256)
      sum (assoc :expect-sum sum)
      exclude-newer (assoc :exclude-newer exclude-newer))))

(defn- install-results
  "Runs `install-one` (name, version -> install! options) over `specs` with
  progress and pacing under `command`, returning a result per spec."
  [{:keys [config registry locks io] :as ctx} command specs install-one]
  (let [progress (batch/broadcast (tracker ctx command) (hook-listener ctx command))
        transferred (atom 0)
        io (batch/tracked-io io progress transferred)]
    (batch/run-batch io (vec (tools-first registry specs))
                     ((pacer ctx progress transferred)
                      (fn [spec]
                        (let [[name version] (batch/split-spec spec)
                              pkg (registry/lookup registry name)
                              options (install-one name version)
                              locked (lock-options locks name (or (:version options) version))
                              unlocked? (and (::pinned options) (seq locks) (nil? locked))
                              _ (when (and unlocked? (:require-locked config))
                                  (throw (ex-info (str name " " version " is in no lock file of the registry directories, and :require-locked is set")
                                                  {:breto/problem :not-locked :package name :version version})))
                              options (cond-> (into (or locked {}) (remove (comp nil? val)) (dissoc options ::pinned))
                                        (= :go (package/source-kind pkg)) (assoc :go (tool-command config registry "go"))
                                        (= :pypi (package/source-kind pkg)) (assoc :uv (tool-command config registry "uv"))
                                        (= :npm (package/source-kind pkg)) (assoc :npm (tool-command config registry "node" "npm"))
                                        (= :cargo (package/source-kind pkg)) (assoc :cargo (tool-command config registry "rustup" "cargo"))
                                        (= :ghcup (package/source-kind pkg)) (assoc :ghcup (tool-command config registry "ghcup"))
                                        (= :coursier (package/source-kind pkg)) (assoc :coursier (tool-command config registry "coursier" "cs")))
                              result (installed-result name (install/install! config io pkg options))
                              warnings (cond-> []
                                         (package/superseded-warning pkg) (conj (package/superseded-warning pkg))
                                         unlocked? (conj (str version " is in no lock file of the registry directories, so only upstream verified it")))]
                          (cond-> result
                            (seq warnings) (clojure.core/update :warnings (fnil into []) warnings)))))
                     progress)))

(defn- installing
  "install-results with a printed and saved report under `command`."
  [ctx command specs install-one]
  (batch/finish! command (install-results ctx command specs install-one)))

(defn- locked
  "install! options for `name` from the lock file, when --locked asks for it."
  [{:keys [config opts]} name]
  (when (:locked opts)
    (when-not (:lockfile config)
      (throw (ex-info "--locked needs :lockfile in the config"
                      {:breto/problem :no-lockfile :path nil})))
    (let [{:keys [version sha256 sum exclude-newer]} (lockfile/locked-entry (:lockfile config) name)]
      {:version version :expect-sha256 sha256 :expect-sum sum :exclude-newer exclude-newer})))

(defn- which [io]
  (or (:which io) #(some-> (fs/which %) str)))

(defn choose
  "The packages to install, chosen in a fuzzy finder over every definition,
  or without one through a numbered prompt. --query starts with a search."
  [{:keys [config registry io opts]}]
  (when-not (:interactive? io)
    (throw (ex-info "install needs a package; run it in a terminal to choose from the registries"
                    {:breto/problem :usage})))
  (if-let [finder (search/finder config (which io))]
    (let [{:keys [exit out]} ((:pick io) (search/finder-command config finder (:query opts))
                                         (str (str/join "\n" (map search/row (search/matches registry []))) "\n"))]
      (if (= 0 exit) (search/selected out) []))
    (or (search/prompt! registry (:ask io) (:query opts)) [])))

(defn install-args
  "The arguments of install: the named packages, else the chosen ones."
  [ctx args]
  (if (seq args) args (choose ctx)))

(defn- unconfigured-hint [{:keys [config groups]} args]
  (let [configured (set (:packages config))
        names (remove configured (map (comp first batch/split-spec) (registry/expand groups args)))]
    (when (seq names)
      (println)
      (println (str "Not in :packages, so breto update leaves "
                    (if (= 1 (count names)) "it" "them") " alone"
                    (when (:prune config) " and breto gc uninstalls them")
                    "; add " (str/join " " (map pr-str names)) " to keep them.")))))

(defn install [{:keys [opts groups chosen?] :as ctx} args]
  (let [code (installing ctx "install" (registry/expand groups args)
                         (fn [name version]
                           (merge {:version version :verify? (:verify opts true)} (locked ctx name))))]
    (when chosen? (unconfigured-hint ctx args))
    code))

(defn search
  "The definitions whose name, description or source contains every term."
  [{:keys [registry]} terms]
  (let [found (search/matches registry terms)]
    (if (seq found)
      (run! #(println (search/row %)) found)
      (println (str "No package matches " (str/join " " terms) ".")))
    0))

(defn- with-store-lock
  "Runs `f` under the store lock. Waits for another run holding it, or with
  --no-wait gives up at once."
  [{:keys [config opts]} f]
  (store/with-store-lock config
    (fn [pid]
      (if (false? (:wait opts))
        (throw (ex-info (str "another breto run (process " pid ") is changing the store")
                        {:breto/problem :store-busy :pid pid}))
        (binding [*out* *err*]
          (println (str "waiting for another breto run (process " pid ") to finish with the store")))))
    f))

(defn- project
  "The packages of the project file `file`: {:dir :file :specs :files}, the
  specs with groups expanded and :files the project file and the group files
  the specs come from."
  [groups file]
  (let [names (env/read-project file)]
    {:dir (str (fs/parent (fs/absolutize file)))
     :file (str (fs/absolutize file))
     :specs (registry/expand groups names)
     :files (vec (cons (str (fs/absolutize file)) (registry/group-files groups names)))}))

(defn- project-targets
  "[spec bin-targets] for each spec of `project`, bin-targets nil for a
  version that is not installed. A spec without version uses the active
  version, else the newest installed one."
  [config registry {:keys [specs]}]
  (for [spec specs
        :let [[name version] (batch/split-spec spec)
              pkg (registry/lookup registry name)
              version (or version (store/current-version config name)
                          (:version (first (store/versions config name))))]]
    [spec (when version (install/bin-targets config pkg version))]))

(defn- project-environment
  "{:homes :vars} of `project`: the version directory of each package whose
  definition has :env, and the variables those definitions set, rendered
  with {home} as the project's stable link to that directory. Two packages
  setting one variable is an error, since only one value can win."
  [config registry {:keys [dir specs]}]
  (let [entries (for [spec specs
                      :let [[name version] (batch/split-spec spec)
                            pkg (registry/lookup registry name)]
                      :when (seq (:env pkg))
                      :let [version (or version (store/current-version config name)
                                        (:version (first (store/versions config name))))]
                      :when version
                      :let [pkg (package/at pkg version)
                            vars (assoc (package/template-vars pkg {:tag (package/tag-of-version pkg version) :version version})
                                        :home (str (env/home config dir name)))]]
                  {:name name
                   :home (str (store/version-dir config name version))
                   :vars (update-vals (:env pkg) #(template/render % vars))})]
    (doseq [[k owners] (group-by first (for [{:keys [name vars]} entries, [k _] vars] [k name]))
            :when (< 1 (count owners))]
      (throw (ex-info (str "the project's packages " (str/join " and " (map second owners)) " all set " k)
                      {:breto/problem :env-conflict :variable k :packages (mapv second owners)})))
    {:homes (into {} (map (juxt :name :home)) entries)
     :vars (into {} (mapcat :vars) entries)}))

(defn- project-install
  "install! options for a version a project needs: installed beside the
  active one, not in its place, and expected in a registry lock file when it
  is pinned."
  [opts]
  (fn [_ version] (cond-> {:version version :activate? false :verify? (:verify opts true)}
                    version (assoc ::pinned true))))

(defn env
  "Links the versions the nearest .breto.edn lists into the project's bin
  directory, installing those that are missing without activating them, and
  prints the shell code that puts that directory in front of PATH. Everything
  else goes to stderr, so the output can be evaluated. Only installing needs
  the store lock, so linking installed versions never waits for another run."
  [{:keys [config registry groups opts] :as ctx} _]
  (let [file (or (env/find-project (or (:dir opts) "."))
                 (throw (ex-info (str "no " env/file-name " in " (fs/absolutize (or (:dir opts) ".")) " or above it")
                                 {:breto/problem :no-project :dir (str (fs/absolutize (or (:dir opts) ".")))})))
        {:keys [dir files] :as proj} (project groups file)
        missing (for [[spec targets] (project-targets config registry proj)
                      :when (nil? targets)]
                  spec)
        _ (when (and (seq missing) (false? (:install opts)))
            (throw (ex-info (str "the project needs " (str/join ", " missing) ", which " (if (= 1 (count missing)) "is" "are") " not installed")
                            {:breto/problem :env-missing :missing (vec missing) :project dir})))
        code (if (seq missing)
               (binding [*out* *err*]
                 (with-store-lock ctx #(installing ctx "env" (vec missing) (project-install opts))))
               0)]
    (if (pos? code)
      code
      (let [links (into {} (mapcat second (project-targets config registry proj)))
            {:keys [homes vars]} (project-environment config registry proj)
            bin (env/link! config dir links homes)
            shell (if (= "fish" (:shell opts)) :fish :sh)]
        (println (env/exports shell (env/path-value config bin (System/getenv "PATH")) dir bin files vars))
        0))))

(defn- refresh-projects
  "Installs the versions the projects with an environment need and links
  each project's bin directory again, so a version a shared group moved to
  reaches every project, its open shells included. A project whose file no
  longer loads keeps its links and is reported. Returns the install results."
  [{:keys [config registry groups opts] :as ctx} command]
  (let [projects (for [environment (remove env/stale? (env/environments config))
                       :let [dir (:project environment)]]
                   (try (project groups (str (fs/path dir env/file-name)))
                        (catch Exception e {:dir dir :problem (ex-message e)})))
        projects (doall projects)
        missing (distinct (for [p projects
                                :when (not (:problem p))
                                [spec targets] (try (project-targets config registry p) (catch Exception _ []))
                                :when (and (nil? targets) (second (batch/split-spec spec)))]
                            spec))
        results (when (seq missing) (install-results ctx command (vec missing) (project-install opts)))]
    (doseq [p projects]
      (if (:problem p)
        (println (str "[WARN] project " (:dir p) " keeps its links: " (:problem p)))
        (try
          (let [targets (project-targets config registry p)]
            (if (every? second targets)
              (env/link! config (:dir p) (into {} (mapcat second targets))
                         (:homes (project-environment config registry p)))
              (println (str "[WARN] project " (:dir p) " keeps its links: "
                            (str/join ", " (map first (remove second targets))) " not installed"))))
          (catch Exception e
            (println (str "[WARN] project " (:dir p) " keeps its links: " (ex-message e)))))))
    (vec results)))

(defn update
  "Updates the named packages, else the configured ones and then the projects
  with an environment."
  [{:keys [config opts] :as ctx} args]
  (let [results (install-results ctx "update" (targets ctx args)
                                 (fn [name version]
                                   (merge {:version (or version (get-in config [:pins name])) :verify? (:verify opts true)} (locked ctx name))))]
    (batch/finish! "update" (into results (when (empty? args) (refresh-projects ctx "update"))))))

(defn outdated
  "The packages whose latest release differs from the active version, or from
  the version a group or argument pins."
  [{:keys [config registry io] :as ctx} args]
  (let [progress (tracker ctx "outdated")]
    (batch/finish! "outdated"
                   (batch/run-batch io (targets ctx args)
                                    ((pacer ctx progress (atom 0))
                                     (fn [spec]
                                       (progress {:type :stage :stage :resolve})
                                       (let [[name pinned] (batch/split-spec spec)
                                             active (or pinned (get-in config [:pins name]) (store/current-version config name))
                                             latest (:version (install/with-stage :resolve {:package name}
                                                                #(source/newest (registry/lookup registry name) io)))]
                                         {:package name :version latest :from active
                                          :status (if (= latest active) :current :outdated)})))
                                    progress))))

(defn check
  "Checks the configured packages, those named, or with --all every package of
  the registries, against their latest release without downloading it."
  [{:keys [registry opts io] :as ctx} args]
  (let [progress (tracker ctx "check")
        names (cond (:all opts) (sort (keys registry))
                    :else (distinct (map (comp first batch/split-spec) (targets ctx args))))
        io (assoc io :progress progress)]
    (batch/finish! "check"
                   (batch/run-batch io names
                                    ((pacer ctx progress (atom 0))
                                     #(check/check-package io (registry/lookup registry %)))
                                    progress))))

(defn list-installed [{:keys [config]} _]
  (doseq [name (store/installed-packages config)]
    (let [active (store/current-version config name)
          versions (map (fn [{:keys [version]}] (str version (when (= version active) "*")))
                        (store/versions config name))]
      (println (str name "\t" (str/join " " versions)))))
  0)

(defn- previous! [config name]
  (let [active (store/current-version config name)
        installed (map :version (store/versions config name))]
    (or (when active (store/previous-version installed active))
        (throw (ex-info (str name " has no installed version older than " (or active "the active one"))
                        {:breto/problem :no-previous :package name :active active
                         :installed (vec (sort-by identity installed))})))))

(defn use-version
  "Activates VERSION of PKG, or with --previous the newest installed version
  older than the active one."
  [{:keys [config registry opts]} [name version]]
  (let [version (if (:previous opts) (when name (previous! config name)) version)]
    (when-not (and name version)
      (throw (ex-info "use needs a package and a version, or a package and --previous" {:breto/problem :usage})))
    (let [warnings (install/use! config (registry/lookup registry name) version)]
      (println (str "[OK] " name " " version " active"))
      (doseq [warning warnings]
        (println (str "[WARN] " name ": " warning))))
    (when (some #{name} (:packages config))
      (println (str "breto update activates the latest release again unless the config pins it: :pins {"
                    (pr-str name) " " (pr-str version) "}")))
    0))

(defn- claimed-links
  "Link path -> configured package, for the commands and extra links that
  configured packages own: those their definitions name, and the commands
  of the active version, which a :bin-dir package only lists once installed."
  [{:keys [config registry]}]
  (into {} (for [name (:packages config)
                 :let [pkg (get registry name)
                       version (store/current-version config name)
                       commands (concat (keys (:bins pkg))
                                        (when (and pkg version) (keys (install/bin-targets config pkg version))))]
                 link (concat (map #(str (fs/path (:bin-dir config) %)) commands)
                              (map #(str (fs/path (store/expand-home %))) (keys (:links pkg))))]
             [link name])))

(defn- uninstall-one
  "Removes package `name` with every link into it, or only reports what it
  would remove on a dry run. A link a configured package owns blocks removal:
  deleting the directory would leave that command dangling."
  [{:keys [config registry] :as ctx} name dry-run?]
  (when-not (fs/directory? (store/package-dir config name))
    (throw (ex-info (str name " is not installed")
                    {:breto/problem :not-in-store :package name :store (:store config)
                     :installed (store/installed-packages config)})))
  (let [links (store/links-into config name (some-> (get registry name) :links keys))
        claimed (claimed-links ctx)
        blocking (for [link links
                       :let [owner (claimed (str link))]
                       :when (and owner (not= owner name))]
                   [link owner])]
    (when (seq blocking)
      (throw (ex-info (str name " still provides commands of configured packages")
                      {:breto/problem :prune-blocked :package name :blocking (vec blocking)})))
    (when-not dry-run?
      (store/uninstall! config name links))
    {:package name :status (if dry-run? :would-remove :removed) :links (vec links)}))

(defn gc [{:keys [config opts io] :as ctx} args]
  (let [keep (or (:keep opts) (:keep config))
        dry-run? (:dry-run opts)
        used (env/used-versions config)
        prune (when (and (:prune config) (empty? args))
                (remove used (store/prune-candidates (store/installed-packages config) (:packages config))))
        _ (when (empty? args)
            (doseq [{:keys [dir project] :as e} (env/environments config)
                    :when (env/stale? e)]
              (when-not dry-run? (fs/delete-tree dir))
              (println (str (if dry-run? "would remove " "[OK] removed ") "the links of " (or project dir)
                            ", which has no " env/file-name " any more"))))
        pruned (batch/run-batch io prune #(uninstall-one ctx % dry-run?) (fn [_]))
        failures (for [name (if (seq args) (targets ctx args) (remove (set prune) (store/installed-packages config)))
                       :let [r (try
                                 (doseq [leftover (store/leftovers config name)]
                                   (when-not dry-run? (sealed/delete-tree! leftover))
                                   (println (str (if dry-run? "would remove " "[OK] removed ") leftover)))
                                 (doseq [version (remove (get used name #{})
                                                         (store/gc-candidates (store/versions config name)
                                                                              (store/current-version config name) keep))]
                                   (when-not dry-run? (store/remove-version! config name version))
                                   (println (str (if dry-run? "would remove " "[OK] removed ") name " " version)))
                                 nil
                                 (catch Exception e (batch/failure io name nil e)))]
                       :when r]
                   r)
        results (into pruned failures)]
    (if (seq results) (batch/finish! "gc" results) 0)))

(defn sync-store
  "Makes the store match the lock file: installs and activates each locked
  version, checked against its locked hash, and uninstalls every package the
  lock file does not list and no project uses. --dry-run lists the changes."
  [{:keys [config opts io] :as ctx} _]
  (let [path (or (:lockfile config)
                 (throw (ex-info "sync needs :lockfile in the config" {:breto/problem :no-lockfile :path nil})))
        _ (when-not (fs/exists? path)
            (throw (ex-info (str "there is no lock file at " path) {:breto/problem :no-lockfile :path (str path)})))
        entries (into (sorted-map) (:entries (lockfile/read-lock path)))
        extra (remove (into (set (keys entries)) (keys (env/used-versions config)))
                      (store/installed-packages config))]
    (if (:dry-run opts)
      (do (doseq [[name {:keys [version]}] entries
                  :when (not= version (store/current-version config name))]
            (println (str name " would change from " (or (store/current-version config name) "nothing") " to " version)))
          (doseq [name extra]
            (println (str name " would be removed")))
          0)
      (let [installed (install-results ctx "sync" (mapv (fn [[name {:keys [version]}]] (str name "@" version)) entries)
                                       (fn [name version]
                                         {:version version :verify? (:verify opts true)
                                          :expect-sha256 (get-in entries [name :sha256])
                                          :expect-sum (get-in entries [name :sum])
                                          :exclude-newer (get-in entries [name :exclude-newer])}))
            removed (batch/run-batch io extra #(uninstall-one ctx % false) (fn [_]))]
        (batch/finish! "sync" (-> installed (into removed) (into (refresh-projects ctx "sync"))))))))

(defn uninstall [{:keys [config opts io] :as ctx} args]
  (when (empty? args)
    (throw (ex-info "uninstall needs at least one package" {:breto/problem :usage})))
  (batch/finish! "uninstall"
                 (batch/run-batch io (distinct (map (comp first batch/split-spec) (targets ctx args)))
                                  (fn [name]
                                    (when (and (some #{name} (:packages config)) (not (:force opts)))
                                      (throw (ex-info (str name " is still configured")
                                                      {:breto/problem :still-configured :package name})))
                                    (uninstall-one ctx name (:dry-run opts)))
                                  (fn [_]))))

(defn info [{:keys [registry groups]} [name]]
  (pprint/pprint (if (registry/group-name? name)
                   (let [packages (registry/expand groups [name])]
                     (assoc (get groups name) :packages packages))
                   (registry/lookup registry name)))
  0)

(defn show-report
  "The saved reports of `commands`, or of every command. With --brief, only
  the latest of them in a few short lines, as a notification shows it."
  [{:keys [opts]} commands]
  (let [runs (cond->> (report/load-runs) (seq commands) (filter #((set commands) (:command %))))]
    (cond
      (empty? runs)
      (do (println (if (seq commands)
                     (str "No run of " (str/join ", " commands) " recorded yet in " (report/state-path))
                     (str "No run recorded yet in " (report/state-path))))
          0)

      (:brief opts) (do (println (report/brief (last runs) {})) 0)

      :else (do (doseq [run runs]
                  (println (report/heading run))
                  (println (report/render run))
                  (println))
                0))))

(defn doctor [{:keys [config-path config registry io load-error]} _]
  (let [checks (concat (when load-error [(doctor/load-check load-error)])
                       (doctor/run-checks config-path config (or registry {}) io))]
    (println (doctor/render checks))
    (if (some #(= :fail (:level %)) checks) 1 0)))

(defn- lock-entry
  "The lock entry of an installed version: the module hash of a package built
  with go install, else the download hash and asset, with the cut-off a
  package from PyPI was resolved with."
  [config name version]
  (let [{:keys [sha256 asset sum exclude-newer]} (store/read-meta config name version)]
    (if sum
      {:version version :sum sum}
      (cond-> {:version version}
        sha256 (assoc :sha256 sha256)
        asset (assoc :asset asset)
        exclude-newer (assoc :exclude-newer exclude-newer)))))

(defn lock
  "Writes breto.lock in the registry directory `dir`: the hash of every
  version a group defined there pins. The versions are installed first,
  beside the active ones, and each one an existing entry lists must match it,
  so a release that changed upstream fails instead of being locked anew.
  Entries of versions no group pins any more are dropped. --check only
  reports whether the file is up to date."
  [{:keys [config groups opts] :as ctx} [dir & more]]
  (when (or (nil? dir) (seq more))
    (throw (ex-info "lock needs one registry directory" {:breto/problem :usage})))
  (let [dir (str (fs/canonicalize dir))
        path (str (fs/path dir registry/lock-file-name))
        own (filter (fn [[_ {:keys [files]}]] (some #(= dir (str (fs/canonicalize (fs/parent %)))) files)) groups)
        specs (filterv #(second (batch/split-spec %)) (registry/expand groups (map key own)))
        results (install-results ctx "lock" specs (fn [_ version] {:version version :activate? false :verify? (:verify opts true)}))
        failed? (some #(= :failed (:status %)) results)
        before (lockfile/read-lock path)
        text (lockfile/render {:header (:header before)
                               :entries (into {} (for [spec specs
                                                       :let [[name version] (batch/split-spec spec)]]
                                                   [spec (dissoc (lock-entry config name version) :version)]))})
        current? (and (fs/exists? path) (= text (slurp path)))]
    (cond
      failed? (batch/finish! "lock" results)
      current? (do (println (str "[OK] " path " is up to date")) 0)
      (:check opts) (do (println (str "[ERROR] " path " is out of date; run breto lock " dir)) 1)
      :else (do (spit path text)
                (println (str "[OK] wrote " path " with " (count specs) " versions"))
                0))))
