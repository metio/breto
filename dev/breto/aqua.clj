;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.aqua
  "Converts the github_release packages of an aqua-registry checkout into
  package definitions for linux/amd64. Only what translates exactly is
  converted; everything else is skipped with the reason."
  (:require [babashka.fs :as fs]
            [breto.package :as package]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]))

(def ^:private os "linux")
(def ^:private arch "amd64")

(def ^:private archive-formats
  {"tar.gz" ".tar.gz" "tgz" ".tgz" "tar.xz" ".tar.xz" "txz" ".txz" "tar.bz2" ".tar.bz2"
   "tbz2" ".tbz2" "tar" ".tar" "zip" ".zip"})

(defn- skip! [reason & more]
  (throw (ex-info reason {::skip (str/join " " (cons reason more))})))

(defn- simple-lower-bound? [constraint]
  (boolean (re-matches #"semver\(\">=? ?[0-9][^\"]*\"\)" constraint)))

(defn- latest-spec
  "The package as aqua resolves it for a current release: the first override
  whose constraint holds for any recent version, merged onto the base."
  [{:keys [version_constraint version_overrides] :as pkg}]
  (let [base (dissoc pkg :version_overrides :version_constraint)]
    (if (or (nil? version_constraint) (= "true" version_constraint) (simple-lower-bound? version_constraint))
      base
      (if-let [o (first (filter #(let [c (:version_constraint %)]
                                   (or (= "true" c) (simple-lower-bound? c)))
                                version_overrides))]
        (merge base (dissoc o :version_constraint))
        (skip! "no override covers current releases")))))

(defn- platform-overrides
  "`spec` with its overrides for linux/amd64 applied in order."
  [{:keys [overrides] :as spec}]
  (reduce (fn [s {:keys [goos goarch] :as o}]
            (if (and (contains? #{nil os} goos) (contains? #{nil arch} goarch))
              (merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) b))
                          s (dissoc o :goos :goarch))
              s))
          (dissoc spec :overrides)
          overrides))

(defn- supported? [{:keys [supported_envs]}]
  (or (nil? supported_envs)
      (some #{"all" os arch (str os "/" arch)} supported_envs)))

(defn- tag-prefix
  "The prefix breto strips from tags, from a tag aqua's tests install."
  [example]
  (if (and example (re-find #"^v\d" example)) "v" ""))

(defn- strip-format
  "`s` without the extension of `format`."
  [s format]
  (let [ext (archive-formats format)]
    (if (and ext (str/ends-with? s ext)) (subs s 0 (- (count s) (count ext))) s)))

(defn- convert-template
  "The breto template for an aqua template, or a skip when it uses anything
  besides plain variables and trimV."
  [s {:keys [replacements format prefix asset]}]
  (let [os-name (get replacements (keyword os) os)
        arch-name (get replacements (keyword arch) arch)
        version (if (= "v" prefix) "{version}" "{tag}")
        out (str/replace (or s "") #"\{\{\s*([^}]*?)\s*\}\}"
                         (fn [[whole expr]]
                           (case (str/replace expr #"\s+" " ")
                             ".OS" os-name
                             ".GOOS" os
                             "title .OS" (str/capitalize os-name)
                             ".Arch" arch-name
                             ".GOARCH" arch
                             ".Format" (or format (skip! "uses .Format without a format"))
                             (".Version" ".SemVer") "{tag}"
                             ("trimV .Version" ".Version|trimV" ".Version | trimV" "trimV .SemVer") version
                             ".Asset" (if asset asset "{asset}")
                             ".AssetWithoutExt" (if asset (strip-format asset format)
                                                    (skip! "uses .AssetWithoutExt in the asset"))
                             (skip! "unsupported template" whole))))]
    (when (str/includes? out "{{") (skip! "unsupported template" s))
    out))

(defn- checksum-template [{:keys [checksum] :as spec} ctx]
  (let [{:keys [type asset url enabled algorithm file_format]} checksum]
    (when (and checksum (not (false? enabled))
               (contains? #{nil "sha256" "sha512"} algorithm)
               (contains? #{nil "raw"} file_format))
      (case type
        "github_release" (some-> asset (convert-template ctx))
        "http" (some-> url (convert-template ctx))
        nil))))

(defn- bins [spec name asset format]
  (let [files (or (:files spec) [{:name name}])
        raw? (nil? (archive-formats format))]
    (into (sorted-map)
          (for [{file :name src :src} files]
            [file (cond
                    raw? "{asset}"
                    src (convert-template (str/replace src "{{.FileName}}" file)
                                          {:replacements (:replacements spec) :format format
                                           :prefix (:prefix spec) :asset asset})
                    :else file)]))))

(defn- format-of
  "The archive format of `spec`, from :format or the asset's extension."
  [{:keys [format asset]}]
  (cond
    (= "raw" format) nil
    (archive-formats format) format
    format (skip! "unsupported format" format)
    :else (some (fn [[f ext]] (when (and (str/ends-with? asset ext) (not (str/includes? asset "{{.Format}}"))) f))
                (sort-by (comp - count val) archive-formats))))

(defn convert
  "The breto definition of one aqua package, given a tag its tests install."
  [pkg example name]
  (let [_ (when-not (= "github_release" (:type pkg)) (skip! "type" (:type pkg)))
        spec (platform-overrides (latest-spec pkg))
        _ (cond
            (not (supported? spec)) (skip! "no linux/amd64 build")
            (:no_asset spec) (skip! "no asset")
            (:error_message spec) (skip! "marked as an error")
            (or (:version_filter spec) (:version_prefix spec)) (skip! "filters versions")
            (str/blank? (:asset spec)) (skip! "no asset template"))
        format (format-of spec)
        prefix (tag-prefix example)
        ctx {:replacements (:replacements spec) :format format :prefix prefix}
        asset (convert-template (:asset spec) ctx)
        _ (when (and format (not (str/ends-with? asset (archive-formats format))))
            (skip! "asset" asset "does not end with" (archive-formats format)))
        _ (when (and (nil? format) (some #(str/ends-with? asset %) (vals archive-formats)))
            (skip! "raw asset with an archive extension" asset))
        _ (when (re-find #"\.(gz|bz2|xz|zst|dmg|pkg|msi|exe|deb|rpm|AppImage)$" asset)
            (when-not format (skip! "unsupported file type" asset)))
        checksum (checksum-template spec ctx)
        bins (bins (assoc spec :prefix prefix) name asset format)
        repo (str (:repo_owner pkg) "/" (:repo_name pkg))]
    (cond-> {:source {:github repo}}
      (not= "v" prefix) (assoc :tag-prefix prefix)
      true (assoc :asset asset)
      checksum (assoc :checksum checksum)
      (not= bins (if format {name name} {name "{asset}"})) (assoc :bins bins))))

(defn- render-edn
  "The definition as an EDN file with an MIT header, one key per line."
  [definition]
  ;; REUSE-IgnoreStart
  (str ";; SPDX-FileCopyrightText: 2021 Shunsuke Suzuki\n"
       ";; SPDX-FileCopyrightText: The breto Authors\n"
       ";; SPDX-License-Identifier: MIT\n\n"
       "{" (str/join "\n " (for [[k v] definition] (str k " " (pr-str v)))) "}\n"))
;; REUSE-IgnoreEnd

(defn- example-tag [dir]
  (let [f (fs/path dir "pkg.yaml")]
    (when (fs/exists? f)
      (some (fn [{:keys [name version]}]
              (or version (second (re-find #"@(.+)$" (str name)))))
            (:packages (yaml/parse-string (slurp (str f))))))))

(defn- validate!
  "Renders the definition for its example tag, as an install would."
  [name definition example]
  (let [pkg (package/normalize (assoc definition :name name))
        prefix (:tag-prefix pkg)
        tag (or example (str prefix "1.0.0"))]
    (when-not (str/starts-with? tag prefix) (skip! "example tag" tag "lacks prefix" prefix))
    (package/artifact pkg {:tag tag :version (subs tag (count prefix))})))

(defn -main [aqua-root out-dir]
  (let [existing (set (map #(str/replace (str (fs/file-name %)) #"\.edn$" "") (fs/glob out-dir "*.edn")))
        candidates (for [f (sort (map str (fs/glob aqua-root "pkgs/**/registry.yaml")))
                         :let [dir (fs/parent f)
                               pkgs (:packages (yaml/parse-string (slurp f)))]
                         pkg pkgs
                         :let [name (last (str/split (or (:name pkg) (:repo_name pkg) "") #"/"))]]
                     {:name (str/lower-case name) :pkg pkg :dir dir :many (> (count pkgs) 1)})
        by-name (group-by :name candidates)
        results (for [{:keys [name pkg dir many]} candidates]
                  (try
                    (cond
                      (contains? existing name) {:name name :skip "already defined"}
                      (> (count (by-name name)) 1) {:name name :skip "name is ambiguous in aqua"}
                      (not (re-matches #"[a-z0-9][a-z0-9._-]*" name)) {:name name :skip "unusable name"}
                      :else (let [example (when-not many (example-tag dir))
                                  definition (convert pkg example name)]
                              (validate! name definition example)
                              {:name name :definition definition}))
                    (catch clojure.lang.ExceptionInfo e
                      {:name name :skip (or (::skip (ex-data e)) (ex-message e))})))]
    (doseq [{:keys [name definition]} results :when definition]
      (spit (str (fs/path out-dir (str name ".edn"))) (render-edn definition)))
    (let [skipped (remove :definition results)]
      (println "converted" (count (filter :definition results)) "skipped" (count skipped))
      (doseq [[reason n] (sort-by (comp - val) (frequencies (map #(first (str/split (:skip %) #" (?=\S*[{/.])")) skipped)))]
        (println " " n reason)))))
