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
            [breto.install :as install]
            [breto.lockfile :as lockfile]
            [breto.registry :as registry]
            [breto.report :as report]
            [breto.source :as source]
            [breto.store :as store]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn- targets [{:keys [config]} args]
  (if (seq args) args (:packages config)))

(defn- tracker
  "The progress callback for `command`: the live display and the recorder."
  [{:keys [reporter]} command]
  (batch/broadcast (reporter command) (batch/recorder command)))

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

(defn- installed-result [name {:keys [version installed? verified? signed?]}]
  (cond-> {:package name :version version :status (if installed? :installed :current)}
    (and installed? (not verified?)) (assoc :unverified true)
    signed? (assoc :signed true)))

(defn- installing
  "Runs `install-one` (io, name, version -> install! options) over `specs`
  with progress, pacing and a saved report under `command`."
  [{:keys [config registry io] :as ctx} command specs install-one]
  (let [progress (tracker ctx command)
        transferred (atom 0)
        io (batch/tracked-io io progress transferred)]
    (batch/finish! command
                   (batch/run-batch io specs
                                    ((pacer ctx progress transferred)
                                     (fn [spec]
                                       (let [[name version] (batch/split-spec spec)]
                                         (installed-result name (install/install! config io (registry/lookup registry name)
                                                                                  (install-one name version))))))
                                    progress))))

(defn- locked
  "install! options for `name` from the lock file, when --locked asks for it."
  [{:keys [config opts]} name]
  (when (:locked opts)
    (when-not (:lockfile config)
      (throw (ex-info "--locked needs :lockfile in the config"
                      {:breto/problem :no-lockfile :path nil})))
    (let [{:keys [version sha256]} (lockfile/locked-entry (:lockfile config) name)]
      {:version version :expect-sha256 sha256})))

(defn install [{:keys [opts] :as ctx} args]
  (installing ctx "install" args
              (fn [name version]
                (merge {:version version :verify? (:verify opts true)} (locked ctx name)))))

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
        names (cond (seq args) args
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
    (install/use! config (registry/lookup registry name) version)
    (println (str "[OK] " name " " version " active"))
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
        prune (when (and (:prune config) (empty? args))
                (store/prune-candidates (store/installed-packages config) (:packages config)))
        pruned (batch/run-batch io prune #(uninstall-one ctx % dry-run?) (fn [_]))
        failures (for [name (if (seq args) args (remove (set prune) (store/installed-packages config)))
                       :let [r (try
                                 (doseq [leftover (store/leftovers config name)]
                                   (when-not dry-run? (fs/delete-tree leftover))
                                   (println (str (if dry-run? "would remove " "[OK] removed ") leftover)))
                                 (doseq [version (store/gc-candidates (store/versions config name)
                                                                      (store/current-version config name) keep)]
                                   (when-not dry-run? (store/remove-version! config name version))
                                   (println (str (if dry-run? "would remove " "[OK] removed ") name " " version)))
                                 nil
                                 (catch Exception e (batch/failure io name nil e)))]
                       :when r]
                   r)
        results (into pruned failures)]
    (if (seq results) (batch/finish! "gc" results) 0)))

(defn uninstall [{:keys [config opts io] :as ctx} args]
  (when (empty? args)
    (throw (ex-info "uninstall needs at least one package" {:breto/problem :usage})))
  (batch/finish! "uninstall"
                 (batch/run-batch io args
                                  (fn [name]
                                    (when (and (some #{name} (:packages config)) (not (:force opts)))
                                      (throw (ex-info (str name " is still configured")
                                                      {:breto/problem :still-configured :package name})))
                                    (uninstall-one ctx name (:dry-run opts)))
                                  (fn [_]))))

(defn info [{:keys [registry]} [name]]
  (pprint/pprint (registry/lookup registry name))
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

(defn doctor [{:keys [config-path config registry io]} _]
  (let [checks (doctor/run-checks config-path config (or registry {}) io)]
    (println (doctor/render checks))
    (if (some #(= :fail (:level %)) checks) 1 0)))
