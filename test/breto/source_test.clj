;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.source-test
  (:require [breto.diagnose :as diagnose]
            [breto.package :as package]
            [breto.source :as source]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private idea
  (package/normalize {:name "jetbrains-idea"
                      :source {:json "https://example.com/releases" :version [:IIU 0 :version]}
                      :tag-prefix "" :asset "idea-{version}.tar.gz" :url "https://example.com/{asset}"}))

(defn- serving [body]
  {:get-text (fn [url headers]
               (is (= "https://example.com/releases" url))
               (is (= "application/json" (get headers "Accept")))
               body)})

(deftest json-sources-read-the-version-at-their-path
  (is (= {:tag "2026.2.3" :version "2026.2.3"}
         (source/latest idea (serving "{\"IIU\":[{\"version\":\"2026.2.3\",\"build\":\"262.1\"}]}")))))

(deftest numbers-and-string-keys-work-too
  (let [pkg (package/normalize {:name "x" :source {:json "https://example.com/releases" :version ["latest" "build"]}
                                :tag-prefix "" :asset "x" :url "https://example.com/{asset}"})]
    (is (= "262" (:version (source/latest pkg (serving "{\"latest\":{\"build\":262}}")))))))

(defn- problem [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest missing-versions-name-how-far-the-path-got
  (testing "a missing key"
    (let [data (problem #(source/latest idea (serving "{\"IIC\":[{\"version\":\"1\"}]}")))]
      (is (= :json-path (:breto/problem data)))
      (is (= [] (:found data)))
      (is (= ["IIC"] (:keys data)))))
  (testing "an index past the end"
    (let [data (problem #(source/latest idea (serving "{\"IIU\":[]}")))]
      (is (= [:IIU] (:found data)))
      (is (= ["0 entries"] (:keys data)))))
  (testing "a value that is no version"
    (let [data (problem #(source/latest idea (serving "{\"IIU\":[{\"version\":{\"major\":1}}]}")))]
      (is (= [:IIU 0 :version] (:found data)))
      (is (= ["major"] (:keys data)))))
  (testing "the diagnosis shows the document's keys"
    (let [d (diagnose/diagnose (ex-info "x" (problem #(source/latest idea (serving "{\"IIC\":[]}")))) {})]
      (is (some #(str/includes? % "Keys there: IIC") (:facts d)))
      (is (some #(str/includes? % "jq") (:advice d))))))

(deftest json-sources-need-a-version-path-and-a-url
  (is (thrown? clojure.lang.ExceptionInfo
               (package/normalize {:name "x" :source {:json "https://example.com"} :asset "x" :url "u"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (package/normalize {:name "x" :source {:json "https://example.com" :version [:a]} :asset "x"}))))
