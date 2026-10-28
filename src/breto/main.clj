;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.main
  (:require [babashka.cli :as cli]
            [breto.commands :as commands]
            [breto.config :as config]
            [breto.diagnose :as diagnose]
            [breto.http :as http]
            [breto.lockfile :as lockfile]
            [breto.log :as log]
            [breto.progress :as progress]
            [breto.registry :as registry]
            [breto.store :as store]
            [breto.token :as token]))

(def ^:private usage
  "Usage: breto <command> [args] [--config FILE] [--debug]

Commands:
  list                      installed packages and versions (* marks the active one)
  install PKG[@VERSION]...  install and activate, latest unless a version is given
  use PKG VERSION           activate an installed version
  use PKG --previous        activate the newest installed version older than the active one
  update [PKG...]           install the latest (or pinned) version of the configured packages
  outdated [PKG...]         configured packages with a newer release
  check [PKG...] [--all]    whether the latest release of each configured package (or every
                            package with --all) exists and can be verified, without downloading
  gc [PKG...] [--keep N]    delete all but the N most recent versions, never the active one,
                            and what killed installs left behind;
                            with :prune true and no PKG, also uninstall unconfigured packages
  uninstall PKG...          delete a package and every link into it
  info PKG                  the resolved package definition
  report                    the reports of the last run of each command
  doctor                    check the environment, configuration and store

After a package that downloaded something, install and update wait :delay seconds, or as long
as an upstream host's rate limit asks if that is longer; --delay N overrides :delay.
gc and uninstall accept --dry-run. uninstall refuses configured packages unless --force,
since the next update would install them again.
install and update refuse downloads they cannot verify unless --no-verify is given; with
--locked they install the versions in :lockfile and check each download against its hash.
Every failure is explained with what to do; --debug adds each request and stack traces.")

(def ^:private dispatch
  {"list" commands/list-installed
   "install" commands/install
   "use" commands/use-version
   "update" commands/update
   "outdated" commands/outdated
   "check" commands/check
   "gc" commands/gc
   "uninstall" commands/uninstall
   "info" commands/info
   "report" commands/show-report
   "doctor" commands/doctor})

(defn network
  "The network operations for `config`: HTTP, the GitHub token from the
  environment or :token-command, and the waits upstream hosts announced."
  [config]
  (assoc http/io :github-token (token/provider (:token-command config))))

(defn context
  "Everything a command needs, from the parsed options."
  [opts]
  (let [config-path (or (:config opts) (config/default-path))
        config (config/load-config config-path)]
    {:config-path config-path
     :config config
     :opts opts
     :io (network config)
     :reporter (fn [_] (progress/reporter))
     :sleep-second! #(Thread/sleep 1000)}))

(defn- with-registry
  "`ctx` with the registry loaded. doctor runs without one when it fails to
  load, since diagnosing that is its job."
  [ctx command]
  (let [load #(registry/load-registry (get-in ctx [:config :registries]))]
    (assoc ctx :registry (if (= "doctor" command)
                           (try (load) (catch Exception _ nil))
                           (load)))))

(def ^:private changes-store
  #{"install" "use" "update" "gc" "uninstall"})

(defn- record-lock!
  "Rewrites the lock file, when one is configured, after a command changed the
  store. A failure to write it is reported but does not fail the command."
  [{:keys [config]}]
  (when-let [path (:lockfile config)]
    (try (lockfile/write! path config)
         (catch Exception e
           (binding [*out* *err*]
             (println (str "[WARN] could not update the lock file " path ": " (ex-message e))))))))

(defn- locked
  "Runs `f` under the store lock when `command` changes the store, and records
  the lock file afterwards."
  [ctx command f]
  (if (changes-store command)
    (store/with-store-lock (:config ctx)
      (fn [pid]
        (binding [*out* *err*]
          (println (str "waiting for another breto run (process " pid ") to finish with the store"))))
      #(let [code (f)]
         (record-lock! ctx)
         code))
    (f)))

(def parse-spec
  {:coerce {:keep :long :delay :long :debug :boolean :verify :boolean :force :boolean :dry-run :boolean :all :boolean :previous :boolean :locked :boolean}
   :alias {:d :debug}})

(defn run
  "Runs the command line and returns the process exit code. `overrides` replace
  parts of the context, which is how tests swap the network and the display."
  ([args] (run args {}))
  ([args overrides]
   (let [{:keys [args opts]} (cli/parse-args args parse-spec)
         [command & more] args]
     (binding [log/*debug* (boolean (:debug opts))]
       (if-let [f (dispatch command)]
         (let [ctx (with-registry (merge (context opts) overrides) command)]
           (locked ctx command #(f ctx more)))
         (do (println usage) (if (#{nil "help"} command) 0 2)))))))

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
