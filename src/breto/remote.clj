;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.remote
  "Registries published as releases: a :registries entry such as
  {:github \"metio/toolchains\"} names a repository whose releases carry
  REPO-VERSION.tar.gz with the registry's files, REPO_VERSION_SHA256SUMS and
  its cosign bundle REPO_VERSION_SHA256SUMS.bundle. breto installs the
  release like a package of its own and reads the registry from the active
  version, so every command but install, update and sync works offline."
  (:require [breto.platform :as platform]
            [breto.store :as store]
            [clojure.string :as str]))

(def ^:private keys-allowed #{:github :tag-prefix :signature})

(defn remote?
  "Whether the :registries entry `entry` names a published registry rather
  than a directory."
  [entry]
  (map? entry))

(defn problems
  "What is wrong with the published registry entry `entry`."
  [{:keys [github tag-prefix signature] :as entry}]
  (cond-> []
    (seq (remove keys-allowed (keys entry)))
    (conj (str "unknown keys " (str/join ", " (map pr-str (sort (remove keys-allowed (keys entry)))))
               "; a published registry has :github, :tag-prefix and :signature"))
    (not (and (string? github) (re-matches #"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+" github)))
    (conj ":github names the repository as OWNER/REPO")
    (and (some? tag-prefix) (not (string? tag-prefix)))
    (conj ":tag-prefix is the text before the version in a tag")
    (and (some? signature) (not (and (map? signature) (every? #{:identity :issuer} (keys signature))
                                     (every? string? (vals signature)))))
    (conj ":signature maps :identity and :issuer to the certificate's identity and OIDC issuer")))

(defn package-name
  "The name the release of `entry` is installed under in the store."
  [{:keys [github]}]
  (str "registry-" (str/replace github "/" "-")))

(defn definition
  "The package definition installing the release of `entry`. The checksum
  file must be signed: by default by the repository's release workflow on
  main, as GitHub's OIDC issuer certifies it."
  [{:keys [github tag-prefix signature]}]
  (let [repo (second (str/split github #"/"))]
    {:description (str "The registry " github " publishes")
     :source {:github github}
     :tag-prefix (or tag-prefix "")
     :asset (str repo "-{version}.tar.gz")
     :checksum (str repo "_{version}_SHA256SUMS")
     :signature {:bundle (str repo "_{version}_SHA256SUMS.bundle")
                 :identity (or (:identity signature)
                               (str "https://github.com/" github "/.github/workflows/release.yml@refs/heads/main"))
                 :issuer (or (:issuer signature) "https://token.actions.githubusercontent.com")
                 :of :checksum}
     :layout :tree
     :bins {}
     :platforms (zipmap platform/known (repeat {}))}))

(defn dir
  "The directory of the active release of `entry`, or nil before one is
  installed."
  [config entry]
  (when-let [version (store/current-version config (package-name entry))]
    (str (store/version-dir config (package-name entry) version))))
