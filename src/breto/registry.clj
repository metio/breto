;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.registry
  "Package definitions, one EDN file per package, and groups of packages, one
  @NAME.edn file per group, both read from the :registries directories. A
  breto binary carries the definitions of its release, which come before
  every directory."
  (:require [babashka.fs :as fs]
            [breto.package :as package]
            [breto.platform :as platform]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn group-name?
  "Whether `s` names a group, as @NAME."
  [s]
  (str/starts-with? s "@"))

(def ^:private builtin-dir "breto/registry/")

(defn builtin
  "The files built into a breto binary, file name -> text, or nil when breto
  runs from a checkout."
  []
  (when-let [index (io/resource (str builtin-dir "index.edn"))]
    (into (sorted-map)
          (for [file (edn/read-string (slurp index))]
            [file (slurp (io/resource (str builtin-dir file)))]))))

(defn- entries
  "[label file-name text] of every .edn file whose name `keep?` accepts:
  those of `builtin` first, then those of `dirs` in order."
  [builtin dirs keep?]
  (concat (for [[file text] builtin :when (keep? file)]
            [(str "breto:" file) file text])
          (for [dir (filter fs/directory? dirs)
                file (sort (fs/glob dir "*.edn"))
                :let [file-name (str (fs/file-name file))]
                :when (keep? file-name)]
            [(str file) file-name (slurp (str file))])))

(defn- read-file [[label file-name text]]
  (let [name (str (fs/strip-ext file-name))]
    (try (assoc (edn/read-string text) :name name ::files [label])
         (catch RuntimeException e
           (throw (ex-info (str "invalid package " (pr-str name) ": " label " is not valid EDN: " (ex-message e))
                           {:breto/problem :invalid-package :package name :files [label]
                            :problems [(str "not valid EDN: " (ex-message e))]}))))))

(defn- normalize
  "The validated definition for `platform`, or an error naming the files it
  came from."
  [platform {files ::files :as pkg}]
  (try (package/normalize (dissoc pkg ::files) platform)
       (catch clojure.lang.ExceptionInfo e
         (throw (ex-info (ex-message e) (assoc (ex-data e) :files files) e)))))

(defn- origin
  "Where a definition's releases come from: its :source without the keys that
  only refine the lookup."
  [{:keys [source]}]
  (not-empty (select-keys source package/source-kinds)))

(defn- layer
  "`pkg` merged over the definition `earlier` of the same name. A definition
  with :replace true stands alone. One whose :source names another project
  is a different tool under the same name, and merging the two would mix
  their keys, so it is refused."
  [earlier pkg]
  (cond
    (nil? earlier) (dissoc pkg :replace)
    (:replace pkg) (dissoc pkg :replace)
    (and (origin pkg) (not= (origin earlier) (origin pkg)))
    (let [files (into (vec (::files earlier)) (::files pkg))]
      (throw (ex-info (str "conflicting package " (pr-str (:name pkg)) ": " (last files)
                           " has a different :source than " (str/join ", " (butlast files)))
                      {:breto/problem :invalid-package :package (:name pkg) :files files
                       :problems [(str (last files) " defines " (pr-str (origin pkg)) " while "
                                       (str/join ", " (butlast files)) " define " (pr-str (origin earlier))
                                       "; rename one of the files, or set :replace true in the later one"
                                       " to replace the earlier definition instead of merging over it")]})))
    :else (cond-> (-> (merge earlier pkg) (assoc ::files (into (vec (::files earlier)) (::files pkg))))
            (and (map? (:platforms earlier)) (map? (:platforms pkg)))
            (assoc :platforms (merge-with merge (:platforms earlier) (:platforms pkg))))))

(defn load-registry
  "Package definitions from `builtin`, the files built into breto, and `dirs`,
  keyed by name. A definition in a later directory is merged over the one
  before it, so an overlay can change a single key of an upstream package, or
  replaces it with :replace true. An overlay's :platforms entries are merged
  key by key into those of the definition it is merged over. Every definition is resolved
  for `platform`."
  [builtin dirs platform]
  (let [merged (reduce (fn [acc pkg] (update acc (:name pkg) layer pkg))
                       {}
                       (map read-file (entries builtin dirs (complement group-name?))))]
    (update-vals merged #(normalize platform %))))

(defn lookup [registry name]
  (or (get registry name)
      (throw (ex-info (str "unknown package " name)
                      {:breto/problem :unknown-package :package name :known (sort (keys registry))}))))

(defn- group-problems [{:keys [members]}]
  (cond-> []
    (not (and (vector? members) (seq members)))
    (conj ":members must be a non-empty vector of package or group names")

    (and (vector? members) (not (every? #(and (string? %) (not (str/blank? %))) members)))
    (conj ":members must hold names as strings")))

(defn load-groups
  "Groups from `builtin`, the files built into breto, and `dirs`, keyed by
  @NAME. A group in a later directory is merged over the one before it, so an
  overlay can replace its :members."
  ([dirs] (load-groups nil dirs))
  ([builtin dirs]
   (->> (entries builtin dirs group-name?)
         (reduce (fn [acc [label file-name text]]
                   (let [name (str (fs/strip-ext file-name))
                         group (try (edn/read-string text)
                                    (catch RuntimeException e
                                      (throw (ex-info (str "invalid group " name ": " label " is not valid EDN: " (ex-message e))
                                                      {:breto/problem :invalid-package :package name :files [label]
                                                       :problems [(str "not valid EDN: " (ex-message e))]}))))]
                     (update acc name #(-> (merge % (if (map? group) group {:members group}))
                                           (update :files (fnil conj []) label)))))
                 {})
         (into {} (map (fn [[name group]]
                         (when-let [problems (seq (group-problems group))]
                           (throw (ex-info (str "invalid group " name ": " (str/join "; " problems))
                                           {:breto/problem :invalid-package :package name
                                            :files (:files group) :problems (vec problems)})))
                         [name group]))))))

(defn- package-of
  "The package a spec names: PKG of PKG@VERSION."
  [spec]
  (first (str/split spec #"@(?=[^@]*$)" 2)))

(defn expand
  "`names` with every @group replaced by its members, recursively, keeping the
  first occurrence of each package. A package `names` lists itself takes the
  place of every group member of the same package, so its version wins over
  the one a group pins."
  [groups names]
  (letfn [(walk [path name]
            (if (group-name? name)
              (let [group (or (get groups name)
                              (throw (ex-info (str "unknown group " name)
                                              {:breto/problem :unknown-group :group name
                                               :known (sort (keys groups))})))]
                (when (some #{name} path)
                  (throw (ex-info (str "the group " name " contains itself: " (str/join " -> " (conj path name)))
                                  {:breto/problem :invalid-package :package name :files (:files group)
                                   :problems [(str "it contains itself through " (str/join " -> " (conj path name)))]})))
                (mapcat #(walk (conj path name) %) (:members group)))
              [name]))]
    (let [direct (into {} (for [n names :when (not (group-name? n))] [(package-of n) n]))]
      (vec (distinct (map #(get direct (package-of %) %) (mapcat #(walk [] %) names)))))))

(defn reached-groups
  "The groups `names` uses, directly or through other groups, in the order
  they are reached, each once."
  [groups names]
  (letfn [(walk [seen name]
            (if (and (group-name? name) (not (some #{name} seen)))
              (reduce walk (conj seen name) (get-in groups [name :members]))
              seen))]
    (reduce walk [] names)))

(defn group-files
  "The files defining the groups `names` uses, directly or through other
  groups, in the order they are reached."
  [groups names]
  (letfn [(walk [seen name]
            (if (and (group-name? name) (not (seen name)))
              (let [group (get groups name)]
                (reduce walk (conj seen name) (:members group)))
              seen))]
    (let [used (reduce walk #{} names)]
      (vec (distinct (mapcat #(get-in groups [% :files]) (filter used (sort (keys groups)))))))))

(def lock-file-name "breto.lock")

(defn load-locks
  "The entries of the breto.lock files of `dirs`, keyed by PKG@VERSION. An
  entry in a later directory replaces one of the same key."
  [dirs read-lock]
  (->> dirs
       (map #(fs/path % lock-file-name))
       (filter fs/regular-file?)
       (map #(:entries (read-lock (str %))))
       (apply merge {})))
