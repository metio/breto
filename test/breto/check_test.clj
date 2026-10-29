;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.check-test
  (:require [breto.support :as s]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- github-world
  "acme/tool at v1.2.3 with `assets`, each a name or [name digest]."
  [assets]
  {(str "https://github.com/acme/tool/releases/latest") "{\"tag_name\":\"v1.2.3\"}"
   "https://api.github.com/repos/acme/tool/releases/tags/v1.2.3"
   (json/generate-string {:tag_name "v1.2.3"
                          :assets (for [a assets]
                                    (if (vector? a) {:name (first a) :digest (second a)} {:name a :digest nil}))})})

(defn- run-check [definition texts]
  (s/define! "tool" (merge {:source {:github "acme/tool"} :asset "tool-{version}-linux.tar.gz"} definition))
  (s/breto! {:packages ["tool"]} ["check"] {:io (s/fake-io {} texts (atom []))}))

(deftest digests-make-a-release-verifiable
  (let [{:keys [code out]} (run-check {} (github-world [["tool-1.2.3-linux.tar.gz" "sha256:abc"]]))]
    (is (= 0 code))
    (is (str/includes? out "[OK] tool 1.2.3 verifiable by its GitHub digest"))))

(deftest checksum-files-in-the-release-count
  (let [{:keys [code out]} (run-check {:checksum "SHA256SUMS"}
                                      (github-world ["tool-1.2.3-linux.tar.gz" "SHA256SUMS"]))]
    (is (= 0 code))
    (is (str/includes? out "verifiable by its checksum file"))))

(deftest missing-assets-suggest-the-closest-name
  (let [{:keys [code out]} (run-check {} (github-world ["tool-1.2.3-x86_64-linux.tar.gz" "tool-1.2.3-darwin.tar.gz"]))]
    (is (= 1 code))
    (is (str/includes? out "has no asset named tool-1.2.3-linux.tar.gz"))
    (is (str/includes? out "\"tool-{version}-x86_64-linux.tar.gz\""))))

(deftest releases-without-any-similar-asset-suggest-skipping
  (let [{:keys [out]} (run-check {} (github-world ["other.zip"]))]
    (is (str/includes? out ":skip [\"1.2.3\"]"))))

(deftest unverifiable-releases-suggest-a-history-exemption
  (let [{:keys [code out]} (run-check {} (github-world ["tool-1.2.3-linux.tar.gz" "tool_1.2.3_sha256.txt"]))]
    (is (= 1 code))
    (is (str/includes? out "Nothing can verify tool-1.2.3-linux.tar.gz"))
    (is (str/includes? out "Set :checksum to \"tool_{version}_sha256.txt\""))
    (is (str/includes? out "{:before \"1.2.4\" :verify false}"))))

(deftest exempt-releases-pass-and-say-so
  (let [{:keys [code out]} (run-check {:history [{:before "1.2.4" :verify false}]}
                                      (github-world ["tool-1.2.3-linux.tar.gz"]))]
    (is (= 0 code))
    (is (str/includes? out "installs unverified, as its definition says"))))

(deftest a-named-checksum-file-that-is-missing-fails
  (let [{:keys [code out]} (run-check {:checksum "SHA256SUMS"} (github-world [["tool-1.2.3-linux.tar.gz" "sha256:abc"]]))]
    (is (= 0 code) "the digest verifies it, so the missing file does not matter"))
  (let [{:keys [code out]} (run-check {:checksum "SHA256SUMS"} (github-world ["tool-1.2.3-linux.tar.gz"]))]
    (is (= 1 code))
    (is (str/includes? out "checksum file the package names does not exist"))))

(deftest assets-outside-github-are-checked-by-their-url
  (s/define! "tool" {:source {:text "https://example.com/stable.txt"} :tag-prefix ""
                     :asset "tool" :url "https://example.com/{version}/{asset}" :checksum "{asset}.sha256"})
  (let [texts {"https://example.com/stable.txt" "1.0\n" "https://example.com/1.0/tool.sha256" "abc"}
        files {"https://example.com/1.0/tool" (s/file! "tool" "x")}
        {:keys [code out]} (s/breto! {:packages ["tool"]} ["check"] {:io (s/fake-io files texts (atom []))})]
    (is (= 0 code))
    (is (str/includes? out "verifiable by its checksum file")))
  (let [{:keys [code out]} (s/breto! {:packages ["tool"]} ["check"]
                                     {:io (s/fake-io {} {"https://example.com/stable.txt" "1.0\n"} (atom []))})]
    (is (= 1 code))
    (is (str/includes? out "(download)"))))

(deftest check-all-covers-the-whole-registry
  (s/define! "a" {:source {:github "acme/a"} :asset "a"})
  (s/define! "b" {:source {:github "acme/b"} :asset "b"})
  (let [{:keys [events]} (s/breto! {:packages []} ["check" "--all"])]
    (is (= ["a" "b"] (keep #(when (= :start (:type %)) (:package %)) events)))))

(deftest a-signed-checksum-file-must-exist-despite-a-digest
  (let [sig {:of :checksum :bundle "SHA256SUMS.sigstore.json"
             :identity "https://github.com/acme/tool/.github/workflows/release.yaml@refs/tags/{tag}"
             :issuer "https://token.actions.githubusercontent.com"}
        {:keys [code out]} (run-check {:checksum "SHA256SUMS" :signature sig}
                                      (github-world [["tool-1.2.3-linux.tar.gz" "sha256:abc"] "SHA256SUMS.sigstore.json"]))]
    (is (= 1 code) out)
    (is (str/includes? out "checksum file the package names does not exist"))))
