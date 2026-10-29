;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.document-test
  (:require [breto.document :as document]
            [breto.package :as package]
            [breto.source :as source]
            [breto.support :as s]
            [clojure.test :refer [deftest is testing]]))

(deftest documents-are-yaml-when-their-name-says-so
  (is (document/yaml-url? "https://example.org/meta.yaml"))
  (is (document/yaml-url? "https://example.org/meta.yml?ref=main"))
  (is (not (document/yaml-url? "https://example.org/meta.json")))
  (testing "anchors resolve to the value they name"
    (is (= {"b" 1} (document/at (document/parse "x.yaml" "a: &x {b: 1}\nc: *x\n") ["c"]))))
  (testing "JSON Lines are a list"
    (is (= "2" (document/at (document/parse "x" "{\"v\":\"1\"}\n{\"v\":\"2\"}\n") [1 "v"])))))

(deftest field-selectors-pick-the-first-matching-element
  (let [doc [{"name" "a" "n" 1} {"name" "b" "n" 2}]]
    (is (= 2 (document/at doc [{"name" "b"} "n"])))
    (is (= 2 (document/at doc [{:n "2"} :n])))
    (is (nil? (document/at doc [{"name" "c"} "n"])))
    (is (= 1 (document/deepest doc [{"name" "b"} "missing"])))))

(deftest vars-can-be-read-from-a-path-in-a-document
  (let [io (s/fake-io {} {"https://example.org/tool.yaml" "releases:\n  1.2.0:\n    file: tool-1.2.0-linux.tar.gz\n"}
                      (atom []))
        pkg (package/normalize {:name "tool" :source {:text "https://example.org/latest"}
                                :vars {:file {:json "https://example.org/tool.yaml" :path ["releases" "{version}" "file"]}}
                                :asset "{file}" :url "https://example.org/{asset}"})
        release (source/fetch-vars pkg io {:tag "1.2.0" :version "1.2.0"})]
    (is (= "https://example.org/tool-1.2.0-linux.tar.gz" (:url (package/artifact pkg release))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no value for :vars :file"
                          (source/fetch-vars pkg io {:tag "2.0.0" :version "2.0.0"})))))
