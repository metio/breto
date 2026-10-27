;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.source
  (:require [breto.github :as github]
            [breto.package :as package]
            [breto.template :as template]
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
      (throw (ex-info (str name ": no asset in release " tag " matches " asset) {:package name})))
    {:tag tag :version (:version (last (sort-by :created builds)))}))

(defn- gitlab-latest-url [project]
  (let [[host path] (str/split project #"/" 2)]
    (str "https://" host "/api/v4/projects/" (URLEncoder/encode path "UTF-8")
         "/releases/permalink/latest")))

(defn latest
  "The tag and version of the newest release of `pkg`."
  [{:keys [source] :as pkg} {:keys [get-text] :as io}]
  (case (package/source-kind pkg)
    :github (if (package/rolling? pkg)
              (latest-rolling pkg io)
              (let [url (str "https://github.com/" (:github source) "/releases/latest")]
                (tagged pkg (or (get (json/parse-string (get-text url {"Accept" "application/json"})) "tag_name")
                                (throw (ex-info (str "no tag_name in " url) {:url url}))))))
    :gitlab (let [url (gitlab-latest-url (:gitlab source))]
              (tagged pkg (or (get (json/parse-string (get-text url {})) "tag_name")
                              (throw (ex-info (str "no tag_name in " url) {:url url})))))
    :text (tagged pkg (str/trim (get-text (:text source) {})))))
