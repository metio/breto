;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.remote
  "Registries published as releases. A :registries entry names where the
  releases live: {:github \"OWNER/REPO\"}, {:gitlab \"HOST/GROUP/PROJECT\"},
  {:forgejo \"HOST/OWNER/REPO\"} or {:gitea …}, or {:url \"https://…/\"} for a
  plain directory. Each release carries NAME-VERSION.tar.gz with the
  registry's files, NAME_VERSION_SHA256SUMS and its cosign bundle
  NAME_VERSION_SHA256SUMS.bundle side by side, NAME being the last segment of
  the location. breto installs the release like a package of its own and
  reads the registry from the active version, so every command but install,
  update and sync works offline."
  (:require [breto.platform :as platform]
            [breto.store :as store]
            [clojure.string :as str])
  (:import (java.net URLEncoder)))

(def kinds
  "The keys naming where a published registry lives."
  [:github :gitlab :forgejo :gitea :url])

(def ^:private keys-allowed (into #{:tag-prefix :signature} kinds))

(def ^:private location-patterns
  {:github #"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"
   :gitlab #"[A-Za-z0-9.-]+(?::[0-9]+)?(?:/[A-Za-z0-9_.-]+){2,}"
   :forgejo #"[A-Za-z0-9.-]+(?::[0-9]+)?/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"
   :gitea #"[A-Za-z0-9.-]+(?::[0-9]+)?/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"
   :url #"https://[A-Za-z0-9.-]+(?::[0-9]+)?(?:/[A-Za-z0-9_.~%-]+)+/"})

(def ^:private location-shapes
  {:github ":github names the repository as OWNER/REPO"
   :gitlab ":gitlab names the project as HOST/GROUP/PROJECT, subgroups included"
   :forgejo ":forgejo names the repository as HOST/OWNER/REPO"
   :gitea ":gitea names the repository as HOST/OWNER/REPO"
   :url ":url names an https directory ending in /, such as https://example.org/registry/"})

(defn remote?
  "Whether the :registries entry `entry` names a published registry rather
  than a directory."
  [entry]
  (map? entry))

(defn kind
  "Which of `kinds` `entry` uses, or nil unless exactly one."
  [entry]
  (let [found (filter #(contains? entry %) kinds)]
    (when (= 1 (count found)) (first found))))

(defn location [entry]
  (some-> (kind entry) entry))

(defn label
  "`entry` as a person names it, such as metio/toolchains or
  gitlab.example.com/tools/registry."
  [entry]
  (str (location entry)))

(defn- default-signature?
  "Whether the release of `entry` has a signer breto knows by default: the
  release workflow of a GitHub repository, or the CI of a project on
  gitlab.com."
  [entry]
  (or (= :github (kind entry))
      (and (= :gitlab (kind entry)) (str/starts-with? (location entry) "gitlab.com/"))))

(defn- signature-problem [signature]
  (cond
    (nil? signature) nil
    (false? signature) nil
    (not (map? signature)) ":signature maps :identity or :identity-regexp and :issuer to the signing certificate, or is false"
    (seq (remove #{:identity :identity-regexp :issuer} (keys signature)))
    ":signature has only :identity, :identity-regexp and :issuer"
    (not (every? string? (vals signature))) ":signature values are strings"
    (and (:identity signature) (:identity-regexp signature)) ":signature has :identity or :identity-regexp, not both"))

(defn problems
  "What is wrong with the published registry entry `entry`."
  [{:keys [tag-prefix signature] :as entry}]
  (let [k (kind entry)
        unknown (remove keys-allowed (keys entry))]
    (cond-> []
      (seq unknown)
      (conj (str "unknown keys " (str/join ", " (map pr-str (sort unknown)))
                 "; a published registry has one of " (str/join ", " (map pr-str kinds)) ", and :tag-prefix and :signature"))
      (nil? k)
      (conj (str "a published registry names exactly one of " (str/join ", " (map pr-str kinds))))
      (and k (not (and (string? (location entry)) (re-matches (location-patterns k) (location entry)))))
      (conj (location-shapes k))
      (and (some? tag-prefix) (not (string? tag-prefix)))
      (conj ":tag-prefix is the text before the version in a tag")
      (signature-problem signature)
      (conj (signature-problem signature))
      (and k (map? signature) (not (default-signature? entry))
           (not (and (or (:identity signature) (:identity-regexp signature)) (:issuer signature))))
      (conj (str ":signature needs :issuer and :identity or :identity-regexp, since breto knows no signer for "
                 (label entry) "; :signature false checks the checksum file alone"))
      (and k (nil? signature) (not (default-signature? entry)))
      (conj (str "breto knows no signer for " (label entry)
                 ": :signature names it, or :signature false checks the checksum file alone")))))

(defn- segments
  "The host and path segments of the location of `entry`."
  [entry]
  (let [loc (str/replace (location entry) #"^https://|/$" "")]
    (str/split loc #"/")))

(defn- base-name
  "NAME in the asset names: the repository, project or directory."
  [entry]
  (last (segments entry)))

(defn package-name
  "The name the release of `entry` is installed under in the store: OWNER-REPO
  for GitHub, the host and path for every other location."
  [entry]
  (str "registry-" (str/join "-" (map #(str/replace % ":" "-") (segments entry)))))

(defn- source
  "The package source finding the newest release of `entry`, and the URL its
  assets are downloaded from."
  [entry name]
  (let [loc (location entry)]
    (case (kind entry)
      :github {:source {:github loc}}
      :gitlab (let [[host path] (str/split loc #"/" 2)]
                {:source {:gitlab loc}
                 :url (str "https://" host "/api/v4/projects/" (URLEncoder/encode path "UTF-8")
                           "/packages/generic/" name "/{version}/{asset}")})
      (:forgejo :gitea) {:source {(kind entry) loc}}
      :url {:source {:text (str loc "latest")}
            :url (str loc "{version}/{asset}")})))

(defn- default-identity
  [entry]
  (case (kind entry)
    :github {:identity (str "https://github.com/" (location entry) "/.github/workflows/release.yml@refs/heads/main")
             :issuer "https://token.actions.githubusercontent.com"}
    :gitlab {:identity (str "https://" (location entry) "//.gitlab-ci.yml@refs/heads/main")
             :issuer "https://gitlab.com"}
    nil))

(defn definition
  "The package definition installing the release of `entry`. The checksum
  file must be signed: by default by a GitHub repository's release workflow
  on main, or by the CI of a gitlab.com project on main, as their OIDC
  issuers certify it. :signature names another signer, and :signature false
  checks the downloads against the checksum file alone."
  [{:keys [tag-prefix signature] :as entry}]
  (let [name (base-name entry)
        signer (when-not (false? signature)
                 (let [given (when (map? signature) signature)]
                   (if (or (:identity given) (:identity-regexp given))
                     (merge (select-keys (default-identity entry) [:issuer]) given)
                     (merge (default-identity entry) given))))]
    (cond-> (merge {:description (str "The registry " (label entry) " publishes")
                    :tag-prefix (or tag-prefix "")
                    :asset (str name "-{version}.tar.gz")
                    :checksum (str name "_{version}_SHA256SUMS")
                    :layout :tree
                    :bins {}
                    :platforms (zipmap platform/known (repeat {}))}
                   (source entry name))
      signer (assoc :signature (merge {:bundle (str name "_{version}_SHA256SUMS.bundle") :of :checksum}
                                      signer)))))

(defn dir
  "The directory of the active release of `entry`, or nil before one is
  installed."
  [config entry]
  (when-let [version (store/current-version config (package-name entry))]
    (str (store/version-dir config (package-name entry) version))))
