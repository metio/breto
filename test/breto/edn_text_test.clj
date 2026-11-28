;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.edn-text-test
  (:require [breto.edn-text :as edn-text]
            [breto.lockfile :as lockfile]
            [breto.project :as project]
            [breto.registry :as registry]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(def ^:private deep (str (apply str (repeat 200000 "[")) (apply str (repeat 200000 "]"))))

(defn- problem [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest text-without-a-form-reads-as-nil
  (is (nil? (edn-text/read-text "")))
  (is (nil? (edn-text/read-text ";; nothing yet\n\n")))
  (is (= {:a 1} (edn-text/read-text "{:a 1} ; trailing comment"))))

(deftest inst-and-uuid-read-as-clojure-edn-reads-them
  (is (inst? (:at (edn-text/read-text "{:at #inst \"2026-11-28T00:00:00Z\"}"))))
  (is (uuid? (edn-text/read-text "#uuid \"115da3f3-30dd-4c85-9ca6-435d8802e133\"")))
  (is (= :briko.edn/tag-refused (:type (problem #(edn-text/read-text "#acme/thing {}"))))))

(deftest a-second-form-is-refused
  (is (= :briko.edn/trailing-form (:type (problem #(edn-text/read-text "{:a 1} {:b 2}"))))))

(deftest a-deep-project-file-is-an-invalid-project
  (spit (path ".breto.edn") deep)
  (let [data (problem #(project/read-file (path ".breto.edn")))]
    (is (= :invalid-project (:breto/problem data)))
    (is (str/includes? (first (:problems data)) "deeper than"))))

(deftest a-deep-definition-is-an-invalid-package
  (s/define! "alpha" {:source {:github "acme/alpha"} :asset "alpha"})
  (spit (path "packages" "beta.edn") deep)
  (let [data (problem #(s/load-registry [(path "packages")]))]
    (is (= :invalid-package (:breto/problem data)))
    (is (= "beta" (:package data)))))

(deftest a-deep-group-is-an-invalid-package
  (s/define! "alpha" {:source {:github "acme/alpha"} :asset "alpha"})
  (spit (path "packages" "@deep.edn") deep)
  (is (= :invalid-package (:breto/problem (problem #(registry/load-groups [(path "packages")]))))))

(deftest a-deep-or-oversized-lock-file-is-not-valid-edn
  (testing "nesting"
    (spit (path "breto.lock") deep)
    (let [data (problem #(lockfile/read-lock (path "breto.lock")))]
      (is (= :lockfile-format (:breto/problem data)))
      (is (= (path "breto.lock") (:path data)))))
  (testing "size"
    (spit (path "breto.lock") (str "{\"a\" \"" (apply str (repeat (* 2 1024 1024) "x")) "\"}"))
    (is (= :lockfile-format (:breto/problem (problem #(lockfile/read-lock (path "breto.lock"))))))))
