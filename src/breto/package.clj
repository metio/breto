;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.package
  (:require [breto.cargo :as cargo]
            [breto.command :as command]
            [breto.coursier :as coursier]
            [breto.document :as document]
            [breto.ghcup :as ghcup]
            [breto.npm :as npm]
            [breto.platform :as platform]
            [breto.template :as template]
            [breto.version :as version]
            [clojure.string :as str]))

(def ^:private archive-suffixes
  [[".tar.gz" :tar] [".tgz" :tar] [".tar.xz" :tar] [".txz" :tar]
   [".tar.bz2" :tar] [".tbz2" :tar] [".tar.zst" :tar] [".tzst" :tar] [".tar" :tar] [".zip" :zip]
   [".crate" :tar] [".gz" :gz] [".bz2" :bz2] [".zst" :zst]])

(defn archive-type
  "The unpack method for a downloaded file, judged by its name. Anything that is
  not a known archive is the binary itself."
  [file-name]
  (or (some (fn [[suffix type]] (when (str/ends-with? file-name suffix) type))
            archive-suffixes)
      :raw))

(defn unpacked-name
  "The name a single compressed file is decompressed under: its own name
  without the compression suffix. Other files keep their name."
  [file-name]
  (if-let [suffix ({:gz ".gz" :bz2 ".bz2" :zst ".zst"} (archive-type file-name))]
    (subs file-name 0 (- (count file-name) (count suffix)))
    file-name))

(def source-kinds #{:github :gitlab :forgejo :gitea :text :json :go :pypi :npm :cargo :ghcup :coursier})

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
  (or (:github source) (:gitlab source) (:forgejo source) (:gitea source) (:go source)))

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

(def ^:private reserved-vars #{:name :tag :version :repo :asset :install-dir :install-dirs :home :homes})

(defn- vars-valid? [vars]
  (and (map? vars)
       (every? (fn [[k v]]
                 (and (keyword? k) (re-matches #"[a-z-]+" (name k)) (not (reserved-vars k))
                      (map? v)
                      (or (and (= 1 (count v)) (string? (:match v)) (try (re-pattern (:match v)) (catch Exception _ false)))
                          (and (= 1 (count v)) (vector? (:replace v)) (= 2 (count (:replace v))) (every? string? (:replace v)))
                          (and (= #{:text :match} (set (keys v))) (string? (:text v)) (string? (:match v))
                               (try (re-pattern (:match v)) (catch Exception _ false)))
                          (and (= #{:json :path} (set (keys v))) (string? (:json v)) (document/path-valid? (:path v))))))
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

    (and (contains? source :newest)
         (not (and (= :text (source-kind pkg)) (:match source) (boolean? (:newest source)))))
    (conj ":source :newest true makes a :text source take the newest of all the tags its :match finds")

    (and (:tag source) (not= :github (source-kind pkg)))
    (conj ":source :tag is only supported for :github")

    (and (= :go (source-kind pkg))
         (not (and (string? (:go source)) (re-matches #"[^\s@]+" (:go source))
                   (or (nil? (:package source))
                       (and (string? (:package source))
                            (or (= (:package source) (:go source))
                                (str/starts-with? (:package source) (str (:go source) "/"))))))))
    (conj ":source {:go MODULE} names a module path, and its :package, when given, a package inside that module")

    (and (= :go (source-kind pkg))
         (contains? source :tags)
         (not (and (vector? (:tags source))
                   (seq (:tags source))
                   (every? #(and (string? %) (re-matches #"[A-Za-z0-9_.]+" %)) (:tags source)))))
    (conj ":source {:go MODULE :tags [...]} lists build tags, as letters, digits, dots and underscores")

    (and (= :pypi (source-kind pkg))
         (not (and (string? (:pypi source)) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" (:pypi source)))))
    (conj ":source {:pypi NAME} names a project on PyPI")

    (and (= :npm (source-kind pkg))
         (not (and (string? (:npm source))
                   (re-matches #"(@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*" (:npm source)))))
    (conj ":source {:npm NAME} names a package on the npm registry, such as prettier or @biomejs/biome")

    (and (= :cargo (source-kind pkg))
         (not (and (string? (:cargo source)) (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]{0,63}" (:cargo source)))))
    (conj ":source {:cargo NAME} names a crate on crates.io")

    (and (contains? source :git)
         (not (and (= :cargo (source-kind pkg)) (string? (:git source))
                   (re-matches #"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+" (:git source)))))
    (conj ":source :git names the GitHub repository OWNER/REPO a :cargo crate is built from")

    (and (= :coursier (source-kind pkg))
         (not (and (string? (:coursier source)) (re-matches #"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+" (:coursier source))
                   (or (nil? (:main source)) (and (string? (:main source)) (re-matches #"[A-Za-z_$][\w$.]*" (:main source))))
                   (or (nil? (:java-opts source)) (and (vector? (:java-opts source)) (every? string? (:java-opts source)))))))
    (conj ":source {:coursier \"GROUP:ARTIFACT\"} names a module on Maven Central, with an optional :main class and :java-opts vector")

    (and (= :ghcup (source-kind pkg)) (not (contains? ghcup/tools (:ghcup source))))
    (conj ":source {:ghcup TOOL} names a tool ghcup installs: cabal, ghc, hls or stack")

    (not (or (nil? (:python pkg)) (and (string? (:python pkg)) (re-matches #"\d+\.\d+" (:python pkg)))))
    (conj ":python is a Python version such as \"3.13\"")

    (and (rolling? pkg) (not (str/includes? (str asset) "{version}")))
    (conj ":asset of a rolling release must contain {version}")

    (and (rolling? pkg) (some #(str/includes? (str asset) (str "{" (clojure.core/name %) "}")) (keys (:vars pkg))))
    (conj ":asset of a rolling release cannot use :vars, which derive from a version it does not know yet")

    (not (string? asset))
    (conj ":asset must be a template string")

    (and (not (#{:github :forgejo :gitea} (source-kind pkg))) (not (string? url)))
    (conj ":url is required unless the source is :github, :forgejo or :gitea")

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
    (conj ":vars maps placeholder keywords other than :name, :tag, :version, :repo, :asset, :install-dir, :install-dirs, :home and :homes to {:match REGEX} or {:replace [FROM TO]}, applied to the version, or {:text URL :match REGEX}, matched in a document, or {:json URL :path [path]}, the value at a path in a JSON or YAML document")

    (not (env-valid? (:env pkg {})))
    (conj ":env maps environment variable names other than PATH and BRETO_* to template strings")

    (not (map? (:commands pkg {})))
    (conj ":commands maps names to commands")

    (map? (:commands pkg {}))
    (into (mapcat (fn [[name definition]] (command/problems name definition)) (:commands pkg)))

    (not (string? (:description pkg "")))
    (conj ":description must be a string")

    (and (contains? pkg :superseded-by) (not (and (string? (:superseded-by pkg)) (not (str/blank? (:superseded-by pkg))))))
    (conj ":superseded-by must name the package that replaces this one")

    (not (and (sequential? (:man pkg [])) (every? relative-path? (:man pkg []))))
    (conj ":man must be a vector of relative paths or globs inside the release, without ..")

    (not (completions-valid? (:completions pkg {})))
    (conj ":completions maps :bash, :zsh or :fish to a relative path inside the release or to {:args [...] :env {...}}, and :command to a command name")

    (not (let [checksum (:checksum pkg)]
           (or (nil? checksum) (string? checksum)
               (and (map? checksum) (= #{:json :hash} (set (keys checksum))) (string? (:json checksum))
                    (document/path-valid? (:hash checksum))))))
    (conj ":checksum is a checksum file name or URL, or {:json URL :hash [path]} with a path of keys, indices and field selectors to the hash")

    (and (:signature pkg)
         (let [{:keys [bundle identity identity-regexp issuer of]} (:signature pkg)]
           (not (and (string? bundle) (string? issuer)
                     (or (string? identity) (string? identity-regexp))
                     (or (contains? #{nil :asset :checksum} of)
                         (and (map? of) (= #{:file} (set (keys of))) (relative-path? (:file of))
                              (not (#{:raw :gz :bz2 :zst} (archive-type (str (:asset pkg)))))))
                     (or (not= :checksum of) (:checksum pkg))))))
    (conj ":signature needs :bundle, :issuer and :identity or :identity-regexp; :of is :asset, :checksum, which needs :checksum, or {:file path} inside an archive asset")))

(defn superseded-warning
  "The warning for a package whose definition names a successor, or nil."
  [{:keys [superseded-by]}]
  (when superseded-by
    (str "superseded by " superseded-by ", which breto install " superseded-by " installs; its commands may have other names")))

(defn- cargo-defaults
  "What a :cargo source fills in: the crate's download, checked against the
  checksum its index lists, and the binary named like the package. A crate
  built from :git downloads the archive of its commit, which nothing upstream
  lists a checksum for, so the commit in its URL is what pins the source and
  a lock file records the archive's hash."
  [pkg]
  (let [crate (get-in pkg [:source :cargo])
        git (get-in pkg [:source :git])]
    (merge (if git
             {:tag-prefix "" :strip 1 :verify false
              :asset (str crate "-{commit}.tar.gz")
              :url (cargo/archive-url git)
              :vars (merge {:commit {:match cargo/commit-match}} (:vars pkg))}
             {:tag-prefix "" :strip 1
              :asset (str crate "-{version}.crate")
              :url (cargo/download-url crate)
              :checksum {:json (cargo/index-url crate) :hash [{"vers" "{version}"} "cksum"]}})
           (when (nil? (:bins pkg))
             {:bins {(:name pkg) (str "bin/" (:name pkg))}}))))

(defn- ghcup-defaults
  "What a :ghcup source fills in: the generic bindist ghcup's metadata
  lists for `platform`, checked against the hash listed
  beside it, and where its commands are once ghcup has installed it."
  [pkg platform]
  (let [tool (get-in pkg [:source :ghcup])
        download (ghcup/download-path tool platform)]
    (merge {:tag-prefix "" :layout :tree
            :asset (str tool "-{version}-bindist")
            :url "{bindist}"
            :checksum {:json ghcup/metadata-url :hash (conj download "dlHash")}
            :vars (merge {:bindist {:json ghcup/metadata-url :path (conj download "dlUri")}} (:vars pkg))}
           (when-not (or (:bins pkg) (:bin-dir pkg))
             (ghcup/layout tool)))))

(defn- complete
  "Fills in defaults for `platform` and validates one flat definition."
  [pkg platform]
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

              (= :npm (source-kind pkg))
              (merge {:tag-prefix "" :layout :tree
                      :asset "package-lock.json"
                      :url (npm/registry-url (get-in pkg [:source :npm]) "/{version}")}
                     (when (nil? (:bins pkg))
                       {:bins {(:name pkg) (str "node_modules/.bin/" (:name pkg))}}))

              (= :cargo (source-kind pkg))
              (merge (cargo-defaults pkg))

              (= :ghcup (source-kind pkg))
              (merge (ghcup-defaults pkg platform))

              (= :coursier (source-kind pkg))
              (merge {:tag-prefix "" :layout :tree
                      :asset "jars.txt"
                      :url (coursier/pom-url (get-in pkg [:source :coursier]))}
                     (when (nil? (:bins pkg))
                       {:bins {(:name pkg) (str "bin/" (:name pkg))}}))

              (and (nil? (:url pkg)) (= :github (source-kind pkg)))
              (assoc :url (str "https://github.com/" (get-in pkg [:source :github])
                               "/releases/download/{tag}/{asset}"))

              (and (nil? (:url pkg)) (#{:forgejo :gitea} (source-kind pkg)))
              (assoc :url (str "https://" (get-in pkg [:source (source-kind pkg)])
                               "/releases/download/{tag}/{asset}"))

              (and (nil? (:bins pkg)) (:bin-dir pkg))
              (assoc :bins {})

              (and (nil? (:bins pkg)) (nil? (:bin-dir pkg)) (not (#{:pypi :npm :cargo :ghcup :coursier} (source-kind pkg))))
              (assoc :bins {(:name pkg) (case (archive-type (str (:asset pkg)))
                                          :raw "{asset}"
                                          (:gz :bz2 :zst) (unpacked-name (str (:asset pkg)))
                                          (:name pkg))}))]
    (when-let [ps (seq (problems pkg))]
      (throw (ex-info (str "invalid package " (pr-str (:name pkg)) ": " (str/join "; " ps))
                      {:breto/problem :invalid-package :package (:name pkg) :problems (vec ps)})))
    pkg))

(def neutral-kinds
  "Source kinds that build or resolve the package on the machine itself, so
  one definition serves every platform."
  #{:go :pypi :npm :cargo :coursier})

(defn- invalid! [definition problem]
  (throw (ex-info (str "invalid package " (pr-str (:name definition)) ": " problem)
                  {:breto/problem :invalid-package :package (:name definition) :problems [problem]})))

(defn- check-platforms! [{:keys [history platforms] :as definition}]
  (when-let [bad (seq (remove #(string? (:before %)) (concat history (mapcat :history (vals platforms)))))]
    (invalid! definition (str "every :history entry needs a :before version, not " (pr-str (first bad)))))
  (when (some? platforms)
    (when-not (and (map? platforms) (every? map? (vals platforms)))
      (invalid! definition ":platforms maps platform names to maps of the keys that differ there"))
    (when-let [unknown (seq (remove platform/known (keys platforms)))]
      (invalid! definition (str ":platforms names " (str/join ", " (map pr-str unknown))
                                ", which is none of " (str/join ", " (sort platform/known)))))
    (when-let [[p _] (first (filter (fn [[_ entry]] (some #{:source :name :platforms} (keys entry))) platforms))]
      (invalid! definition (str ":platforms " (pr-str p) " cannot change :source, :name or :platforms")))))

(defn supported-platforms
  "The platforms `definition` has releases for: every one for a source that
  builds on the machine itself or that ghcup installs, else those its
  :platforms names."
  [{:keys [platforms] :as definition}]
  (let [kind (source-kind definition)]
    (if (or (neutral-kinds kind) (= :ghcup kind))
      (sort platform/known)
      (sort (keys platforms)))))

(defn normalize
  "Validates a definition for `platform` and resolves its `:history`. The top
  level holds what every platform shares, and the :platforms entry of
  `platform` overrides keys on top of it. Each history entry overrides keys
  for every version older than its `:before`, on top of the entries with
  later boundaries; the history of the platform entry applies over that of
  the top level. Each entry is stored as the complete definition that
  applies below its boundary. A definition without releases for `platform`
  is marked :unsupported and keeps only its shared keys."
  [{:keys [history platforms] :as definition} platform]
  (check-platforms! definition)
  (let [supported (supported-platforms definition)
        base (dissoc definition :history :platforms)]
    (when (and (empty? supported) (not (neutral-kinds (source-kind definition))))
      (invalid! definition (str ":platforms names no platform; list each of "
                                (str/join ", " (sort platform/known)) " the package has releases for")))
    (if-not (some #{platform} supported)
      (assoc base :platform platform :supported (vec supported) :unsupported true :history [] ::definition definition)
      (let [entry (get platforms platform)
            over (dissoc entry :history)
            layered (concat (map #(vector :base %) history) (map #(vector :platform %) (:history entry)))
            entries (sort-by (comp :before second) #(version/compare-versions %2 %1) layered)
            steps (rest (reductions (fn [[b p] [layer e]]
                                      (if (= :base layer) [(merge b (dissoc e :before)) p] [b (merge p (dissoc e :before))]))
                                    [base over] entries))]
        (assoc (complete (merge base over) platform)
               :history (mapv (fn [[_ e] [b p]] {:before (:before e) :package (complete (merge b p) platform)})
                              entries steps)
               :platform platform
               :supported (vec supported)
               ::definition definition)))))

(defn for-platform
  "`pkg` resolved for `platform` instead of the one it was loaded for."
  [pkg platform]
  (if (= platform (:platform pkg)) pkg (normalize (::definition pkg) platform)))

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
        :else (or (some identity (rest m))
                  (throw (ex-info (str (:name pkg) ": the groups of the " k " pattern " match " match nothing in version " version)
                                  {:breto/problem :invalid-package :package (:name pkg)
                                   :problems [(str ":vars " k " matches version " version " without any group")]})))))
    (str/replace version (first replace) (second replace))))

(defn fetched-vars
  "The :vars of `pkg` read from a document, {:text URL :match REGEX} or
  {:json URL :path [path]}."
  [pkg]
  (into {} (filter (comp #(or (:text %) (:json %)) val)) (:vars pkg)))

(defn template-vars
  "The placeholders of `release`. A var read from a document takes its value
  from the release's :fetched; a release built without fetching, as for the
  commands of an installed version, keeps its placeholder, which only the
  download names use."
  [{:keys [name vars] :as pkg} {:keys [tag version fetched]}]
  (into {:name name :tag (or tag "") :version (or version "") :repo (or (repo pkg) "")}
        (when (seq version)
          (for [[k spec] vars]
            [k (if (or (:text spec) (:json spec))
                 (get fetched k (str "{" (clojure.core/name k) "}"))
                 (derived pkg k spec version))]))))

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
     :checksum-url (some->> (if (map? checksum) (:json checksum) checksum)
                            (#(template/render % file-vars))
                            (sibling-url asset-url))
     :checksum-path (when (map? checksum)
                      (mapv #(cond (string? %) (template/render % file-vars)
                                   (map? %) (update-vals % (fn [v] (template/render v file-vars)))
                                   :else %)
                            (:hash checksum)))
     :signature (when-let [{:keys [bundle] :as sig} signature]
                  (-> sig
                      (assoc :bundle-url (sibling-url asset-url (template/render bundle file-vars)))
                      (update :identity #(some-> % (template/render file-vars)))
                      (update :of #(if (map? %) (update % :file template/render file-vars) (or % :asset)))))
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
