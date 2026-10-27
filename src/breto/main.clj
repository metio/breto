;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.main
  (:require [babashka.cli :as cli]
            [breto.config :as config]
            [breto.http :as http]
            [breto.install :as install]
            [breto.registry :as registry]
            [breto.source :as source]
            [breto.store :as store]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def ^:private usage
  "Usage: breto <command> [args] [--config FILE]

Commands:
  list                      installed packages and versions (* marks the active one)
  install PKG[@VERSION]...  install and activate, latest unless a version is given
  use PKG VERSION           activate an installed version
  update [PKG...]           install the latest (or pinned) version of the configured packages
                            install and update refuse unverifiable downloads unless --no-verify
  outdated [PKG...]         configured packages with a newer release
  gc [PKG...] [--keep N]    delete all but the N most recent versions, never the active one
  info PKG                  the resolved package definition")

(defn- bin-links [pkg]
  (keys (:bins pkg)))

(defn- current [cfg reg name]
  (store/current-version cfg name (some-> (get reg name) bin-links)))

(defn- split-spec [spec]
  (let [[name version] (str/split spec #"@" 2)]
    [name version]))

(defn- targets [cfg args]
  (if (seq args) args (:packages cfg)))

(defn- each
  "Runs `f` for every name, reporting failures without stopping. Returns the
  number of failures."
  [names f]
  (count (remove true?
                 (for [name names]
                   (try (f name) true
                        (catch Exception e
                          (binding [*out* *err*]
                            (println (str "[ERROR] " name ": " (ex-message e))))
                          false))))))

(defn- cmd-list [cfg reg _]
  (doseq [name (store/installed-packages cfg)]
    (let [active (current cfg reg name)
          versions (map (fn [{:keys [version]}] (str version (when (= version active) "*")))
                        (store/versions cfg name))]
      (println (str name "\t" (str/join " " versions))))))

(defn- cmd-install [cfg reg args opts]
  (each args
        (fn [spec]
          (let [[name version] (split-spec spec)
                result (install/install! cfg http/io (registry/lookup reg name)
                                         {:version version :verify? (:verify opts true)})]
            (println (str "[OK] " name " " (:version result)
                          (if (:installed? result) " installed" " already installed")))))))

(defn- cmd-use [cfg reg [name version]]
  (when-not (and name version)
    (throw (ex-info "use needs a package and a version" {})))
  (install/use! cfg (registry/lookup reg name) version)
  (println (str "[OK] " name " " version " active"))
  0)

(defn- cmd-update [cfg reg args opts]
  (each (targets cfg args)
        (fn [name]
          (let [pin (get-in cfg [:pins name])
                result (install/install! cfg http/io (registry/lookup reg name)
                                         {:version pin :verify? (:verify opts true)})]
            (when (:installed? result)
              (println (str "[OK] " name " " (:version result) " installed")))))))

(defn- cmd-outdated [cfg reg args]
  (each (targets cfg args)
        (fn [name]
          (let [active (current cfg reg name)
                latest (:version (source/latest (registry/lookup reg name) http/io))]
            (when (not= latest active)
              (println (str name "\t" (or active "-") " -> " latest)))))))

(defn- cmd-gc [cfg reg args opts]
  (let [keep (or (:keep opts) (:keep cfg))]
    (each (if (seq args) args (store/installed-packages cfg))
          (fn [name]
            (doseq [version (store/gc-candidates (store/versions cfg name) (current cfg reg name) keep)]
              (store/remove-version! cfg name version)
              (println (str "[OK] removed " name " " version)))))))

(defn- cmd-info [_ reg [name]]
  (pprint/pprint (registry/lookup reg name))
  0)

(defn run
  "Runs the command line and returns the process exit code."
  [args]
  (let [{:keys [args opts]} (cli/parse-args args {:coerce {:keep :long}})
        [command & more] args
        cfg (config/load-config (or (:config opts) (config/default-path)))
        reg (registry/load-registry (:registries cfg))]
    (case command
      "list" (do (cmd-list cfg reg more) 0)
      "install" (cmd-install cfg reg more opts)
      "use" (cmd-use cfg reg more)
      "update" (cmd-update cfg reg more opts)
      "outdated" (cmd-outdated cfg reg more)
      "gc" (cmd-gc cfg reg more opts)
      "info" (cmd-info cfg reg more)
      (do (println usage) (if (#{nil "help"} command) 0 2)))))

(defn -main [& args]
  (let [code (try (run args)
                  (catch clojure.lang.ExceptionInfo e
                    (binding [*out* *err*] (println (str "[ERROR] " (ex-message e))))
                    1))]
    (System/exit (if (pos? code) 1 0))))
