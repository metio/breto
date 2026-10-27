;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.main
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [breto.config :as config]
            [breto.diagnose :as diagnose]
            [breto.doctor :as doctor]
            [breto.http :as http]
            [breto.install :as install]
            [breto.log :as log]
            [breto.registry :as registry]
            [breto.report :as report]
            [breto.source :as source]
            [breto.store :as store]
            [clojure.pprint :as pprint]
            [clojure.stacktrace :as stacktrace]
            [clojure.string :as str]))

(def ^:private usage
  "Usage: breto <command> [args] [--config FILE] [--debug]

Commands:
  list                      installed packages and versions (* marks the active one)
  install PKG[@VERSION]...  install and activate, latest unless a version is given
  use PKG VERSION           activate an installed version
  update [PKG...]           install the latest (or pinned) version of the configured packages
  outdated [PKG...]         configured packages with a newer release
  gc [PKG...] [--keep N]    delete all but the N most recent versions, never the active one;
                            with :prune true and no PKG, also uninstall unconfigured packages
  uninstall PKG...          delete a package and every link into it
  info PKG                  the resolved package definition
  report                    the reports of the last run of each command
  doctor                    check the environment, configuration and store

install, update and outdated wait between packages for :delay seconds or the rate limit
upstream hosts announce, whichever is longer; --delay N overrides :delay.
gc and uninstall accept --dry-run. uninstall refuses configured packages unless --force,
since the next update would install them again.
install and update refuse downloads they cannot verify unless --no-verify is given.
Every failure is explained with what to do; --debug adds each request and stack traces.")

(defn- bin-links [pkg]
  (keys (:bins pkg)))

(defn- current [cfg name]
  (store/current-version cfg name))

(defn- split-spec [spec]
  (let [[name version] (str/split spec #"@" 2)]
    [name version]))

(defn- targets [cfg args]
  (if (seq args) args (:packages cfg)))

(defn- failure [package version e]
  (when log/*debug*
    (binding [*out* *err*] (stacktrace/print-cause-trace e)))
  {:package package :version (or version (:version (ex-data e))) :status :failed
   :diagnosis (diagnose/diagnose e (diagnose/github-probes http/io))})

(defn- batch
  "Runs `f` for every name, collecting a result per name; a failure becomes a
  diagnosed result instead of stopping the others."
  [names f]
  (vec (for [name names]
         (try (f name)
              (catch Exception e
                (failure (first (split-spec name)) (second (split-spec name)) e))))))

(defn paced
  "Wraps `f` so that each call first waits for the longer of `delay` seconds,
  skipped before the first call, and the wait upstream hosts ask for. A call
  refused by a rate limit is retried once after the wait the host names."
  [delay upstream-wait sleep! f]
  (let [first? (atom true)]
    (fn [x]
      (let [own (if (compare-and-set! first? true false) 0 delay)
            upstream (upstream-wait)]
        (when (pos? (max own upstream))
          (sleep! (max own upstream) (if (> upstream own) "the rate limit of an upstream host" ":delay"))))
      (try
        (f x)
        (catch clojure.lang.ExceptionInfo e
          (let [upstream (upstream-wait)]
            (if (and (http/rate-limited? (ex-data e)) (pos? upstream))
              (do (sleep! upstream "the rate limit of an upstream host")
                  (f x))
              (throw e))))))))

(defn- pause [cfg opts]
  (fn [f]
    (paced (or (:delay opts) (:delay cfg) 0)
           (:upstream-wait http/io)
           (fn [seconds why]
             (binding [*out* *err*] (println (str "waiting " seconds "s for " why)))
             (Thread/sleep (* 1000 (long seconds))))
           f)))

(defn- finish!
  "Prints and saves the report for `command`, returning the exit code."
  [command results]
  (let [run {:command command :results results}]
    (println (report/render run))
    (try (report/save! run)
         (catch Exception e
           (binding [*out* *err*]
             (println (str "[WARN] could not save the report to " (report/state-path) ": " (ex-message e))))))
    (if (some #(= :failed (:status %)) results) 1 0)))

(defn- installed-result [name {:keys [version installed?]}]
  {:package name :version version :status (if installed? :installed :current)})

(defn- cmd-list [cfg reg _]
  (doseq [name (store/installed-packages cfg)]
    (let [active (current cfg name)
          versions (map (fn [{:keys [version]}] (str version (when (= version active) "*")))
                        (store/versions cfg name))]
      (println (str name "\t" (str/join " " versions)))))
  0)

(defn- cmd-install [cfg reg args opts]
  (finish! "install"
           (batch args
                  ((pause cfg opts)
                   (fn [spec]
                    (let [[name version] (split-spec spec)]
                      (installed-result name (install/install! cfg http/io (registry/lookup reg name)
                                                               {:version version :verify? (:verify opts true)}))))))))

(defn- cmd-update [cfg reg args opts]
  (finish! "update"
           (batch (targets cfg args)
                  ((pause cfg opts)
                   (fn [name]
                    (installed-result name (install/install! cfg http/io (registry/lookup reg name)
                                                             {:version (get-in cfg [:pins name])
                                                              :verify? (:verify opts true)})))))))

(defn- cmd-outdated [cfg reg args opts]
  (finish! "outdated"
           (batch (targets cfg args)
                  ((pause cfg opts)
                   (fn [name]
                    (let [active (current cfg name)
                          latest (:version (install/with-stage :resolve {:package name}
                                             #(source/latest (registry/lookup reg name) http/io)))]
                      {:package name :version latest :from active
                       :status (if (= latest active) :current :outdated)}))))))

(defn- cmd-use [cfg reg [name version]]
  (when-not (and name version)
    (throw (ex-info "use needs a package and a version" {:breto/problem :usage})))
  (install/use! cfg (registry/lookup reg name) version)
  (println (str "[OK] " name " " version " active"))
  0)

(defn- claimed-links
  "Link name -> configured package, for the links configured packages own."
  [cfg reg]
  (into {} (for [name (:packages cfg)
                 link (some-> (get reg name) bin-links)]
             [link name])))

(defn- uninstall-result
  "Removes package `name` with every link into it, or only reports what it
  would remove on a dry run. A link a configured package owns blocks removal:
  deleting the directory would leave that command dangling."
  [cfg reg name dry-run?]
  (when-not (fs/directory? (store/package-dir cfg name))
    (throw (ex-info (str name " is not installed")
                    {:breto/problem :not-in-store :package name :store (:store cfg)
                     :installed (store/installed-packages cfg)})))
  (let [links (store/links-into cfg name (some-> (get reg name) :links keys))
        claimed (claimed-links cfg reg)
        blocking (for [link links
                       :let [owner (claimed (str (fs/file-name link)))]
                       :when (and owner (not= owner name))]
                   [link owner])]
    (when (seq blocking)
      (throw (ex-info (str name " still provides commands of configured packages")
                      {:breto/problem :prune-blocked :package name :blocking (vec blocking)})))
    (when-not dry-run?
      (store/uninstall! cfg name links))
    {:package name :status (if dry-run? :would-remove :removed) :links (vec links)}))

(defn- cmd-gc [cfg reg args opts]
  (let [keep (or (:keep opts) (:keep cfg))
        dry-run? (:dry-run opts)
        prune (when (and (:prune cfg) (empty? args))
                (store/prune-candidates (store/installed-packages cfg) (:packages cfg)))
        pruned (batch prune #(uninstall-result cfg reg % dry-run?))
        failures (for [name (if (seq args) args (remove (set prune) (store/installed-packages cfg)))
                       :let [r (try
                                 (doseq [version (store/gc-candidates (store/versions cfg name) (current cfg name) keep)]
                                   (when-not dry-run? (store/remove-version! cfg name version))
                                   (println (str (if dry-run? "would remove " "[OK] removed ") name " " version)))
                                 nil
                                 (catch Exception e (failure name nil e)))]
                       :when r]
                   r)
        results (into pruned failures)]
    (if (seq results) (finish! "gc" results) 0)))

(defn- cmd-uninstall [cfg reg args opts]
  (when (empty? args)
    (throw (ex-info "uninstall needs at least one package" {:breto/problem :usage})))
  (finish! "uninstall"
           (batch args
                  (fn [name]
                    (when (and (some #{name} (:packages cfg)) (not (:force opts)))
                      (throw (ex-info (str name " is still configured")
                                      {:breto/problem :still-configured :package name})))
                    (uninstall-result cfg reg name (:dry-run opts))))))

(defn- cmd-info [_ reg [name]]
  (pprint/pprint (registry/lookup reg name))
  0)

(defn- cmd-report [_ _ _]
  (if-let [runs (seq (report/load-runs))]
    (do (doseq [run runs]
          (println (str "Finished " (:finished run)))
          (println (report/render run))
          (println))
        0)
    (do (println "No run recorded yet in" (report/state-path)) 0)))

(defn- cmd-doctor [config-path cfg reg]
  (let [checks (doctor/run-checks config-path cfg (or reg {}) http/io)]
    (println (doctor/render checks))
    (if (some #(= :fail (:level %)) checks) 1 0)))

(defn run
  "Runs the command line and returns the process exit code."
  [args]
  (let [{:keys [args opts]} (cli/parse-args args {:coerce {:keep :long :delay :long :debug :boolean :verify :boolean :force :boolean :dry-run :boolean}
                                                    :alias {:d :debug}})
        [command & more] args
        config-path (or (:config opts) (config/default-path))]
    (binding [log/*debug* (boolean (:debug opts))]
      (let [cfg (config/load-config config-path)]
        (if (= "doctor" command)
          (cmd-doctor config-path cfg (try (registry/load-registry (:registries cfg)) (catch Exception _ nil)))
          (let [reg (registry/load-registry (:registries cfg))]
            (case command
              "list" (cmd-list cfg reg more)
              "install" (cmd-install cfg reg more opts)
              "use" (cmd-use cfg reg more)
              "update" (cmd-update cfg reg more opts)
              "outdated" (cmd-outdated cfg reg more opts)
              "gc" (cmd-gc cfg reg more opts)
              "uninstall" (cmd-uninstall cfg reg more opts)
              "info" (cmd-info cfg reg more)
              "report" (cmd-report cfg reg more)
              (do (println usage) (if (#{nil "help"} command) 0 2)))))))))

(defn -main [& args]
  (let [code (try (run args)
                  (catch Exception e
                    (let [{:keys [message summary facts advice]} (diagnose/diagnose e {})]
                      (binding [*out* *err*]
                        (println (str "[ERROR] " message))
                        (when-not (= message summary) (println (str "  " summary)))
                        (doseq [f facts] (println (str "  · " f)))
                        (doseq [a advice] (println (str "  - " a)))))
                    1))]
    (System/exit (if (pos? code) 1 0))))
