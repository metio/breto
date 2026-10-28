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
            [breto.registry :as registry]
            [breto.report :as report]
            [breto.search :as search]
            [breto.signature :as signature]
            [breto.source :as source]
            [breto.store :as store]
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

(defn- hook-listener
  "Runs the configured hooks on the events of `command`; without a hook runner
  in the io, none."
  [{:keys [config io]} command]
  (if-let [run-hook (:run-hook io)]
    (hooks/listener config command
                    {:run-hook run-hook
                     :warn #(binding [*out* *err*] (println (str "[WARN] " %)))
                     :os (System/getProperty "os.name")
                     :which #(some-> (fs/which %) str)})
    (fn [_])))

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

(defn- cosign-first
  "`specs` with the packages that provide cosign moved to the front, so that
  on a fresh host cosign is installed before it is needed to check the
  signatures of the others."
  [registry specs]
  (let [provides? #(some-> (get registry (first (batch/split-spec %))) signature/provides-cosign?)]
    (concat (filter provides? specs) (remove provides? specs))))

(defn- install-results
  "Runs `install-one` (name, version -> install! options) over `specs` with
  progress and pacing under `command`, returning a result per spec."
  [{:keys [config registry io] :as ctx} command specs install-one]
  (let [progress (batch/broadcast (tracker ctx command) (hook-listener ctx command))
        transferred (atom 0)
        io (batch/tracked-io io progress transferred)]
    (batch/run-batch io (vec (cosign-first registry specs))
                     ((pacer ctx progress transferred)
                      (fn [spec]
                        (let [[name version] (batch/split-spec spec)]
                          (installed-result name (install/install! config io (registry/lookup registry name)
                                                                   (install-one name version))))))
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
    (let [{:keys [version sha256]} (lockfile/locked-entry (:lockfile config) name)]
      {:version version :expect-sha256 sha256})))

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

(defn env
  "Links the versions the nearest .breto.edn lists into the project's bin
  directory, installing those that are missing without activating them, and
  prints the shell code that puts that directory in front of PATH. Everything
  else goes to stderr, so the output can be evaluated."
  [{:keys [config registry groups opts] :as ctx} _]
  (let [file (or (env/find-project (or (:dir opts) "."))
                 (throw (ex-info (str "no " env/file-name " in " (fs/absolutize (or (:dir opts) ".")) " or above it")
                                 {:breto/problem :no-project :dir (str (fs/absolutize (or (:dir opts) ".")))})))
        project-dir (str (fs/parent file))
        specs (registry/expand groups (env/read-project file))
        chosen (fn [name version]
                 (or version (store/current-version config name)
                     (:version (first (store/versions config name)))))
        resolve-all #(for [spec specs
                           :let [[name version] (batch/split-spec spec)
                                 pkg (registry/lookup registry name)
                                 version (chosen name version)]]
                       [name version (when version (install/bin-targets config pkg version))])
        missing (for [[spec [_ _ targets]] (map vector specs (resolve-all))
                      :when (nil? targets)]
                  spec)
        code (if (seq missing)
               (binding [*out* *err*]
                 (installing ctx "env" (vec missing)
                             (fn [_ version] {:version version :activate? false :verify? (:verify opts true)})))
               0)]
    (if (pos? code)
      code
      (let [links (into {} (mapcat #(nth % 2) (resolve-all)))
            bin (env/link! config project-dir links)
            shell (if (= "fish" (:shell opts)) :fish :sh)]
        (println (env/exports shell (env/path-value config bin (System/getenv "PATH")) project-dir bin))
        0))))

(defn update [{:keys [config opts] :as ctx} args]
  (installing ctx "update" (targets ctx args)
              (fn [name _]
                (merge {:version (get-in config [:pins name]) :verify? (:verify opts true)} (locked ctx name)))))

(defn outdated [{:keys [config registry io] :as ctx} args]
  (let [progress (tracker ctx "outdated")]
    (batch/finish! "outdated"
                   (batch/run-batch io (targets ctx args)
                                    ((pacer ctx progress (atom 0))
                                     (fn [name]
                                       (progress {:type :stage :stage :resolve})
                                       (let [active (store/current-version config name)
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
        names (cond (seq args) (targets ctx args)
                    (:all opts) (sort (keys registry))
                    :else (targets ctx args))
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
  "Link name -> configured package, for the links configured packages own."
  [{:keys [config registry]}]
  (into {} (for [name (:packages config)
                 link (some-> (get registry name) :bins keys)]
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
                       :let [owner (claimed (str (fs/file-name link)))]
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
                                   (when-not dry-run? (fs/delete-tree leftover))
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
                                          :expect-sha256 (get-in entries [name :sha256])}))
            removed (batch/run-batch io extra #(uninstall-one ctx % false) (fn [_]))]
        (batch/finish! "sync" (into installed removed))))))

(defn uninstall [{:keys [config opts io] :as ctx} args]
  (when (empty? args)
    (throw (ex-info "uninstall needs at least one package" {:breto/problem :usage})))
  (batch/finish! "uninstall"
                 (batch/run-batch io (targets ctx args)
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
