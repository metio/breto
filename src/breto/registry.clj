;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.registry
  "Package definitions, one EDN file per package, and groups of packages, one
  @NAME.edn file per group, both read from the :registries directories."
  (:require [babashka.fs :as fs]
            [breto.package :as package]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn group-name?
  "Whether `s` names a group, as @NAME."
  [s]
  (str/starts-with? s "@"))

(defn- read-file [file]
  (let [name (str (fs/strip-ext (fs/file-name file)))]
    (try (assoc (edn/read-string (slurp (str file))) :name name ::files [(str file)])
         (catch RuntimeException e
           (throw (ex-info (str "invalid package " (pr-str name) ": " file " is not valid EDN: " (ex-message e))
                           {:breto/problem :invalid-package :package name :files [(str file)]
                            :problems [(str "not valid EDN: " (ex-message e))]}))))))

(defn- read-dir [dir]
  (->> (sort (fs/glob dir "*.edn"))
       (remove #(group-name? (str (fs/file-name %))))
       (map read-file)))

(defn- normalize
  "The validated definition, or an error naming the files it came from."
  [{files ::files :as pkg}]
  (try (package/normalize (dissoc pkg ::files))
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
    :else (-> (merge earlier pkg) (assoc ::files (into (vec (::files earlier)) (::files pkg))))))

(defn load-registry
  "Package definitions from `dirs`, keyed by name. A definition in a later
  directory is merged over the one before it, so an overlay can change a single
  key of an upstream package, or replaces it with :replace true."
  [dirs]
  (let [merged (reduce (fn [acc pkg] (update acc (:name pkg) layer pkg))
                       {}
                       (mapcat read-dir (filter fs/directory? dirs)))]
    (update-vals merged normalize)))

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
  "Groups from `dirs`, keyed by @NAME. A group in a later directory is merged
  over the one before it, so an overlay can replace its :members."
  [dirs]
  (let [files (->> (filter fs/directory? dirs)
                   (mapcat #(sort (fs/glob % "@*.edn"))))]
    (->> files
         (reduce (fn [acc file]
                   (let [name (str (fs/strip-ext (fs/file-name file)))
                         group (try (edn/read-string (slurp (str file)))
                                    (catch RuntimeException e
                                      (throw (ex-info (str "invalid group " name ": " file " is not valid EDN: " (ex-message e))
                                                      {:breto/problem :invalid-package :package name :files [(str file)]
                                                       :problems [(str "not valid EDN: " (ex-message e))]}))))]
                     (update acc name #(-> (merge % (if (map? group) group {:members group}))
                                           (update :files (fnil conj []) (str file))))))
                 {})
         (into {} (map (fn [[name group]]
                         (when-let [problems (seq (group-problems group))]
                           (throw (ex-info (str "invalid group " name ": " (str/join "; " problems))
                                           {:breto/problem :invalid-package :package name
                                            :files (:files group) :problems (vec problems)})))
                         [name group]))))))

(defn expand
  "`names` with every @group replaced by its members, recursively, keeping the
  first occurrence of each package."
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
    (vec (distinct (mapcat #(walk [] %) names)))))

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
