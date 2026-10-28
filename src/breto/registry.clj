;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.registry
  (:require [babashka.fs :as fs]
            [breto.package :as package]
            [clojure.edn :as edn]))

(defn- read-file [file]
  (let [name (str (fs/strip-ext (fs/file-name file)))]
    (try (assoc (edn/read-string (slurp (str file))) :name name ::files [(str file)])
         (catch RuntimeException e
           (throw (ex-info (str "invalid package " (pr-str name) ": " file " is not valid EDN: " (ex-message e))
                           {:breto/problem :invalid-package :package name :files [(str file)]
                            :problems [(str "not valid EDN: " (ex-message e))]}))))))

(defn- read-dir [dir]
  (map read-file (sort (fs/glob dir "*.edn"))))

(defn- normalize
  "The validated definition, or an error naming the files it came from."
  [{files ::files :as pkg}]
  (try (package/normalize (dissoc pkg ::files))
       (catch clojure.lang.ExceptionInfo e
         (throw (ex-info (ex-message e) (assoc (ex-data e) :files files) e)))))

(defn load-registry
  "Package definitions from `dirs`, keyed by name. A definition in a later
  directory is merged over the one before it, so an overlay can change a single
  key of an upstream package."
  [dirs]
  (let [merged (reduce (fn [acc pkg]
                         (update acc (:name pkg)
                                 #(-> (merge % pkg) (assoc ::files (into (vec (::files %)) (::files pkg))))))
                       {}
                       (mapcat read-dir (filter fs/directory? dirs)))]
    (update-vals merged normalize)))

(defn lookup [registry name]
  (or (get registry name)
      (throw (ex-info (str "unknown package " name)
                      {:breto/problem :unknown-package :package name :known (sort (keys registry))}))))
