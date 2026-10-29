;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.source
  (:require [breto.github :as github]
            [breto.package :as package]
            [breto.template :as template]
            [breto.version :as version]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.net URLEncoder)))

(defn- tagged [pkg tag]
  {:tag tag :version (package/version-of-tag pkg tag)})

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

(defn latest
  "The tag and version of the newest release of `pkg`."
  [{:keys [source] :as pkg} {:keys [get-text] :as io}]
  (case (package/source-kind pkg)
    :github (if (package/rolling? pkg)
              (latest-rolling pkg io)
              (let [url (str "https://github.com/" (:github source) "/releases/latest")]
                (tagged pkg (or (get (json/parse-string (get-text url {"Accept" "application/json"})) "tag_name")
                                (throw (ex-info (str "no tag_name in " url) {:breto/problem :no-release :url url}))))))
    :gitlab (let [url (gitlab-latest-url (:gitlab source))]
              (tagged pkg (or (get (json/parse-string (get-text url {})) "tag_name")
                              (throw (ex-info (str "no tag_name in " url) {:breto/problem :no-release :url url})))))
    :text (let [body (get-text (:text source) {})]
            (tagged pkg (if-let [pattern (:match source)]
                          (let [m (re-find (re-pattern pattern) body)]
                            (cond (nil? m) (throw (ex-info (str "nothing in " (:text source) " matches " pattern)
                                                           {:breto/problem :no-release :url (:text source)}))
                                  (string? m) m
                                  :else (or (some identity (rest m))
                                            (throw (ex-info (str "the groups of " pattern " match nothing in " (:text source))
                                                            {:breto/problem :no-release :url (:text source)})))))
                          (str/trim body))))
    :json (tagged pkg (json-version pkg (json/parse-string (get-text (:json source) {"Accept" "application/json"}))))
    :go (let [url (go-proxy-url source "/@latest")]
          (tagged pkg (or (get (json/parse-string (get-text url {})) "Version")
                          (throw (ex-info (str "no Version in " url) {:breto/problem :no-release :url url})))))
    :pypi (let [url (pypi-url source "/json")]
            (tagged pkg (or (get-in (json/parse-string (get-text url {"Accept" "application/json"})) ["info" "version"])
                            (throw (ex-info (str "no info.version in " url) {:breto/problem :no-release :url url})))))))

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
    nil))

(defn newest
  "The tag and version of the newest release of `pkg` that its `:skip` list
  does not name. The releases are only listed when the latest one is skipped."
  [pkg io]
  (let [found (latest pkg io)
        skip (set (:skip pkg))]
    (if-not (skip (:version found))
      found
      (or (->> (release-tags pkg io)
               (filter #(str/starts-with? % (:tag-prefix pkg)))
               (map #(tagged pkg %))
               (remove (comp skip :version))
               (sort #(version/compare-versions (:version %2) (:version %1)))
               first)
          (throw (ex-info (str (:name pkg) ": every release found is listed in :skip")
                          {:breto/problem :all-skipped :package (:name pkg) :latest (:version found)
                           :skip (vec (:skip pkg)) :source (:source pkg)}))))))
