;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.registry-test
  (:require [babashka.fs :as fs]
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
