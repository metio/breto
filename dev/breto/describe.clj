;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.describe
  "Adds the :description of the aqua registry's package to every definition
  that has none and fetches from a repository aqua knows."
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- aqua-packages
  "Every package entry of the aqua registry checkout `dir`."
  [dir]
  (for [file (fs/glob (fs/path dir "pkgs") "**registry.yaml")
        pkg (:packages (yaml/parse-string (slurp (str file))))
        :when (and (:repo_owner pkg) (:repo_name pkg) (not (str/blank? (:description pkg))))]
    pkg))

(defn- descriptions
  "Repository -> package name -> description, all lower-case keys."
  [dir]
  (reduce (fn [acc {:keys [repo_owner repo_name name description]}]
            (let [repo (str/lower-case (str repo_owner "/" repo_name))
                  short (str/lower-case (last (str/split (or name repo) #"/")))]
              (update acc repo #(merge {short (str/trim description)} %))))
          {}
          (aqua-packages dir)))

(defn- repository [{:keys [source]}]
  (some-> (or (:github source) (:gitlab source)) str/lower-case))

(defn- describe
  "The description for the definition `name` from the entries of its
  repository: the entry of the same name, else the only one, else the one
  named like the repository."
  [entries name repo]
  (or (get entries (str/lower-case name))
      (when (= 1 (count entries)) (val (first entries)))
      (get entries (last (str/split repo #"/")))))

(defn -main [aqua-dir packages-dir]
  (let [known (descriptions aqua-dir)
        missing (atom [])]
    (doseq [file (sort (fs/glob packages-dir "*.edn"))
            :let [name (str (fs/strip-ext (fs/file-name file)))
                  text (slurp (str file))
                  pkg (edn/read-string text)]
            :when (and (map? pkg) (:source pkg) (not (:description pkg)))
            :let [repo (repository pkg)
                  description (some-> (get known repo) (describe name repo))]]
      (if description
        (spit (str file) (str/replace-first text #"(?m)^\{" (str/re-quote-replacement (str "{:description " (pr-str description) ",\n "))))
        (swap! missing conj name)))
    (println (count @missing) "definitions without a description:" (str/join " " @missing))))
