;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.registry
  (:require [babashka.fs :as fs]
            [breto.package :as package]
            [clojure.edn :as edn]))

(defn- read-dir [dir]
  (for [file (sort (fs/glob dir "*.edn"))]
    (let [name (str (fs/strip-ext (fs/file-name file)))]
      (assoc (edn/read-string (slurp (str file))) :name name))))

(defn load-registry
  "Package definitions from `dirs`, keyed by name. A definition in a later
  directory is merged over the one before it, so an overlay can change a single
  key of an upstream package."
  [dirs]
  (let [merged (reduce (fn [acc pkg] (update acc (:name pkg) merge pkg))
                       {}
                       (mapcat read-dir (filter fs/directory? dirs)))]
    (update-vals merged package/normalize)))

(defn lookup [registry name]
  (or (get registry name)
      (throw (ex-info (str "unknown package " name)
                      {:breto/problem :unknown-package :package name :known (sort (keys registry))}))))
