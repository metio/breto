;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.main
  (:require [babashka.cli :as cli]
            [clojure.string :as str]
            [breto.batch :as batch]
            [breto.cli :as entry]
            [breto.commands :as commands]
            [breto.config :as config]
            [breto.diagnose :as diagnose]
            [breto.http :as http]
            [breto.lockfile :as lockfile]
            [breto.log :as log]
            [breto.platform :as platform]
            [breto.proc :as proc]
            [breto.progress :as progress]
            [breto.registry :as registry]
            [breto.remote :as remote]
            [breto.report :as report]
            [breto.store :as store]
            [breto.token :as token]
            [clojure.stacktrace :as stacktrace]))

(def ^:private usage
  "Usage: breto <command> [args] [--config FILE] [--platform PLATFORM] [--debug]

Commands:
  list                      installed packages and versions (* marks the active one)
  install PKG[@VERSION]...  install and activate, latest unless a version is given
  use PKG VERSION           activate an installed version
  use PKG --previous        activate the newest installed version older than the active one
  update [PKG...]           install the latest (or pinned) version of the configured packages
  outdated [PKG...]         configured packages with a newer release
  check [PKG...] [--all]    whether the latest release of each configured package (or every
                            package with --all) exists and can be verified, without downloading;
                            --platforms all|PLATFORM,... checks those platforms' assets
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
  hook bash                 print the bash hook that runs breto env on entering a project:
                            eval \"$(breto hook bash)\" in .bashrc
  run [COMMAND [ARG...]]    run a command of the project's :commands with its tools on PATH,
                            after those it :depends on; without one, choose it in fzf or sk
  env [--shell fish|github] link the versions .breto.edn and .breto.local.edn list for this
                            project, with its commands, and print the
                            shell code putting them first on PATH: eval \"$(breto env)\";
                            --shell github prints lines for $GITHUB_ENV instead;
                            --no-install fails with exit code 3 instead of installing a
                            missing version;
                            --no-wait fails instead of waiting when it must install while
                            another run changes the store
  info PKG                  the resolved package definition
  report [COMMAND...]       the reports of the last run of each command;
                            --brief prints the latest in a few lines, for notifications
  doctor                    check the environment, configuration and store

After a package that downloaded something, install, update and sync wait :delay seconds, or as
long as an upstream host's rate limit asks if that is longer; env and run skip :delay, and
--delay N overrides it for every command.
gc and uninstall accept --dry-run. uninstall refuses configured packages unless --force,
since the next update would install them again.
install and update refuse downloads they cannot verify unless --no-verify is given; with
--locked they install the versions in :lockfile and check each download against its hash.
--platform resolves the definitions for linux-x86_64, linux-aarch64, darwin-x86_64 or
darwin-aarch64 instead of this machine's platform, as check and lock need for another one.
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
   "run" commands/run-command
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
        config (cond-> (config/load-config config-path)
                 (:platform opts) (assoc :platform (:platform opts)))]
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

(defn- published
  "The published registries among `entries`, the :registries of the config at
  `config-path`, each checked."
  [config-path entries]
  (let [remotes (filter remote/remote? entries)]
    (doseq [entry remotes
            :let [problems (remote/problems entry)]
            :when (seq problems)]
      (throw (ex-info (str "invalid registry " (pr-str entry) " in " config-path ": " (str/join "; " problems))
                      {:breto/problem :invalid-package :package (pr-str entry) :files [config-path] :problems problems})))
    remotes))

(def ^:private installs-registries
  "Commands that install the published registries before the rest."
  #{"update" "sync"})

(defn- with-registry
  "`ctx` with the registry and groups loaded and the groups in :packages
  expanded into their members, and :reload to load them again. A published
  registry is read from its active release, and its package comes first in
  :packages. One not installed yet is left out with a warning, except under
  update and sync, which install it first; until then their :packages holds
  only what loads. doctor runs without a registry when it fails to load,
  since diagnosing that is its job, and report needs none, so a broken
  definition still leaves the last run readable."
  [ctx command]
  (let [entries (get-in ctx [:config :registries])
        remotes (published (:config-path ctx) entries)
        builtin (into (or (registry/builtin) (sorted-map))
                      (for [entry remotes] [(str (remote/package-name entry) ".edn") (pr-str (remote/definition entry))]))
        load (fn load [ctx]
               (let [config (:config ctx)
                     missing (remove #(remote/dir config %) remotes)
                     dirs (keep #(if (remote/remote? %) (remote/dir config %) %) entries)
                     groups (registry/load-groups builtin dirs)
                     names (map remote/package-name remotes)
                     expand #(cond-> % (seq remotes) (update :packages (fn [packages] (into (vec names) packages))))]
                 (when-not (installs-registries command)
                   (binding [*out* *err*]
                     (doseq [entry missing]
                       (println (str "[WARN] the registry " (:github entry) " is not installed, so its packages and groups are missing; breto update installs it")))))
                 (-> ctx
                     (assoc :registry (registry/load-registry builtin dirs (platform/current config)) :groups groups
                            :locks (registry/load-locks dirs lockfile/read-lock)
                            :published (vec names) :reload #(load ctx))
                     (update :config #(try (configured (expand %) groups)
                                           (catch clojure.lang.ExceptionInfo e
                                             (if (and (seq missing) (installs-registries command))
                                               (configured (assoc % :packages (vec names)) groups)
                                               (throw e))))))))]
    (case command
      "report" ctx
      "doctor" (try (load ctx) (catch Exception e (assoc ctx :registry nil :load-error (diagnose/diagnose e {}))))
      (load ctx))))

(defn- registries-first
  "`ctx` loaded again after update or sync installed the published registries
  first: all of them when no packages are named, else those not installed
  yet. Their results are under :registry-results."
  [{:keys [config published] :as ctx} command args]
  (let [names (if (seq args)
                (remove #(store/current-version config %) published)
                published)]
    (if (and (installs-registries command) (seq names) (not (get-in ctx [:opts :dry-run])))
      (let [results (commands/install-registries ctx command (vec names))]
        (assoc ((:reload ctx)) :registry-results results))
      ctx)))

(def ^:private changes-store
  #{"install" "use" "update" "gc" "uninstall" "sync" "lock"})

(defn- record-lock!
  "Rewrites the lock file, when one is configured, after a command changed the
  store. A dry run changed nothing, and sync follows the lock file, so
  rewriting it from the store would undo the versions sync failed to
  install. A failure to write it is
  reported but does not fail the command."
  [{:keys [config opts registry] :as ctx} command]
  (when-let [path (when-not (or (:dry-run opts) (= "sync" command)) (:lockfile config))]
    (try (when (lockfile/write! path config registry)
           (commands/locked! ctx command path))
         (catch Exception e
           (binding [*out* *err*]
             (println (str "[WARN] could not update the lock file " path ": " (ex-message e))))))))

(defn- locked
  "Runs `f` with `ctx` under the store lock when `command` changes the store,
  after installing the published registries it needs, and records the lock
  file afterwards."
  [ctx command args f]
  (if (changes-store command)
    (store/with-store-lock (:config ctx)
      (fn [pid]
        (binding [*out* *err*]
          (println (str "waiting for another breto run (process " pid ") to finish with the store"))))
      #(let [ctx (registries-first ctx command args)
             code (f ctx)]
         (record-lock! ctx command)
         code))
    (f ctx)))

(def parse-spec
  {:coerce {:keep :long :delay :long :debug :boolean :verify :boolean :force :boolean :dry-run :boolean :all :boolean :previous :boolean :locked :boolean :brief :boolean :wait :boolean :install :boolean :check :boolean :enter :boolean}
   :alias {:d :debug}})

(def ^:private valued
  "The options that take a value, so that the value is not read as the
  command."
  #{"--dir" "--config" "--platform" "--platforms" "--delay" "--keep" "--query" "--shell"})

(defn- parse
  "The command line as {:args :opts}, options read wherever they stand. breto
  run stops reading options at the name of the command it runs, so that
  command gets the rest as given."
  [args]
  (let [options (fn [tokens]
                  (loop [own [] [a & more :as remaining] tokens]
                    (cond (nil? a) [own []]
                          (valued a) (recur (conj own a (first more)) (rest more))
                          (str/starts-with? a "-") (recur (conj own a) more)
                          :else [own remaining])))
        [before [command & after]] (options args)]
    (if (= "run" command)
      (let [[own rest-args] (options after)]
        {:args (into ["run"] rest-args) :opts (:opts (cli/parse-args (into before own) parse-spec))})
      (let [[own positional] (loop [own [] positional [] [a & more :as tokens] args]
                               (cond (empty? tokens) [own positional]
                                     (valued a) (recur (conj own a (first more)) positional (rest more))
                                     (str/starts-with? a "-") (recur (conj own a) positional more)
                                     :else (recur own (conj positional a) more)))]
        {:args positional :opts (:opts (cli/parse-args own parse-spec))}))))

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
   (let [{:keys [args opts]} (parse args)
         [command & more] args]
     (binding [log/*debug* (boolean (:debug opts))]
       (if (= "hook" command)
         (entry/print-hook more)
         (if-let [f (dispatch command)]
           (recording-aborts command
             #(let [ctx (with-registry (merge (context opts) overrides) command)
                    chosen? (and (= "install" command) (empty? more))
                    args (if chosen? (commands/install-args ctx more) more)]
                (if (and chosen? (empty? args))
                  (do (println "Nothing chosen.") 0)
                  (locked (assoc ctx :chosen? chosen?) command args (fn [ctx] (f ctx args))))))
           (do (println usage) (if (#{nil "help"} command) 0 2))))))))

(defn -main [& args]
  (let [code (try (run args)
                  (catch Exception e
                    (let [{:keys [message summary facts advice]} (diagnose/diagnose e {})]
                      (binding [*out* *err*]
                        (println (str "[ERROR] " message))
                        (when-not (= message summary) (println (str "  " summary)))
                        (doseq [f facts] (println (str "  · " f)))
                        (doseq [a advice] (println (str "  - " a)))
                        (when (some #{"--debug" "-d"} args) (stacktrace/print-cause-trace e))))
                    (if (= :env-missing (:breto/problem (ex-data e))) ::missing 1)))]
    (System/exit (cond (= ::missing code) 3 (pos? code) 1 :else 0))))
