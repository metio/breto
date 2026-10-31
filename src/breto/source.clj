;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.source
  (:require [breto.cargo :as cargo]
            [breto.coursier :as coursier]
            [breto.document :as document]
            [breto.ghcup :as ghcup]
            [breto.github :as github]
            [breto.npm :as npm]
            [breto.package :as package]
            [breto.platform :as platform]
            [breto.template :as template]
            [breto.version :as version]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.net URLEncoder)))

(defn- tagged [pkg tag]
  {:tag tag :version (package/version-of-tag pkg tag)})

(defn- release-of
  "The release `tag` names for `pkg`, or nil when it names none. A tag counts
  when it starts with the :tag-prefix of the definition that applies to the
  version after it, so a :history entry keeps the prefix of older releases
  and a repository releasing several components yields only this one."
  [pkg tag]
  (some (fn [prefix]
          (when (str/starts-with? tag prefix)
            (let [version (subs tag (count prefix))]
              (when (and (seq version) (= prefix (:tag-prefix (package/at pkg version))))
                {:tag tag :version version}))))
        (sort-by (comp - count) (distinct (cons (:tag-prefix pkg) (map (comp :tag-prefix :package) (:history pkg)))))))

(defn- latest-rolling
  "The newest build in a rolling release, by upload time, that :skip does
  not name. The other releases of the repository are unrelated to the builds,
  so a skipped build falls back to an older one of the same release."
  [{:keys [source asset name] :as pkg} io]
  (let [tag (:tag source)
        pattern (template/matcher asset (package/template-vars pkg {:tag tag}))
        builds (keep (fn [{asset-name :name created :created_at}]
                       (when-let [m (re-matcher pattern asset-name)]
                         (when (.matches m)
                           {:version (.group m "version") :created created})))
                     (:assets (github/release io (:github source) tag)))]
    (when (empty? builds)
      (throw (ex-info (str name ": no asset in release " tag " matches " asset)
                      {:breto/problem :rolling-no-match :package name :repo (:github source) :tag tag :asset asset})))
    (let [skip (set (:skip pkg))
          newest (last (remove (comp skip :version) (sort-by :created builds)))]
      (when-not newest
        (throw (ex-info (str name ": every build in release " tag " is listed in :skip")
                        {:breto/problem :all-skipped :package name :latest (:version (last (sort-by :created builds)))
                         :skip (vec (:skip pkg)) :source source})))
      {:tag tag :version (:version newest)})))

(defn- gitlab-api [project suffix]
  (let [[host path] (str/split project #"/" 2)]
    (str "https://" host "/api/v4/projects/" (URLEncoder/encode path "UTF-8") suffix)))

(defn- gitlab-latest-url [project]
  (gitlab-api project "/releases/permalink/latest"))

(defn json-version
  "The value at the :version path of a :json source in the parsed document
  `doc`. A missing value names the deepest part of the path that exists."
  [{:keys [source name]} doc]
  (let [path (:version source)
        step (fn [node k] (if (nat-int? k)
                            (when (sequential? node) (get (vec node) k))
                            (when (map? node) (get node (if (keyword? k) (clojure.core/name k) k)))))
        found (reductions step doc path)
        value (last found)]
    (if (or (string? value) (integer? value))
      (str value)
      (let [depth (count (take-while some? (rest found)))
            node (nth found depth)]
        (throw (ex-info (str name ": no version at " (pr-str path) " in " (:json source)
                             (when (number? value) ", only a fractional number, which JSON parsing cannot keep exact"))
                        {:breto/problem :json-path :package name :url (:json source) :path path
                         :found (vec (take depth path))
                         :keys (cond (map? node) (vec (sort (keys node)))
                                     (sequential? node) [(str (count node) " entries")]
                                     :else [])}))))))

(defn- go-proxy-url [source suffix]
  (str "https://proxy.golang.org/" (package/go-escape (:go source)) suffix))

(defn- pypi-url [source suffix]
  (str "https://pypi.org/pypi/" (:pypi source) suffix))

(declare release-tags)

(defn- newest-listed
  "The newest release of `pkg` among its listed releases that `skip` does not
  name, or nil."
  [pkg io skip]
  (->> (release-tags pkg io)
       (keep #(release-of pkg %))
       (remove (comp skip :version))
       (sort #(version/compare-versions (:version %2) (:version %1)))
       first))

(defn- listed-latest
  "The release the forge marks as latest, `tag`, when it is one of `pkg`;
  else, as for another component of the same repository, the newest listed
  release that is. Without one, the tag is reported against :tag-prefix."
  [pkg io tag]
  (or (release-of pkg tag)
      (newest-listed pkg io #{})
      (tagged pkg tag)))

(defn latest
  "The tag and version of the newest release of `pkg`."
  [{:keys [source] :as pkg} {:keys [get-text] :as io}]
  (case (package/source-kind pkg)
    :github (if (package/rolling? pkg)
              (latest-rolling pkg io)
              (let [url (str "https://github.com/" (:github source) "/releases/latest")]
                (listed-latest pkg io (or (get (json/parse-string (get-text url {"Accept" "application/json"})) "tag_name")
                                          (throw (ex-info (str "no tag_name in " url) {:breto/problem :no-release :url url}))))))
    :gitlab (let [url (gitlab-latest-url (:gitlab source))]
              (listed-latest pkg io (or (get (json/parse-string (get-text url {})) "tag_name")
                                        (throw (ex-info (str "no tag_name in " url) {:breto/problem :no-release :url url})))))
    :text (if (:newest source)
            (or (newest-listed pkg io #{})
                (throw (ex-info (str "nothing in " (:text source) " matches " (:match source))
                                {:breto/problem :no-release :url (:text source)})))
            (let [body (get-text (:text source) {})]
            (tagged pkg (if-let [pattern (:match source)]
                          (let [m (re-find (re-pattern pattern) body)]
                            (cond (nil? m) (throw (ex-info (str "nothing in " (:text source) " matches " pattern)
                                                           {:breto/problem :no-release :url (:text source)}))
                                  (string? m) m
                                  :else (or (some identity (rest m))
                                            (throw (ex-info (str "the groups of " pattern " match nothing in " (:text source))
                                                            {:breto/problem :no-release :url (:text source)})))))
                          (str/trim body)))))
    :json (tagged pkg (json-version pkg (document/parse (:json source) (get-text (:json source) {"Accept" "application/json"}))))
    :go (let [url (go-proxy-url source "/@latest")]
          (tagged pkg (or (get (json/parse-string (get-text url {})) "Version")
                          (throw (ex-info (str "no Version in " url) {:breto/problem :no-release :url url})))))
    :pypi (let [url (pypi-url source "/json")]
            (tagged pkg (or (get-in (json/parse-string (get-text url {"Accept" "application/json"})) ["info" "version"])
                            (throw (ex-info (str "no info.version in " url) {:breto/problem :no-release :url url})))))
    :cargo (if-let [repo (:git source)]
             (let [head (json/parse-string (get-text (cargo/head-url repo) (github/api-headers io)))
                   version (cargo/git-version (get head "sha") (get-in head ["commit" "committer" "date"]))]
               {:tag version :version version})
             (or (newest-listed pkg io #{})
                 (throw (ex-info (str "no release of " (:cargo source) " in " (cargo/index-url (:cargo source)))
                                 {:breto/problem :no-release :url (cargo/index-url (:cargo source))}))))
    :coursier (or (newest-listed pkg io #{})
                  (throw (ex-info (str "no release of " (:coursier source) " in " (coursier/metadata-url (:coursier source)))
                                  {:breto/problem :no-release :url (coursier/metadata-url (:coursier source))})))
    :ghcup (or (newest-listed pkg io #{})
               (throw (ex-info (str "no release of " (:ghcup source) " for this platform in " ghcup/metadata-url)
                               {:breto/problem :no-release :url ghcup/metadata-url})))
    :npm (let [url (npm/registry-url (:npm source) "/latest")]
           (tagged pkg (or (get (json/parse-string (get-text url {"Accept" "application/json"})) "version")
                           (throw (ex-info (str "no version in " url) {:breto/problem :no-release :url url})))))))

(defn- release-tags
  "Tags of the published, non-prerelease releases of `pkg`, or nil for a
  source without a release listing."
  [{:keys [source] :as pkg} {:keys [get-text] :as io}]
  (case (package/source-kind pkg)
    :github (->> (json/parse-string (get-text (str "https://api.github.com/repos/" (:github source)
                                                    "/releases?per_page=100")
                                               (github/api-headers io))
                                    true)
                 (remove #(or (:draft %) (:prerelease %)))
                 (map :tag_name))
    :gitlab (->> (json/parse-string (get-text (gitlab-api (:gitlab source) "/releases?per_page=100") {}) true)
                 (remove :upcoming_release)
                 (map :tag_name))
    :go (->> (str/split-lines (get-text (go-proxy-url source "/@v/list") {}))
             (remove str/blank?)
             (remove #(re-find #"^v\d+\.\d+\.\d+-" %)))
    :pypi (->> (get (json/parse-string (get-text (pypi-url source "/json") {"Accept" "application/json"})) "releases")
               (keep (fn [[version files]] (when (and (seq files) (not-every? #(get % "yanked") files)) version)))
               (remove #(re-find #"\d(a|b|rc|\.dev)\d*" %)))
    :text (when (:newest source)
            (->> (re-seq (re-pattern (:match source)) (get-text (:text source) {}))
                 (keep #(if (string? %) % (some identity (rest %))))
                 distinct))
    :cargo (when-not (:git source)
             (->> (json/parsed-seq (java.io.StringReader. (get-text (cargo/index-url (:cargo source)) {})))
                  (remove #(get % "yanked"))
                  (map #(get % "vers"))
                  (remove #(str/includes? % "-"))))
    :npm (->> (get (json/parse-string (get-text (npm/registry-url (:npm source) "")
                                                {"Accept" "application/vnd.npm.install-v1+json"}))
                   "versions")
              (keep (fn [[version meta]] (when-not (get meta "deprecated") version)))
              (remove #(str/includes? % "-")))
    :coursier (coursier/release-versions (get-text (coursier/metadata-url (:coursier source)) {}))
    :ghcup (ghcup/versions (document/parse ghcup/metadata-url (get-text ghcup/metadata-url {}))
                           (:ghcup source) (or (:platform pkg) (platform/detect)))
    nil))

(defn- text-var
  "The first group of `match` in the document at `url`, or the whole match
  without a group."
  [pkg get-text k url match]
  (let [m (re-find (re-pattern match) (get-text url {}))]
    (cond (string? m) m
          (some identity (rest m)) (some identity (rest m))
          :else (throw (ex-info (str (:name pkg) ": " match " for :vars " k " matches nothing in " url)
                                {:breto/problem :no-release :url url})))))

(defn- json-var
  "The string or integer at `path` in the JSON or YAML document at `url`."
  [pkg get-text k url path]
  (let [doc (document/parse url (get-text url {}))
        value (document/at doc path)]
    (if (or (string? value) (integer? value))
      (str value)
      (throw (ex-info (str (:name pkg) ": no value for :vars " k " at " (pr-str path) " in " url)
                      {:breto/problem :json-path :package (:name pkg) :url url :path path
                       :found (vec (take (document/deepest doc path) path))})))))

(defn fetch-vars
  "`release` with the :vars of `pkg` that are read from a document in
  :fetched: the first group of :match in the body of :text, or the value at
  :path in the document :json. Their templates can use the release's other
  placeholders."
  [pkg {:keys [get-text]} release]
  (if-let [specs (seq (package/fetched-vars pkg))]
    (let [base (package/template-vars pkg release)
          render #(template/render % base)]
      (assoc release :fetched
             (into {} (for [[k {:keys [text match json path]}] specs]
                        [k (if text
                             (text-var pkg get-text k (render text) match)
                             (json-var pkg get-text k (render json)
                                       (mapv #(cond (string? %) (render %)
                                                    (map? %) (update-vals % render)
                                                    :else %)
                                             path)))]))))
    release))

(defn newest
  "The tag and version of the newest release of `pkg` that its `:skip` list
  does not name. The releases are only listed when the latest one is skipped."
  [pkg io]
  (let [found (latest pkg io)
        skip (set (:skip pkg))]
    (if-not (skip (:version found))
      found
      (or (newest-listed pkg io skip)
          (throw (ex-info (str (:name pkg) ": every release found is listed in :skip")
                          {:breto/problem :all-skipped :package (:name pkg) :latest (:version found)
                           :skip (vec (:skip pkg)) :source (:source pkg)}))))))
