;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.github
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn- token []
  (some #(let [v (System/getenv %)] (when-not (str/blank? v) v))
        ["GITHUB_TOKEN" "GH_TOKEN"]))

(defn api-headers
  "Unauthenticated requests share a limit of 60 an hour per address, so a token
  from GITHUB_TOKEN or GH_TOKEN is sent when one is set."
  []
  (cond-> {"Accept" "application/vnd.github+json"}
    (token) (assoc "Authorization" (str "Bearer " (token)))))

(defn release
  "The API representation of the release tagged `tag` in `repo`."
  [{:keys [get-text]} repo tag]
  (json/parse-string
   (get-text (str "https://api.github.com/repos/" repo "/releases/tags/" (str/replace tag "/" "%2F"))
             (api-headers))
   true))

(defn asset-digest
  "The sha256 GitHub recorded for an asset, or nil. Assets uploaded before
  GitHub began recording digests carry none."
  [release asset-name]
  (some (fn [{:keys [name digest]}]
          (when (and (= name asset-name) (string? digest) (str/starts-with? digest "sha256:"))
            (subs digest (count "sha256:"))))
        (:assets release)))

(defn repo-info
  "The API representation of `repo`. GitHub answers for a renamed repository
  under its new name, so `:full_name` reveals a move."
  [{:keys [get-text]} repo]
  (json/parse-string (get-text (str "https://api.github.com/repos/" repo) (api-headers)) true))
