;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.main
  (:require [babashka.cli :as cli]
            [breto.batch :as batch]
            [breto.commands :as commands]
            [breto.config :as config]
            [breto.diagnose :as diagnose]
            [breto.http :as http]
            [breto.lockfile :as lockfile]
            [breto.log :as log]
            [breto.proc :as proc]
            [breto.progress :as progress]
            [breto.registry :as registry]
            [breto.report :as report]
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
  sync [--dry-run]          install and activate exactly the versions in :lockfile, checked against
                            their hashes, and uninstall the packages it does not list
  lock DIR [--check]        write DIR/breto.lock with the hash of every version a group in the
                            registry directory DIR pins; --check fails when it is out of date
  install                   choose packages to install in fzf or sk, else in a numbered prompt;
                            --query TERMS starts with a search
  search [TERM...]          the definitions whose name, description or source has every term
  env [--shell fish]        link the versions .breto.edn lists for this project and print the
                            shell code putting them first on PATH: eval \"$(breto env)\";
                            --no-install fails instead of installing a missing version;
                            --no-wait fails instead of waiting when it must install while
                            another run changes the store
  info PKG                  the resolved package definition
  report [COMMAND...]       the reports of the last run of each command;
                            --brief prints the latest in a few lines, for notifications
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
   "doctor" commands/doctor
   "search" commands/search
   "env" commands/env
   "sync" commands/sync-store
   "lock" commands/lock})

(defn network
  "The network operations for `config`: HTTP, the GitHub token from the
  environment or :token-command, and the waits upstream hosts announced."
  [config]
  (assoc http/io
         :github-token (token/provider (:token-command config))
         :run proc/run-command
         :run-hook proc/run-bounded
         :pick proc/pick
         :ask proc/ask
         :interactive? (some? (System/console))))

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

(defn- configured
  "`config` with :packages as package names, the groups expanded, and the
  versions they pin merged under :pins, where the config's own pins win."
  [config groups]
  (let [specs (map batch/split-spec (registry/expand groups (:packages config)))]
    (assoc config
           :packages (vec (distinct (map first specs)))
           :pins (merge (into {} (filter second) specs) (:pins config)))))

(defn- with-registry
  "`ctx` with the registry and groups loaded and the groups in :packages
  expanded into their members. doctor runs without one when it fails to
  load, since diagnosing that is its job, and report needs none, so a broken
  definition still leaves the last run readable."
  [ctx command]
  (let [dirs (get-in ctx [:config :registries])
        load (fn [ctx]
               (let [groups (registry/load-groups dirs)]
                 (-> ctx
                     (assoc :registry (registry/load-registry dirs) :groups groups
                            :locks (registry/load-locks dirs lockfile/read-lock))
                     (update :config configured groups))))]
    (case command
      "report" ctx
      "doctor" (try (load ctx) (catch Exception e (assoc ctx :registry nil :load-error (diagnose/diagnose e {}))))
      (load ctx))))

(def ^:private changes-store
  #{"install" "use" "update" "gc" "uninstall" "sync" "lock"})

(defn- record-lock!
  "Rewrites the lock file, when one is configured, after a command changed the
  store. A dry run changed nothing, and rewriting the file from the store
  would drop the versions sync was asked to install. A failure to write it is
  reported but does not fail the command."
  [{:keys [config opts] :as ctx} command]
  (when-let [path (when-not (:dry-run opts) (:lockfile config))]
    (try (when (lockfile/write! path config)
           (commands/locked! ctx command path))
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
         (record-lock! ctx command)
         code))
    (f)))

(def parse-spec
  {:coerce {:keep :long :delay :long :debug :boolean :verify :boolean :force :boolean :dry-run :boolean :all :boolean :previous :boolean :locked :boolean :brief :boolean :wait :boolean :install :boolean :check :boolean}
   :alias {:d :debug}})

(def ^:private reported
  "Commands whose last run breto report shows."
  #{"install" "update" "outdated" "check" "gc" "uninstall" "sync"})

(defn- recording-aborts
  "Runs `f`, saving its error as the report of `command` when it stops before
  reporting on any package, so breto report never shows an older run as the
  latest one."
  [command f]
  (try (f)
       (catch Exception e
         (when (reported command)
           (try (report/save! {:command command :results [] :error (diagnose/diagnose e {})})
                (catch Exception _)))
         (throw e))))

(defn run
  "Runs the command line and returns the process exit code. `overrides` replace
  parts of the context, which is how tests swap the network and the display."
  ([args] (run args {}))
  ([args overrides]
   (let [{:keys [args opts]} (cli/parse-args args parse-spec)
         [command & more] args]
     (binding [log/*debug* (boolean (:debug opts))]
       (if-let [f (dispatch command)]
         (recording-aborts command
           #(let [ctx (with-registry (merge (context opts) overrides) command)
                  chosen? (and (= "install" command) (empty? more))
                  args (if chosen? (commands/install-args ctx more) more)]
              (if (and chosen? (empty? args))
                (do (println "Nothing chosen.") 0)
                (locked (assoc ctx :chosen? chosen?) command (fn [] (f (assoc ctx :chosen? chosen?) args))))))
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
