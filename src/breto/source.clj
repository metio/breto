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
  "The newest build in a rolling release, by upload time."
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
    {:tag tag :version (:version (last (sort-by :created builds)))}))

(defn- gitlab-api [project suffix]
  (let [[host path] (str/split project #"/" 2)]
    (str "https://" host "/api/v4/projects/" (URLEncoder/encode path "UTF-8") suffix)))

(defn- gitlab-latest-url [project]
  (gitlab-api project "/releases/permalink/latest"))

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
    :text (tagged pkg (str/trim (get-text (:text source) {})))))

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
