;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.package
  (:require [breto.template :as template]
            [breto.version :as version]
            [clojure.string :as str]))

(def ^:private archive-suffixes
  [[".tar.gz" :tar] [".tgz" :tar] [".tar.xz" :tar] [".txz" :tar]
   [".tar.bz2" :tar] [".tbz2" :tar] [".tar" :tar] [".zip" :zip]])

(defn archive-type
  "The unpack method for a downloaded file, judged by its name. Anything that is
  not a known archive is the binary itself."
  [file-name]
  (or (some (fn [[suffix type]] (when (str/ends-with? file-name suffix) type))
            archive-suffixes)
      :raw))

(def ^:private source-kinds #{:github :gitlab :text})

(defn source-kind [{:keys [source]}]
  (let [kinds (filter source-kinds (keys source))]
    (when (= 1 (count kinds))
      (first kinds))))

(defn rolling?
  "A rolling release lives under one fixed tag, such as `nightly`, and carries
  each build as a separate asset with the version in its name."
  [{:keys [source]}]
  (some? (:tag source)))

(defn repo [{:keys [source]}]
  (or (:github source) (:gitlab source)))

(defn- problems [{:keys [name asset url layout bins source] :as pkg}]
  (cond-> []
    (not (string? name))
    (conj ":name must be a string")

    (nil? (source-kind pkg))
    (conj (str ":source must have exactly one of " (str/join ", " (sort source-kinds))))

    (and (:tag source) (not= :github (source-kind pkg)))
    (conj ":source :tag is only supported for :github")

    (and (rolling? pkg) (not (str/includes? (str asset) "{version}")))
    (conj ":asset of a rolling release must contain {version}")

    (not (string? asset))
    (conj ":asset must be a template string")

    (and (not= :github (source-kind pkg)) (not (string? url)))
    (conj ":url is required unless the source is :github")

    (not (#{:binary :tree} layout))
    (conj ":layout must be :binary or :tree")

    (not (map? bins))
    (conj ":bins must map link names to paths")

    (and (= :binary layout) (empty? bins))
    (conj ":bins must name at least one binary for the :binary layout")))

(defn- complete
  "Fills in defaults and validates one flat definition."
  [pkg]
  (let [pkg (merge {:tag-prefix "v" :strip 0 :layout :binary :links {}} pkg)
        pkg (cond-> pkg
              (and (nil? (:url pkg)) (= :github (source-kind pkg)))
              (assoc :url (str "https://github.com/" (get-in pkg [:source :github])
                               "/releases/download/{tag}/{asset}"))

              (nil? (:bins pkg))
              (assoc :bins {(:name pkg) (if (= :raw (archive-type (str (:asset pkg))))
                                          "{asset}"
                                          (:name pkg))}))]
    (when-let [ps (seq (problems pkg))]
      (throw (ex-info (str "invalid package " (pr-str (:name pkg)) ": " (str/join "; " ps))
                      {:breto/problem :invalid-package :package (:name pkg) :problems (vec ps)})))
    pkg))

(defn normalize
  "Validates a definition and resolves its `:history`. The top level describes
  current releases; each history entry overrides keys for every version older
  than its `:before`, on top of the entries with later boundaries. Each entry is
  stored as the complete definition that applies below its boundary."
  [{:keys [history] :as definition}]
  (when-let [bad (seq (remove #(string? (:before %)) history))]
    (throw (ex-info (str "invalid package " (pr-str (:name definition))
                         ": every :history entry needs a :before version")
                    {:breto/problem :invalid-package :package (:name definition)
                     :problems ["every :history entry needs a :before version"]})))
  (let [base (dissoc definition :history)
        entries (sort #(version/compare-versions (:before %2) (:before %1)) history)
        variants (rest (reductions (fn [acc entry] (merge acc (dissoc entry :before))) base entries))]
    (assoc (complete base)
           :history (mapv (fn [entry variant] {:before (:before entry) :package (complete variant)})
                          entries variants))))

(defn at
  "The definition that applies to `version`; nil means the current releases."
  [pkg version]
  (or (when version
        (->> (:history pkg)
             (filter #(version/older? version (:before %)))
             last
             :package))
      pkg))

(defn version-of-tag [{:keys [tag-prefix name]} tag]
  (if (str/starts-with? tag tag-prefix)
    (subs tag (count tag-prefix))
    (throw (ex-info (str name ": tag " tag " does not start with " (pr-str tag-prefix))
                    {:breto/problem :tag-prefix :package name :tag tag :tag-prefix tag-prefix}))))

(defn tag-of-version [{:keys [tag-prefix source] :as pkg} version]
  (if (rolling? pkg)
    (:tag source)
    (str tag-prefix version)))

(defn- sibling-url
  "Resolves a checksum file name next to the asset URL; full URLs pass through."
  [asset-url checksum]
  (if (str/includes? checksum "://")
    checksum
    (str (subs asset-url 0 (inc (str/last-index-of asset-url "/"))) checksum)))

(defn template-vars [{:keys [name] :as pkg} {:keys [tag version]}]
  {:name name :tag (or tag "") :version (or version "") :repo (or (repo pkg) "")})

(defn artifact
  "Everything needed to fetch and unpack one release of `pkg`."
  [{:keys [asset url checksum bins] :as pkg} release]
  (let [vars (template-vars pkg release)
        asset-name (template/render asset vars)
        asset-url (template/render url (assoc vars
                                              :asset asset-name
                                              :tag (str/replace (:tag vars) "/" "%2F")))
        file-vars (assoc vars :asset asset-name)]
    {:asset asset-name
     :url asset-url
     :checksum-url (some->> checksum (#(template/render % file-vars)) (sibling-url asset-url))
     :archive (archive-type asset-name)
     :bins (update-vals bins #(template/render % file-vars))}))

(defn file-name [path]
  (subs path (inc (or (str/last-index-of path "/") -1))))

(defn- stored-name
  "The command name a binary is kept under: the command named like the file
  itself when there is one, else the first of its commands by name."
  [bins path]
  (let [links (sort (keep (fn [[link p]] (when (= p path) link)) bins))
        own (file-name path)]
    (if (some #{own} links) own (first links))))

(defn stored-files
  "Path inside the unpacked archive to the name the binary layout keeps it
  under. A package with a single binary keeps it under the package name;
  several binaries are each kept once under one of their command names."
  [{:keys [name]} {:keys [bins]}]
  (let [paths (distinct (vals bins))]
    (if (= 1 (count paths))
      {(first paths) name}
      (into {} (map (fn [path] [path (stored-name bins path)])) paths))))

(defn bin-paths
  "Command name to path inside an installed version directory. The binary
  layout keeps each binary under a command name; the tree layout keeps the
  whole archive."
  [{:keys [layout] :as pkg} {:keys [bins] :as artifact}]
  (if (= :binary layout)
    (update-vals bins (stored-files pkg artifact))
    bins))
