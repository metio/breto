;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.config
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- env-dir [variable fallback]
  (let [value (System/getenv variable)]
    (if (str/blank? value)
      (str (fs/path (System/getProperty "user.home") fallback))
      value)))

(defn default-path []
  (str (fs/path (env-dir "XDG_CONFIG_HOME" ".config") "breto" "config.edn")))

(defn defaults []
  {:store (str (fs/path (env-dir "XDG_DATA_HOME" ".local/share") "breto" "installations"))
   :bin-dir (str (fs/path (System/getProperty "user.home") ".local" "bin"))
   :registries []
   :packages []
   :pins {}
   :keep 3
   :prune false
   :token-command nil
   :lockfile nil})

(defn- expand-home [s]
  (str/replace-first s #"^~(?=/|$)" (System/getProperty "user.home")))

(defn load-config
  "Reads the EDN config at `path` over the defaults. A missing file means all
  defaults."
  [path]
  (let [user (when (fs/exists? path) (edn/read-string (slurp (str path))))]
    (-> (merge (defaults) user)
        (update :store expand-home)
        (update :bin-dir expand-home)
        (update :lockfile #(some-> % expand-home))
        (update :registries #(mapv expand-home %)))))
