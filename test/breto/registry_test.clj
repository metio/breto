;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.registry-test
  (:require [babashka.fs :as fs]
            [breto.package :as package]
            [breto.registry :as registry]
            [clojure.test :refer [deftest is]]))

(deftest every-shipped-package-is-valid
  (let [reg (registry/load-registry ["packages"])]
    (is (< 60 (count reg)))
    (is (every? :url (vals reg)))))

(deftest overlays-merge-over-earlier-registries
  (let [overlay (fs/create-temp-dir)]
    (try
      (spit (str (fs/path overlay "kind.edn")) "{:asset \"kind-linux-arm64\"}")
      (let [kind (registry/lookup (registry/load-registry ["packages" (str overlay)]) "kind")]
        (is (= "kind-linux-arm64" (:asset kind)))
        (is (= {:github "kubernetes-sigs/kind"} (:source kind))))
      (finally (fs/delete-tree overlay)))))

(deftest unknown-packages-are-reported
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown package nope"
                        (registry/lookup {} "nope"))))

(deftest jetbrains-idea-links-its-command-and-icon
  (let [pkg (get (registry/load-registry ["packages"]) "jetbrains-idea")
        artifact (package/artifact pkg {:tag "2026.2.3" :version "2026.2.3"})]
    (is (= "https://download.jetbrains.com/idea/idea-2026.2.3.tar.gz" (:url artifact)))
    (is (= "https://download.jetbrains.com/idea/idea-2026.2.3.tar.gz.sha256" (:checksum-url artifact)))
    (is (= {"jetbrains-idea" "bin/idea"} (:bins pkg)))
    (is (= {"~/.local/bin/jetbrains-idea.svg" "bin/idea.svg"} (:links pkg)))))
