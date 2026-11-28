;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.document-test
  (:require [breto.diagnose :as diagnose]
            [breto.document :as document]
            [breto.github :as github]
            [breto.http :as http]
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
        pkg (s/normalize {:name "tool" :source {:text "https://example.org/latest"}
                                :vars {:file {:json "https://example.org/tool.yaml" :path ["releases" "{version}" "file"]}}
                                :asset "{file}" :url "https://example.org/{asset}"})
        release (source/fetch-vars pkg io {:tag "1.2.0" :version "1.2.0"})]
    (is (= "https://example.org/tool-1.2.0-linux.tar.gz" (:url (package/artifact pkg release))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no value for :vars :file"
                          (source/fetch-vars pkg io {:tag "2.0.0" :version "2.0.0"})))))

(deftest a-selector-finds-the-only-line-of-a-json-lines-index
  (let [url "https://index.crates.io/ab/cd/abcd"
        one "{\"name\":\"abcd\",\"vers\":\"1.0.0\",\"cksum\":\"aa\"}\n"
        two (str one "{\"name\":\"abcd\",\"vers\":\"1.1.0\",\"cksum\":\"bb\"}\n")]
    (is (= "aa" (document/at (document/parse url one) [{:vers "1.0.0"} "cksum"])))
    (is (= "bb" (document/at (document/parse url two) [{:vers "1.1.0"} "cksum"])))
    (is (nil? (document/at (document/parse url one) [{:vers "2.0.0"} "cksum"])))))

(deftest json-documents-keep-string-keys-and-their-numbers
  (is (= {"a" 1 "b" 1.5 "c" [true nil "x"]} (document/parse "x.json" "{\"a\":1,\"b\":1.5,\"c\":[true,null,\"x\"]}")))
  (is (= 123456789012345678901234567890N (document/parse "x" "123456789012345678901234567890")))
  (is (= [] (document/parse "x" "")))
  (is (= [] (document/parse "x" " \n")))
  (is (= [{"v" 1} {"v" 2}] (document/parse "x" "{\"v\":1} {\"v\":2}"))))

(deftest a-malformed-json-document-fails
  (is (thrown? Exception (document/parse "x" "{\"a\":")))
  (is (thrown? Exception (document/parse "x" "{\"a\":1}]"))))

(defn- problem-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest json-documents-are-read-strictly-and-name-their-url
  (is (= {"a" 1} (document/read-json "u" "{\"a\":1}")) "a valid document reads")
  (doseq [[text reason] [["{\"a\":1,\"a\":2}" "duplicate-key"]
                         ["{\"a\":1} {\"b\":2}" "trailing-value"]
                         ["{'a':1}" "malformed"]
                         ["" "no-value"]
                         ["1e400" "number-out-of-range"]]]
    (let [data (problem-of #(document/read-json "https://example.org/x.json" text))]
      (is (= :invalid-json (:breto/problem data)) text)
      (is (= reason (:exception data)) text)
      (is (= "https://example.org/x.json" (:url data)) text)))
  (testing "JSON Lines still refuse an object naming a key twice"
    (is (= "duplicate-key" (:exception (problem-of #(document/parse "x" "{\"v\":1}\n{\"v\":1,\"v\":2}")))))))

(deftest every-body-a-lookup-returns-can-be-read-as-json
  (is (= http/max-body-bytes document/max-json-bytes)))

(deftest a-malformed-api-answer-is-an-invalid-json-problem
  (let [url "https://api.github.com/repos/o/r/releases/tags/v1"
        io (s/fake-io {} {url "<html>rate limited</html>"} (atom []))
        data (problem-of #(github/release io "o/r" "v1"))]
    (is (= :invalid-json (:breto/problem data)))
    (is (= url (:url data)))
    (is (re-find #"not JSON" (:summary (diagnose/diagnose (ex-info "x" data) {}))))))

(deftest api-answers-keep-keyword-keys
  (let [url "https://api.github.com/repos/o/r/releases/tags/v1"
        io (s/fake-io {} {url "{\"assets\":[{\"name\":\"a\",\"digest\":\"sha256:ab\"}]}"} (atom []))]
    (is (= "ab" (github/asset-digest (github/release io "o/r" "v1") "a")))))
