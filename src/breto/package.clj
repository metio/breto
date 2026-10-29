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

(def source-kinds #{:github :gitlab :text :json :go :pypi})

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
  (or (:github source) (:gitlab source) (:go source)))

(defn file-name [path]
  (subs path (inc (or (str/last-index-of path "/") -1))))

(defn go-escape
  "A module path as the Go module proxy spells it: every capital letter as !
  and its lower case."
  [module]
  (str/replace module #"[A-Z]" #(str "!" (str/lower-case %))))

(defn go-package
  "The package `go install` builds for a :go source: its :package, else the
  module itself."
  [{:keys [source]}]
  (or (:package source) (:go source)))

(defn go-binary
  "The name `go install` gives the binary of `package`: its last path
  element, or the one before when that is a major version such as v2."
  [package]
  (let [parts (str/split package #"/")]
    (if (and (next parts) (re-matches #"v\d+" (last parts)))
      (last (butlast parts))
      (last parts))))

(defn- relative-path?
  "Whether `s` is a path or glob inside the release: relative, without .."
  [s]
  (and (string? s) (not (str/blank? s)) (not (str/starts-with? s "/"))
       (not-any? #{".."} (str/split s #"/"))))

(defn- completions-valid? [completions]
  (and (map? completions)
       (every? (fn [[k v]]
                 (case k
                   :command (and (string? v) (re-matches #"[^/.][^/]*" v))
                   (:bash :zsh :fish) (or (relative-path? v)
                                          (and (map? v)
                                               (every? #{:args :env} (keys v))
                                               (every? string? (:args v []))
                                               (map? (:env v {}))
                                               (every? string? (mapcat identity (:env v {})))))
                   false))
               completions)))

(def ^:private reserved-vars #{:name :tag :version :repo :asset :home})

(defn- vars-valid? [vars]
  (and (map? vars)
       (every? (fn [[k v]]
                 (and (keyword? k) (re-matches #"[a-z-]+" (name k)) (not (reserved-vars k))
                      (map? v) (= 1 (count v))
                      (or (and (string? (:match v)) (try (re-pattern (:match v)) (catch Exception _ false)))
                          (and (vector? (:replace v)) (= 2 (count (:replace v))) (every? string? (:replace v))))))
               vars)))

(defn- env-valid? [env]
  (and (map? env)
       (every? (fn [[k v]]
                 (and (string? k) (re-matches #"[A-Za-z_][A-Za-z0-9_]*" k)
                      (not= "PATH" k) (not (str/starts-with? k "BRETO_"))
                      (string? v)))
               env)))

(defn- problems [{:keys [name asset url layout bins source] :as pkg}]
  (cond-> []
    (not (string? name))
    (conj ":name must be a string")

    (nil? (source-kind pkg))
    (conj (str ":source must have exactly one of " (str/join ", " (sort source-kinds))))

    (and (= :json (source-kind pkg))
         (not (and (vector? (:version source)) (seq (:version source))
                   (every? #(or (keyword? %) (string? %) (nat-int? %)) (:version source)))))
    (conj ":source {:json URL} needs :version, a path of keys and indices to the version, e.g. [:releases 0 :version]")

    (and (contains? source :match)
         (not (and (= :text (source-kind pkg)) (string? (:match source))
                   (try (re-pattern (:match source)) (catch Exception _ false)))))
    (conj ":source :match is a regular expression for a :text source, whose first group, or else whole match, is the tag")

    (and (:tag source) (not= :github (source-kind pkg)))
    (conj ":source :tag is only supported for :github")

    (and (= :go (source-kind pkg))
         (not (and (string? (:go source)) (re-matches #"[^\s@]+" (:go source))
                   (or (nil? (:package source))
                       (and (string? (:package source))
                            (or (= (:package source) (:go source))
                                (str/starts-with? (:package source) (str (:go source) "/"))))))))
    (conj ":source {:go MODULE} names a module path, and its :package, when given, a package inside that module")

    (and (= :pypi (source-kind pkg))
         (not (and (string? (:pypi source)) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" (:pypi source)))))
    (conj ":source {:pypi NAME} names a project on PyPI")

    (not (or (nil? (:python pkg)) (and (string? (:python pkg)) (re-matches #"\d+\.\d+" (:python pkg)))))
    (conj ":python is a Python version such as \"3.13\"")

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
    (conj ":bins must name at least one binary for the :binary layout")

    (and (map? bins)
         (not (every? (fn [[link path]]
                        (and (string? link) (re-matches #"[^/.][^/]*" link)
                             (string? path) (not (str/starts-with? path "/"))
                             (not-any? #{".."} (str/split path #"/"))))
                      bins)))
    (conj ":bins must map plain command names to relative paths inside the release, without ..")

    (not (and (sequential? (:skip pkg [])) (every? string? (:skip pkg []))))
    (conj ":skip must be a vector of version strings")

    (not (boolean? (:verify pkg true)))
    (conj ":verify must be true or false")

    (and (contains? pkg :bin-dir)
         (not (and (= :tree layout) (relative-path? (:bin-dir pkg)))))
    (conj ":bin-dir needs the :tree layout and is a relative path inside the release, without ..")

    (not (vars-valid? (:vars pkg {})))
    (conj ":vars maps placeholder keywords other than :name, :tag, :version, :repo, :asset and :home to {:match REGEX} or {:replace [FROM TO]}, applied to the version")

    (not (env-valid? (:env pkg {})))
    (conj ":env maps environment variable names other than PATH and BRETO_* to template strings")

    (not (string? (:description pkg "")))
    (conj ":description must be a string")

    (and (contains? pkg :superseded-by) (not (and (string? (:superseded-by pkg)) (not (str/blank? (:superseded-by pkg))))))
    (conj ":superseded-by must name the package that replaces this one")

    (not (and (sequential? (:man pkg [])) (every? relative-path? (:man pkg []))))
    (conj ":man must be a vector of relative paths or globs inside the release, without ..")

    (not (completions-valid? (:completions pkg {})))
    (conj ":completions maps :bash, :zsh or :fish to a relative path inside the release or to {:args [...] :env {...}}, and :command to a command name")

    (and (:signature pkg)
         (let [{:keys [bundle identity identity-regexp issuer of]} (:signature pkg)]
           (not (and (string? bundle) (string? issuer)
                     (or (string? identity) (string? identity-regexp))
                     (contains? #{nil :asset :checksum} of)
                     (or (not= :checksum of) (:checksum pkg))))))
    (conj ":signature needs :bundle, :issuer and :identity or :identity-regexp; :of is :asset or :checksum, which needs :checksum")))

(defn superseded-warning
  "The warning for a package whose definition names a successor, or nil."
  [{:keys [superseded-by]}]
  (when superseded-by
    (str "superseded by " superseded-by ", which breto install " superseded-by " installs; its commands may have other names")))

(defn- complete
  "Fills in defaults and validates one flat definition."
  [pkg]
  (let [pkg (merge {:tag-prefix "v" :strip 0 :layout :binary :links {}} pkg)
        pkg (cond-> pkg
              (= :go (source-kind pkg))
              (merge {:asset (go-binary (go-package pkg))
                      :url (str "https://proxy.golang.org/" (go-escape (get-in pkg [:source :go])) "/@v/{tag}.zip")})

              (= :pypi (source-kind pkg))
              (merge {:tag-prefix "" :layout :tree
                      :asset "requirements.txt"
                      :url (str "https://pypi.org/project/" (get-in pkg [:source :pypi]) "/{version}/")}
                     (when (nil? (:bins pkg))
                       {:bins {(:name pkg) (str "venv/bin/" (:name pkg))}}))

              (and (nil? (:url pkg)) (= :github (source-kind pkg)))
              (assoc :url (str "https://github.com/" (get-in pkg [:source :github])
                               "/releases/download/{tag}/{asset}"))

              (and (nil? (:bins pkg)) (:bin-dir pkg))
              (assoc :bins {})

              (and (nil? (:bins pkg)) (nil? (:bin-dir pkg)) (not= :pypi (source-kind pkg)))
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

(defn- derived
  "The value of one of `:vars`: the first group of :match in `version`, or
  the whole match without a group, or `version` with :replace applied."
  [pkg k {:keys [match replace]} version]
  (if match
    (let [m (re-find (re-pattern match) version)]
      (cond
        (nil? m) (throw (ex-info (str (:name pkg) ": version " version " does not match the " k " pattern " match)
                                 {:breto/problem :invalid-package :package (:name pkg)
                                  :problems [(str ":vars " k " does not match version " version)]}))
        (string? m) m
        :else (second m)))
    (str/replace version (first replace) (second replace))))

(defn template-vars [{:keys [name vars] :as pkg} {:keys [tag version]}]
  (into {:name name :tag (or tag "") :version (or version "") :repo (or (repo pkg) "")}
        (when (seq version)
          (for [[k spec] vars]
            [k (derived pkg k spec version)]))))

(defn artifact
  "Everything needed to fetch and unpack one release of `pkg`."
  [{:keys [asset url checksum bins signature] :as pkg} release]
  (let [vars (template-vars pkg release)
        asset-name (template/render asset vars)
        asset-url (template/render url (assoc vars
                                              :asset asset-name
                                              :tag (str/replace (:tag vars) "/" "%2F")))
        file-vars (assoc vars :asset asset-name)]
    {:asset asset-name
     :url asset-url
     :checksum-url (some->> checksum (#(template/render % file-vars)) (sibling-url asset-url))
     :signature (when-let [{:keys [bundle] :as sig} signature]
                  (-> sig
                      (assoc :bundle-url (sibling-url asset-url (template/render bundle file-vars)))
                      (update :identity #(some-> % (template/render file-vars)))
                      (update :of #(or % :asset))))
     :archive (archive-type asset-name)
     :bins (update-vals bins #(template/render % file-vars))}))

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
