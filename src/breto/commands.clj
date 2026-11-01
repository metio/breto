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
            [breto.inputs :as inputs]
            [breto.install :as install]
            [breto.lockfile :as lockfile]
            [breto.package :as package]
            [breto.platform :as platform]
            [breto.project :as project]
            [breto.registry :as registry]
            [breto.report :as report]
            [breto.run :as run]
            [breto.sealed :as sealed]
            [breto.search :as search]
            [breto.shell :as shell]
            [breto.signature :as signature]
            [breto.source :as source]
            [breto.store :as store]
            [breto.template :as template]
            [breto.version :as version]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn- unsupported
  "The package of `spec` when `registry` knows it and it has no release for
  the platform it was loaded for."
  [registry spec]
  (let [pkg (get registry (first (batch/split-spec spec)))]
    (when (:unsupported pkg) pkg)))

(defn- skip-message [pkg]
  (str (:name pkg) " has no release for " (:platform pkg) ", so it is skipped; it has releases for "
       (str/join ", " (:supported pkg))))

(defn- supported-only
  "`specs` without the packages that have no release for this platform, each
  of those reported on stderr."
  [registry specs]
  (let [skipped (keep #(unsupported registry %) specs)]
    (binding [*out* *err*] (run! #(println (str "[WARN] " (skip-message %))) skipped))
    (remove #(unsupported registry %) specs)))

(defn- targets
  "The packages a command acts on: the named ones, groups expanded, else the
  configured ones. A package a group or the config brings in that has no
  release for this platform is skipped with a warning; one named on its own
  is kept, so the command reports why it cannot install it."
  [{:keys [config groups registry]} args]
  (if (seq args)
    (vec (mapcat #(if (str/starts-with? % "@") (supported-only registry (registry/expand groups [%])) [%]) args))
    (vec (supported-only registry (:packages config)))))

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

(def ^:private project-commands
  "Commands that install what a project needs while somebody waits for it, so
  :delay does not apply to them."
  #{"env" "run"})

(defn- pacer
  "Paces a batch under `command` so that :delay follows only packages that
  received download bytes; a package found up to date, or a download refused
  outright, costs one small request."
  [{:keys [config opts io sleep-second!]} command progress transferred]
  (fn [f]
    (batch/paced (or (:delay opts) (when-not (project-commands command) (:delay config)) 0)
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
  the version `pins` names for it when that is installed, else of its active
  version, else of its newest installed one."
  ([config registry pins name] (tool-command config registry pins name name))
  ([config registry pins name command]
   (when-let [pkg (get registry name)]
     (when-let [version (or (let [pinned (get pins name)]
                              (when (and pinned (fs/directory? (store/version-dir config name pinned))) pinned))
                            (store/current-version config name)
                            (:version (first (store/versions config name))))]
       (get (install/bin-targets config pkg version) command)))))

(defn- tool-pins
  "The version `specs` pin for each package, the newest where they pin
  several, so that a build uses the go, uv or node that the group or project
  next to it names."
  [specs]
  (reduce (fn [pins spec]
            (let [[name pinned] (batch/split-spec spec)]
              (if (and pinned (or (nil? (pins name)) (version/older? (pins name) pinned)))
                (assoc pins name pinned)
                pins)))
          {} specs))

(defn- lock-options
  "install! options checking `pkg` at `version` against the lock files of
  the registry directories, when one of them lists it for its platform."
  [locks pkg version]
  (when-let [{:keys [sha256 sum exclude-newer]} (and version (lockfile/hashes (get locks (str (:name pkg) "@" version)) pkg))]
    (cond-> {}
      sha256 (assoc :expect-sha256 sha256)
      sum (assoc :expect-sum sum)
      exclude-newer (assoc :exclude-newer exclude-newer))))

(defn- install-results
  "Runs `install-one` (name, version -> install! options) over `specs` with
  progress and pacing under `command`, returning a result per spec. A package
  built by another one uses the version of the builder that `context`, the
  specs it is installed for and `specs` by default, pins."
  ([ctx command specs install-one] (install-results ctx command specs install-one specs))
  ([{:keys [config registry locks io] :as ctx} command specs install-one context]
   (let [pins (tool-pins context)
         tool (partial tool-command config registry pins)
         progress (batch/broadcast (tracker ctx command) (hook-listener ctx command))
         transferred (atom 0)
         io (batch/tracked-io io progress transferred)]
     (batch/run-batch io (vec (tools-first registry specs))
                      ((pacer ctx command progress transferred)
                       (fn [spec]
                         (let [[name version] (batch/split-spec spec)
                               pkg (registry/lookup registry name)
                               options (install-one name version)
                               locked (lock-options locks pkg (or (:version options) version))
                               unlocked? (and (::pinned options) (seq locks) (nil? locked))
                               _ (when (and unlocked? (:require-locked config))
                                   (throw (ex-info (str name " " version " is in no lock file of the registry directories, and :require-locked is set")
                                                   {:breto/problem :not-locked :package name :version version})))
                               options (cond-> (into (or locked {}) (remove (comp nil? val)) (dissoc options ::pinned))
                                         (= :go (package/source-kind pkg)) (assoc :go (tool "go"))
                                         (= :pypi (package/source-kind pkg)) (assoc :uv (tool "uv"))
                                         (= :npm (package/source-kind pkg)) (assoc :npm (tool "node" "npm"))
                                         (= :cargo (package/source-kind pkg)) (assoc :cargo (tool "rustup" "cargo"))
                                         (= :ghcup (package/source-kind pkg)) (assoc :ghcup (tool "ghcup"))
                                         (= :coursier (package/source-kind pkg)) (assoc :coursier (tool "coursier" "cs")))
                               result (installed-result name (install/install! config io pkg options))
                               warnings (cond-> []
                                          (package/superseded-warning pkg) (conj (package/superseded-warning pkg))
                                          unlocked? (conj (str version " is in no lock file of the registry directories, so only upstream verified it")))]
                           (cond-> result
                             (seq warnings) (clojure.core/update :warnings (fnil into []) warnings)))))
                      progress))))

(defn- installing
  "install-results with a printed and saved report under `command`."
  ([ctx command specs install-one] (installing ctx command specs install-one specs))
  ([ctx command specs install-one context]
   (batch/finish! command (install-results ctx command specs install-one context))))

(defn- locked
  "install! options for `name` from the lock file, when --locked asks for it."
  [{:keys [config opts registry]} name]
  (when (:locked opts)
    (when-not (:lockfile config)
      (throw (ex-info "--locked needs :lockfile in the config"
                      {:breto/problem :no-lockfile :path nil})))
    (let [entry (lockfile/locked-entry (:lockfile config) name)
          {:keys [sha256 sum exclude-newer]} (lockfile/hashes entry (get registry name))
          version (:version entry)]
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

(defn install [{:keys [opts chosen?] :as ctx} args]
  (let [code (installing ctx "install" (targets ctx args)
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

(defn- package-commands
  "The :commands of the packages `specs` name."
  [registry specs]
  (into {} (mapcat #(:commands (registry/lookup registry (first (batch/split-spec %))))) specs))

(defn- project
  "The project of `dir`: its packages with groups expanded, its variables and
  commands, and the files they come from; see breto.project. The project's
  own commands and those of its groups win over those of its packages. A package
  without a release for this platform is left out with a warning, so the
  project's other tools still work there."
  [groups registry dir]
  (let [proj (project/load-project groups dir)
        skipped (keep #(unsupported registry %) (:specs proj))
        specs (vec (remove (partial unsupported registry) (:specs proj)))]
    (-> proj
        (assoc :specs specs)
        (clojure.core/update :commands #(merge (package-commands registry specs) %))
        (clojure.core/update :warnings into (map skip-message skipped)))))

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
  with {install-dir} as the project's stable link to that directory and
  {install-dirs} as the directory of all those links; {home} and {homes} are
  the deprecated names of the two. Two packages setting one variable to
  different values is an error, since only one value can win, unless the
  project's own :env sets it."
  [config registry {:keys [dir specs] :as project}]
  (let [entries (for [spec specs
                      :let [[name version] (batch/split-spec spec)
                            pkg (registry/lookup registry name)]
                      :when (seq (:env pkg))
                      :let [version (or version (store/current-version config name)
                                        (:version (first (store/versions config name))))]
                      :when version
                      :let [pkg (package/at pkg version)
                            vars (assoc (package/template-vars pkg {:tag (package/tag-of-version pkg version) :version version})
                                        :install-dir (str (env/home config dir name))
                                        :install-dirs (str (fs/parent (env/home config dir name))))
                            vars (assoc vars :home (:install-dir vars) :homes (:install-dirs vars))]]
                  {:name name
                   :home (str (store/version-dir config name version))
                   :vars (update-vals (:env pkg) #(template/render % vars))})]
    (doseq [[k owners] (group-by first (for [{:keys [name vars]} entries, [k v] vars] [k name v]))
            :when (not (contains? (:env project) k))
            :when (< 1 (count (distinct (map #(nth % 2) owners))))]
      (throw (ex-info (str "the project's packages " (str/join " and " (map second owners)) " all set " k)
                      {:breto/problem :env-conflict :variable k :packages (mapv second owners)})))
    {:homes (into {} (map (juxt :name :home)) entries)
     :vars (into {} (mapcat :vars) entries)}))

(defn- project-setup
  "{:links :homes :vars} of `proj`: what its bin directory links, the
  commands of its packages and wrappers running its own commands, the
  version directories its homes link to, and the variables it sets, those
  of its packages under its own. A project command named like a command of
  one of its packages is an error, since the wrapper would shadow it."
  [config registry proj]
  (let [dir (:dir proj)
        package-links (into {} (mapcat second (project-targets config registry proj)))
        {:keys [homes vars]} (project-environment config registry proj)
        clashes (sort (filter package-links (keys (:commands proj))))
        _ (when (seq clashes)
            (throw (ex-info (str "the project's commands " (str/join ", " clashes) " have the names of commands its packages link")
                            {:breto/problem :invalid-project :file (first (:files proj))
                             :problems [(str "rename the commands " (str/join ", " clashes))]})))
        _ (when-let [on-enter (:on-enter proj)]
            (when-not (get-in proj [:commands on-enter])
              (throw (ex-info (str ":on-enter names " on-enter ", which is none of the project's commands")
                              {:breto/problem :invalid-project :file (first (:files proj))
                               :problems [(str ":on-enter names the unknown command " on-enter)]}))))
        template (run/template-vars dir (env/bin-dir config dir) (env/homes-dir config dir) (platform/current config))
        commands (env/write-commands! config dir (into {} (for [name (keys (:commands proj))]
                                                           [name (run/wrapper dir name)])))
        stale (inputs/stale-test (inputs/of-project proj) (inputs/stamp-path config dir))
        targets (env/link-targets! config dir package-links)
        wrapped (into {} (filter (fn [[name target]] (and (not= name "breto") (run/program? target)))) targets)
        tools (env/write-tools! config dir (into {} (for [[name target] wrapped]
                                                      [name (run/tool-wrapper dir name target stale)])))]
    {:links (merge (apply dissoc targets (keys wrapped)) tools commands)
     :homes homes
     :vars (merge vars (run/render-env (:env proj) template))}))

(defn- project-install
  "install! options for a version a project needs: installed beside the
  active one, not in its place, and expected in a registry lock file when it
  is pinned."
  [opts]
  (fn [_ version] (cond-> {:version version :activate? false :verify? (:verify opts true)}
                    version (assoc ::pinned true))))

(defn- prepared-project!
  "The project at or above --dir with its bin directory linked: the project
  with :bin, :vars and :code 0, after installing the versions it needs that
  are missing unless --no-install, or with the failing :code of that install.
  Warnings about the project files go to stderr."
  [{:keys [config registry groups opts] :as ctx} command]
  (let [start (or (:dir opts) ".")
        dir (or (project/find-dir start)
                (throw (ex-info (str "no " project/file-name " or " project/local-file-name " in "
                                     (fs/absolutize start) " or above it")
                                {:breto/problem :no-project :dir (str (fs/absolutize start))})))
        {:keys [warnings] :as proj} (project groups registry dir)
        _ (binding [*out* *err*] (run! #(println (str "[WARN] " %)) warnings))
        missing (for [[spec targets] (project-targets config registry proj)
                      :when (nil? targets)]
                  spec)
        _ (when (and (seq missing) (false? (:install opts)))
            (throw (ex-info (str "the project needs " (str/join ", " missing) ", which " (if (= 1 (count missing)) "is" "are") " not installed")
                            {:breto/problem :env-missing :missing (vec missing) :project dir})))
        code (if (seq missing)
               (binding [*out* *err*]
                 (with-store-lock ctx #(installing ctx command (vec missing) (project-install opts) (:specs proj))))
               0)]
    (if (pos? code)
      (assoc proj :code code)
      (let [{:keys [links homes vars]} (project-setup config registry proj)
            bin (env/link! config dir links homes)]
        (inputs/stamp! config dir (inputs/of-project proj))
        (assoc proj :code 0 :vars vars :bin bin)))))

(defn- declared-links
  "Command -> target for the active version of every package :packages
  declares, the commands the global bin directory links for them."
  [{:keys [config registry groups]}]
  (into {} (for [spec (registry/expand groups (:packages config))
                 :let [[name] (batch/split-spec spec)
                       pkg (get registry name)
                       version (store/current-version config name)]
                 :when (and pkg version)
                 [command target] (install/bin-targets config pkg version)]
             [command (str target)])))

(defn- path-for
  "The PATH `env` sets, with `bin` the project's bin directory or nil. With
  --only project it holds the project's bin directory and the system
  directories of PATH, so nothing the user installed elsewhere is found;
  --only declared adds the commands of the configuration's :packages."
  [{:keys [config opts] :as ctx} bin]
  (let [path (System/getenv "PATH")]
    (case (:only opts)
      nil (if bin (env/path-value config bin path) (env/path-outside config path))
      "project" (env/only-path config [bin] path)
      "declared" (env/only-path config [bin (env/declared-bin! config (declared-links ctx))] path))))

(defn env
  "Links the versions the nearest project files list into the project's bin
  directory, with wrappers for the project's commands, installing those that
  are missing without activating them, and prints the shell code that puts
  that directory in front of PATH and sets the project's variables.
  Everything else goes to stderr, so the output can be evaluated. --enter,
  which the shell hook passes on entering the project, also prints the
  menu of its commands and runs its :on-enter command. Only installing
  needs the store lock, so linking installed versions never waits for
  another run. Outside a project it installs nothing and prints the code
  that leaves the project a shell entered before. --only project and --only
  declared narrow PATH to what breto links; see `path-for`."
  [{:keys [config opts] :as ctx} _]
  (when-not (contains? #{nil "project" "declared"} (:only opts))
    (throw (ex-info (str "--only is project or declared, not " (:only opts)) {:breto/problem :usage})))
  (when (and (:only opts) (= "github" (:shell opts)))
    (throw (ex-info "--only sets PATH, which --shell github leaves to $GITHUB_PATH" {:breto/problem :usage})))
  (if-not (project/find-dir (or (:dir opts) "."))
    (let [shell (case (:shell opts) "fish" :fish "github" :github :sh)
          code (env/leaving shell (path-for ctx nil))]
      (when-not (str/blank? code) (println code))
      0)
    (let [{:keys [code dir files bin vars] :as proj} (prepared-project! ctx "env")]
      (if (pos? code)
        code
        (let [shell (case (:shell opts) "fish" :fish "github" :github :sh)
              enter? (:enter opts)
              on-enter (when enter? (:on-enter proj))]
          (when (and enter? (:menu proj))
            (binding [*out* *err*] (run! println (run/menu proj))))
          (println (str (env/exports shell (path-for ctx bin) dir bin files vars)
                        (when (and on-enter (not= :github shell))
                          (str "\n" "breto run --dir " (shell/quote-arg dir) " " (shell/quote-arg on-enter)
                               (when (= :fish shell) ";")))))
          0)))))

(defn- choose-command
  "The project command chosen in the fuzzy finder, else through a numbered
  prompt, or nil."
  [{:keys [config io]} {:keys [commands] :as proj}]
  (let [names (sort (keys commands))]
    (when-not (:interactive? io)
      (throw (ex-info (str "run needs a command; the project has " (str/join ", " names))
                      {:breto/problem :usage})))
    (if-let [finder (search/finder config (which io))]
      (let [command (if (vector? (:finder config))
                      finder
                      (into finder ["--delimiter" "\t" "--with-nth" "1,2" "--prompt" "breto run> "]))
            {:keys [exit out]} ((:pick io) command (str (str/join "\n" (for [n names] (str n "\t" (get-in commands [n :description] ""))))
                                                       "\n"))]
        (when (= 0 exit) (first (search/selected out))))
      (do (run! println (rest (run/menu proj)))
          (let [answer (str/trim (or ((:ask io) "Command to run (blank to stop): ") ""))]
            (cond (str/blank? answer) nil
                  (re-matches #"\d+" answer) (get (vec names) (dec (parse-long answer)))
                  :else answer))))))

(defn run-command
  "Runs the project command named first in `args` with the rest of them,
  after the commands it depends on, in the project directory with the
  project's bin directory first on PATH and its variables set. Without a
  name, the command is chosen interactively. Returns the exit code of the
  first command that fails, else 0."
  [{:keys [config io] :as ctx} [name & args]]
  (let [{:keys [code dir bin vars commands] :as proj} (prepared-project! ctx "run")]
    (cond
      (pos? code) code
      (empty? commands) (throw (ex-info (str "the project " dir " defines no :commands")
                                        {:breto/problem :unknown-command :command name :known []}))
      :else
      (if-let [name (or name (choose-command ctx proj))]
        (let [platform (platform/current config)]
          (run/run-order! (project/run-order proj name platform)
                          {:dir dir :env vars :args (vec args)
                           :path (env/path-value config bin (System/getenv "PATH"))
                           :vars (run/template-vars dir bin (env/homes-dir config dir) platform)}
                          (or (:exec io) run/exec!)))
        0))))

(defn exec-tool
  "Runs the command `name` of the project's packages with `args` in the current
  directory, for the script linked into the project's bin directory when a file
  deciding the project's versions looks changed. Content that did not change
  only renews the stamp; anything else installs what is missing and links the
  project again. Returns the exit code of the command."
  [{:keys [config registry groups opts io] :as ctx} [name & args]]
  (let [start (or (:dir opts) ".")
        dir (project/find-dir start)
        current (when dir (inputs/of-project (project groups registry dir)))
        code (cond
               (nil? dir) 0
               (inputs/fresh? config dir current) (do (inputs/touch! config dir) 0)
               :else (:code (prepared-project! ctx "exec")))]
    (cond
      (nil? dir)
      (throw (ex-info (str "no " project/file-name " or " project/local-file-name " in "
                           (fs/absolutize start) " or above it")
                      {:breto/problem :no-project :dir (str (fs/absolutize start))}))
      (pos? code) code
      :else
      (let [entry (fs/path (env/bin-dir config dir) name)]
        (when-not (fs/exists? entry)
          (throw (ex-info (str "the project " dir " no longer links the command " name)
                          {:breto/problem :unknown-command :command name
                           :known (sort (map (comp str fs/file-name) (fs/list-dir (env/bin-dir config dir))))})))
        ((or (:exec io) run/exec!) {:command (into [(str entry)] args)
                                    :env {"BRETO_LINKED" "1"}
                                    :dir (fs/cwd)})))))

(defn- refresh-projects
  "Installs the versions the projects with an environment need and links
  each project's bin directory again, so a version a shared group moved to
  reaches every project, its open shells included. A project whose file no
  longer loads keeps its links and is reported. Returns the install results."
  [{:keys [config registry groups opts] :as ctx} command]
  (let [projects (for [environment (remove env/stale? (env/environments config))
                       :let [dir (:project environment)]]
                   (try (project groups registry dir)
                        (catch Exception e {:dir dir :problem (ex-message e)})))
        projects (doall projects)
        missing (distinct (for [p projects
                                :when (not (:problem p))
                                [spec targets] (try (project-targets config registry p) (catch Exception _ []))
                                :when (and (nil? targets) (second (batch/split-spec spec)))]
                            spec))
        results (when (seq missing)
                  (install-results ctx command (vec missing) (project-install opts)
                                   (mapcat :specs (remove :problem projects))))]
    (doseq [p projects]
      (if (:problem p)
        (println (str "[WARN] project " (:dir p) " keeps its links: " (:problem p)))
        (try
          (let [targets (project-targets config registry p)]
            (if (every? second targets)
              (let [{:keys [links homes]} (project-setup config registry p)]
                (env/link! config (:dir p) links homes)
                (inputs/stamp! config (:dir p) (inputs/of-project p)))
              (println (str "[WARN] project " (:dir p) " keeps its links: "
                            (str/join ", " (map first (remove second targets))) " not installed"))))
          (catch Exception e
            (println (str "[WARN] project " (:dir p) " keeps its links: " (ex-message e)))))))
    (vec results)))

(defn install-registries
  "Installs the releases of the published registries `names`, under sync the
  version the lock file lists, else the pinned or latest one. Their checksums
  are signed, so on a host without cosign the built-in cosign comes first.
  Returns the install results."
  [{:keys [config opts registry io] :as ctx} command names]
  (let [path (:lockfile config)
        entries (when (and (= "sync" command) path (fs/exists? path)) (:entries (lockfile/read-lock path)))
        cosign? (and (seq names) (get registry "cosign") (nil? (signature/locate config (which io))))]
    (install-results ctx command (cond->> (vec names) cosign? (into ["cosign"]))
                     (fn [name _]
                       (if-let [entry (get entries name)]
                         (let [{:keys [sha256 sum]} (lockfile/hashes entry (get registry name))]
                           {:version (:version entry) :verify? (:verify opts true) :expect-sha256 sha256 :expect-sum sum})
                         (merge {:version (get-in config [:pins name]) :verify? (:verify opts true)} (locked ctx name)))))))

(defn- done
  "The packages of `results`, which a command installed before the rest."
  [results]
  (set (map :package results)))

(defn update
  "Updates the named packages, else the configured ones and then the projects
  with an environment. The published registries installed first come with
  their results under :registry-results."
  [{:keys [config opts registry-results] :as ctx} args]
  (let [results (install-results ctx "update" (vec (remove (comp (done registry-results) first batch/split-spec) (targets ctx args)))
                                 (fn [name version]
                                   (merge {:version (or version (get-in config [:pins name])) :verify? (:verify opts true)} (locked ctx name))))]
    (batch/finish! "update" (-> (vec registry-results) (into results) (into (when (empty? args) (refresh-projects ctx "update")))))))

(defn outdated
  "The packages whose latest release differs from the active version, or from
  the version a group or argument pins."
  [{:keys [config registry io] :as ctx} args]
  (let [progress (tracker ctx "outdated")]
    (batch/finish! "outdated"
                   (batch/run-batch io (targets ctx args)
                                    ((pacer ctx "outdated" progress (atom 0))
                                     (fn [spec]
                                       (progress {:type :stage :stage :resolve})
                                       (let [[name pinned] (batch/split-spec spec)
                                             active (or pinned (get-in config [:pins name]) (store/current-version config name))
                                             latest (:version (install/with-stage :resolve {:package name}
                                                                #(source/newest (registry/lookup registry name) io)))]
                                         {:package name :version latest :from active
                                          :status (if (= latest active) :current :outdated)})))
                                    progress))))

(defn- named-platforms
  "The platforms --platforms names, comma-separated or all, else the one
  breto runs for."
  [{:keys [config opts]}]
  (let [value (:platforms opts)
        names (cond (nil? value) [(platform/current config)]
                    (= "all" value) (sort platform/known)
                    :else (vec (distinct (remove str/blank? (str/split (str value) #",")))))]
    (when-let [unknown (seq (remove platform/known names))]
      (throw (ex-info (str "--platforms names " (str/join ", " unknown) ", which is none of all, "
                           (str/join ", " (sort platform/known)))
                      {:breto/problem :usage})))
    names))

(defn check
  "Checks the configured packages, those named, or with --all every package of
  the registries, against their latest release without downloading it, on
  the platforms --platforms names. A package checked for a platform it has
  no release for fails when it is named, and is passed over otherwise."
  [{:keys [registry opts io] :as ctx} args]
  (let [progress (tracker ctx "check")
        platforms (named-platforms ctx)
        names (if (:all opts)
                (sort (keys registry))
                (distinct (map (comp first batch/split-spec) (targets ctx args))))
        named (set (map (comp first batch/split-spec) args))
        io (assoc io :progress progress)
        results (vec (mapcat (fn [p]
                               (let [pkgs (into {} (for [n names
                                                         :let [pkg (package/for-platform (registry/lookup registry n) p)]
                                                         :when (or (named n) (not (:unsupported pkg)))]
                                                     [n pkg]))]
                                 (when (next platforms) (println (str "Checking " p ":")))
                                 (batch/run-batch io (sort (keys pkgs))
                                                  ((pacer ctx "check" progress (atom 0))
                                                   #(check/check-package io (get pkgs %)))
                                                  progress)))
                             platforms))]
    (batch/finish! "check" results)))

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
                            ", which has no " project/file-name " or " project/local-file-name " any more"))))
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
  [{:keys [config opts io registry registry-results] :as ctx} _]
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
      (let [installed (install-results ctx "sync" (vec (for [[name {:keys [version]}] entries
                                                            :when (not ((done registry-results) name))]
                                                        (str name "@" version)))
                                       (fn [name version]
                                         (let [{:keys [sha256 sum exclude-newer]} (lockfile/hashes (get entries name) (get registry name))]
                                           {:version version :verify? (:verify opts true)
                                            :expect-sha256 sha256 :expect-sum sum :exclude-newer exclude-newer})))
            removed (batch/run-batch io extra #(uninstall-one ctx % false) (fn [_]))]
        (batch/finish! "sync" (-> (vec registry-results) (into installed) (into removed) (into (refresh-projects ctx "sync"))))))))

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

(defn validate
  "Checks the registry directory `dir` on its own, without downloading
  anything, and exits 1 when any definition or group in it has a problem."
  [_ [dir & more]]
  (when (or (nil? dir) (seq more))
    (throw (ex-info "validate needs one registry directory" {:breto/problem :usage})))
  (when-not (fs/directory? dir)
    (throw (ex-info (str dir " is no directory") {:breto/problem :usage})))
  (let [problems (registry/validate dir)
        files (count (fs/glob dir "*.edn"))]
    (run! #(println (str "[FAIL] " %)) problems)
    (if (seq problems)
      (do (println (str "[FAIL] " dir ": " (count problems) " problems in " files " files")) 1)
      (do (println (str "[OK] " dir ": " files " files are valid")) 0))))

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

(defn- foreign-hash
  "The lock result for `spec` on `platform`, hashing the download breto would
  install there and comparing it with `previous`, the hashes an existing
  entry gives that platform."
  [{:keys [config io]} pkg version previous]
  (let [{:keys [sha256] :as hashes} (install/download-hash! config io pkg version)]
    (when (and (:sha256 previous) (not= (str/lower-case (:sha256 previous)) (str/lower-case sha256)))
      (throw (ex-info (str (:name pkg) " " version " for " (:platform pkg) " differs from the lock file: expected "
                           (:sha256 previous) ", got " sha256)
                      {:breto/problem :checksum-mismatch :package (:name pkg) :version version :stage :verify
                       :asset (:asset hashes) :expected (:sha256 previous) :actual sha256 :origin :lockfile})))
    {:package (:name pkg) :version version :status :current :platform (:platform pkg) :hashes hashes}))

(defn lock
  "Writes breto.lock in the registry directory `dir`: the hash of every
  version a group defined there pins, for each platform --platforms names,
  by default the one breto runs for. The versions are installed first,
  beside the active ones, and the downloads of other platforms are verified
  and hashed without being installed. Each one an existing entry lists must
  match it, so a release that changed upstream fails instead of being locked
  anew. The hashes of platforms not named stay. Entries of versions no group
  pins any more are dropped. --check only reports whether the file is up to
  date."
  [{:keys [config groups registry opts io] :as ctx} [dir & more]]
  (when (or (nil? dir) (seq more))
    (throw (ex-info "lock needs one registry directory" {:breto/problem :usage})))
  (let [dir (str (fs/canonicalize dir))
        path (str (fs/path dir registry/lock-file-name))
        own (filter (fn [[_ {:keys [files]}]] (some #(when-let [parent (fs/parent %)] (= dir (str (fs/canonicalize parent)))) files)) groups)
        specs (filterv #(second (batch/split-spec %)) (registry/expand groups (map key own)))
        platforms (named-platforms ctx)
        here (platform/current config)
        shared? #(contains? package/neutral-kinds (package/source-kind %))
        pkg-of #(registry/lookup registry (first (batch/split-spec %)))
        local (filterv #(let [pkg (pkg-of %)]
                          (or (shared? pkg) (and (some #{here} platforms) (not (:unsupported pkg)))))
                       specs)
        before (lockfile/read-lock path)
        results (install-results ctx "lock" local (fn [_ version] {:version version :activate? false :verify? (:verify opts true)}))
        foreign (for [spec specs
                      p platforms
                      :when (not= p here)
                      :let [pkg (package/for-platform (pkg-of spec) p)]
                      :when (not (or (shared? pkg) (:unsupported pkg)))]
                  [spec pkg])
        labelled (into {} (for [[spec pkg] foreign] [(str spec " for " (:platform pkg)) [spec pkg]]))
        foreign-results (batch/run-batch io (mapv (fn [[spec pkg]] (str spec " for " (:platform pkg))) foreign)
                                         (fn [label]
                                           (let [[spec pkg] (labelled label)]
                                             (foreign-hash ctx pkg (second (batch/split-spec spec))
                                                           (lockfile/hashes (get (:entries before) spec) pkg))))
                                         (tracker ctx "lock"))
        all-results (into results foreign-results)
        failed? (some #(= :failed (:status %)) all-results)
        local? (set local)
        entries (into {} (for [spec specs
                               :let [[name version] (batch/split-spec spec)
                                     pkg (pkg-of spec)
                                     entry (cond-> (dissoc (get (:entries before) spec) :version)
                                             (local? spec) (lockfile/with-hashes pkg (dissoc (lock-entry config name version) :version)))
                                     entry (reduce (fn [e {:keys [hashes platform]}]
                                                     (lockfile/with-hashes e (package/for-platform pkg platform) hashes))
                                                   entry
                                                   (filter #(and (:hashes %) (= name (:package %)) (= version (:version %))) foreign-results))]]
                           [spec entry]))
        text (lockfile/render {:header (:header before) :entries entries})
        current? (and (fs/exists? path) (= text (slurp path)))]
    (cond
      failed? (batch/finish! "lock" all-results)
      current? (do (println (str "[OK] " path " is up to date")) 0)
      (:check opts) (do (println (str "[ERROR] " path " is out of date; run breto lock " dir)) 1)
      :else (do (spit path text)
                (println (str "[OK] wrote " path " with " (count specs) " versions"
                              (when (< 1 (count platforms)) (str " for " (str/join ", " platforms)))))
                0))))
