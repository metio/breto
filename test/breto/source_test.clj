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

(defn- rolling [extra]
  (package/normalize (merge {:name "nightly" :source {:github "acme/nightly" :tag "nightly"}
                             :asset "nightly-{version}.tar.gz"}
                            extra)))

(defn- serving-builds [builds]
  {:get-text (fn [url _]
               (case url
                 "https://api.github.com/repos/acme/nightly/releases/tags/nightly"
                 (cheshire.core/generate-string {:tag_name "nightly" :assets builds})
                 "https://api.github.com/repos/acme/nightly/releases?per_page=100"
                 (cheshire.core/generate-string [{:tag_name "v1.4.0"}])))
   :github-token (constantly nil)})

(deftest a-skipped-rolling-build-falls-back-to-the-previous-build
  (let [io (serving-builds [{:name "nightly-20261028.tar.gz" :created_at "2026-10-28T08:00:00Z"}
                            {:name "nightly-20261027.tar.gz" :created_at "2026-10-27T08:00:00Z"}])]
    (is (= {:tag "nightly" :version "20261027"} (source/newest (rolling {:skip ["20261028"]}) io)))
    (is (= :all-skipped (:breto/problem (problem #(source/newest (rolling {:skip ["20261028" "20261027"]}) io)))))))

(deftest rolling-assets-may-not-use-vars
  (is (= :invalid-package
         (:breto/problem (problem #(rolling {:asset "nightly-{version}-{major}.zip" :vars {:major {:match "^\\d+"}}}))))))

(deftest a-text-match-whose-group-is-empty-finds-no-release
  (let [pkg (package/normalize {:name "x" :source {:text "https://example.com/v" :match "version (\\d[\\d.]*)|latest"}
                                :tag-prefix "" :asset "x" :url "https://example.com/{asset}"})]
    (is (= :no-release (:breto/problem (problem #(source/latest pkg {:get-text (constantly "latest")})))))))

(deftest fractional-json-numbers-are-refused
  (let [pkg (package/normalize {:name "x" :source {:json "https://example.com/releases" :version ["v"]}
                                :tag-prefix "" :asset "x" :url "https://example.com/{asset}"})]
    (is (= :json-path (:breto/problem (problem #(source/latest pkg (serving "{\"v\":1.10}"))))))))
