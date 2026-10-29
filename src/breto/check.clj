;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.check
  "Checks a definition against its upstream without downloading the release:
  the asset must exist and something must be able to verify it."
  (:require [breto.github :as github]
            [breto.install :as install]
            [breto.package :as package]
            [clojure.string :as str]))

(defn- release-assets [io pkg release]
  (let [data (github/release io (get-in pkg [:source :github]) (:tag release))]
    {:names (set (map :name (:assets data))) :data data}))

(defn- missing! [pkg release artifact names]
  (throw (ex-info (str (:name pkg) ": release " (:tag release) " has no asset " (:asset artifact))
                  {:breto/problem :asset-missing :package (:name pkg) :asset (:asset artifact)
                   :tag (:tag release) :version (:version release) :assets (vec (sort names))
                   :repo (package/repo pkg) :source (:source pkg)})))

(defn check-package
  "The check result for the release `pkg` would install now, or an exception
  describing why `breto update` would fail on it."
  [io pkg]
  (let [context {:package (:name pkg) :repo (package/repo pkg) :source (:source pkg)}
        release (install/with-stage :resolve context #(install/resolve-release pkg io nil))
        version (:version release)
        pkg (package/at pkg version)
        artifact (package/artifact pkg release)
        context (assoc context :version version :tag (:tag release) :asset (:asset artifact)
                       :url (:url artifact) :checksum-url (:checksum-url artifact))
        checksum-url (:checksum-url artifact)]
    (install/with-stage :check context
      (fn []
        (let [listed (atom nil)
              verified-by
              (if (install/github-asset? pkg artifact)
                (let [{:keys [names data]} (release-assets io pkg release)
                      _ (reset! listed names)
                      sibling? #(str/starts-with? % (subs (:url artifact) 0 (inc (str/last-index-of (:url artifact) "/"))))]
                  (when-not (names (:asset artifact))
                    (missing! pkg release artifact names))
                  (cond
                    (and (github/asset-digest data (:asset artifact))
                         (not= :checksum (get-in artifact [:signature :of]))) :digest
                    (and checksum-url (sibling? checksum-url)
                         (names (subs checksum-url (inc (str/last-index-of checksum-url "/"))))) :checksum
                    (and checksum-url (not (sibling? checksum-url)) ((:exists? io) checksum-url)) :checksum
                    checksum-url (throw (ex-info (str "no checksum file at " checksum-url)
                                                 (assoc context :breto/problem :http-status :status 404
                                                        :stage :verify :url checksum-url :headers {})))
                    :else nil))
                (do (when-not ((:exists? io) (:url artifact))
                      (throw (ex-info (str "no asset at " (:url artifact))
                                      (assoc context :breto/problem :http-status :status 404
                                             :stage :download :headers {}))))
                    (cond
                      (= :go (package/source-kind pkg)) :sumdb
                      (= :pypi (package/source-kind pkg)) :pypi
                      (= :npm (package/source-kind pkg)) :npm
                      (= :coursier (package/source-kind pkg)) :coursier
                      (= :ghcup (package/source-kind pkg)) (when (install/expected-hash pkg io release artifact) :ghcup)
                      (and checksum-url ((:exists? io) checksum-url)) :checksum
                      checksum-url (throw (ex-info (str "no checksum file at " checksum-url)
                                                   (assoc context :breto/problem :http-status :status 404
                                                          :stage :verify :url checksum-url :headers {})))
                      :else nil)))]
          (when-let [bundle (get-in artifact [:signature :bundle-url])]
            (let [file (subs bundle (inc (str/last-index-of bundle "/")))]
              (when-not (if @listed (@listed file) ((:exists? io) bundle))
                (throw (ex-info (str (:name pkg) " " version ": no signature bundle at " bundle)
                                (cond-> (assoc context :breto/problem :bundle-missing :bundle-url bundle :bundle file)
                                  @listed (assoc :assets (vec (sort @listed)))))))))
          (cond-> (cond
            verified-by {:package (:name pkg) :version version :status :checked :verified-by verified-by}
            (false? (:verify pkg true)) {:package (:name pkg) :version version :status :checked :verified-by :none}
            :else (throw (ex-info (str (:name pkg) " " version ": nothing can verify " (:asset artifact))
                                  (cond-> (assoc context :breto/problem :unverifiable)
                                    @listed (assoc :assets (vec (sort @listed)))))))
            (:signature artifact) (assoc :signed true)))))))
