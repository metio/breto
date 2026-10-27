;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.checksum-test
  (:require [babashka.fs :as fs]
            [breto.checksum :as checksum]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private sha256-of-hello
  "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")

(deftest expected-finds-the-asset-in-a-listing
  (is (= "aa" (subs (checksum/expected (str (apply str (repeat 64 "a")) "  tool.tar.gz\n"
                                            (apply str (repeat 64 "b")) "  other.tar.gz\n")
                                       "tool.tar.gz")
                    0 2))))

(deftest expected-accepts-binary-markers-and-paths
  (is (some? (checksum/expected (str sha256-of-hello " *./dist/tool") "tool"))))

(deftest expected-accepts-a-bare-hash
  (is (= sha256-of-hello (checksum/expected (str sha256-of-hello "\n") "anything"))))

(deftest expected-takes-the-only-entry-under-any-name
  (is (= sha256-of-hello (checksum/expected (str sha256-of-hello " mc.RELEASE.2025") "mc.linux-amd64.RELEASE.2025"))))

(deftest expected-is-nil-when-a-listing-lacks-the-asset
  (is (nil? (checksum/expected (str sha256-of-hello "  other\n" sha256-of-hello "  another") "tool"))))

(deftest verify-checks-the-digest
  (let [file (fs/create-temp-file)]
    (try
      (spit (str file) "hello")
      (checksum/verify! file "tool" (str/upper-case sha256-of-hello))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"checksum mismatch"
                            (checksum/verify! file "tool" (apply str (repeat 64 "0")))))
      (finally (fs/delete file)))))

(deftest verify-picks-the-algorithm-from-the-hash-length
  (let [file (fs/create-temp-file)]
    (try
      (spit (str file) "hello")
      (checksum/verify! file "tool" (checksum/digest "SHA-512" file))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unrecognised checksum"
                            (checksum/verify! file "tool" "abc")))
      (finally (fs/delete file)))))
