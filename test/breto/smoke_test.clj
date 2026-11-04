;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.smoke-test
  (:require [breto.proc :as proc]
            [breto.registry :as registry]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- world
  "alpha 1.0.0, a script printing its version and exiting with its first
  argument, defined with `tests`; `run` runs the tests."
  [tests run]
  (let [asset (s/file! "alpha" "#!/bin/sh\necho alpha 1.0.0 in \"$PWD\"\nexit \"${1:-0}\"\n")]
    (s/define! "alpha" (cond-> {:source {:github "acme/alpha"} :asset "alpha" :verify false}
                         tests (assoc :tests tests)))
    (assoc (s/fake-io {"https://github.com/acme/alpha/releases/download/v1.0.0/alpha" asset}
                      {"https://github.com/acme/alpha/releases/latest" "{\"tag_name\":\"v1.0.0\"}"}
                      (atom []))
           :run-hook run)))

(defn- breto-test [tests run]
  (binding [*err* (java.io.StringWriter.)]
    (s/breto! {} ["test" "alpha"] {:io (world tests run)})))

(deftest passing-tests-are-counted
  (let [{:keys [code out]} (breto-test [["alpha"] {:args ["alpha" "0"] :expect "alpha {version}"}] proc/run-bounded)]
    (is (= 0 code))
    (is (str/includes? out "[OK] alpha 1.0.0 passed its 2 tests"))
    (is (str/includes? out "breto test: 1 tested"))))

(deftest a-definition-without-tests-passes-once-installed
  (let [{:keys [code out]} (breto-test nil proc/run-bounded)]
    (is (= 0 code))
    (is (str/includes? out "[OK] alpha 1.0.0 installed; its definition has no tests"))))

(deftest a-test-exiting-non-zero-fails-with-its-output
  (let [{:keys [code out]} (breto-test [["alpha" "3"]] proc/run-bounded)]
    (is (= 1 code))
    (is (str/includes? out "[FAIL] alpha 1.0.0 (test)"))
    (is (str/includes? out "alpha 3 exited with 3"))
    (is (str/includes? out "Its output ends with: alpha 1.0.0 in"))))

(deftest a-test-missing-its-expected-text-fails
  (let [{:keys [code out]} (breto-test [{:args ["alpha"] :expect "alpha 2.0.0"}] proc/run-bounded)]
    (is (= 1 code))
    (is (str/includes? out "did not print \"alpha 2.0.0\""))))

(deftest a-test-running-past-its-timeout-fails
  (let [{:keys [code out]} (breto-test [{:args ["alpha"] :timeout 5}]
                                       (fn [_] {:exit nil :out "" :err "" :timed-out true}))]
    (is (= 1 code))
    (is (str/includes? out "ran longer than 5 seconds"))))

(deftest a-program-that-cannot-start-fails-the-test
  (let [{:keys [code out]} (breto-test [["no-such-program-anywhere"]] proc/run-bounded)]
    (is (= 1 code))
    (is (str/includes? out "could not start"))))

(deftest tests-run-the-installed-version-in-a-fresh-directory-with-the-bin-dir-on-path
  (let [seen (atom nil)
        {:keys [code]} (breto-test [["alpha" "--flag"]]
                                   (fn [inv] (reset! seen inv) {:exit 0 :out "" :err ""}))
        {:keys [command env dir timeout]} @seen]
    (is (= 0 code))
    (is (str/starts-with? (first command) (str (path "store" "alpha" "1.0.0"))))
    (is (= "--flag" (second command)))
    (is (str/starts-with? (get env "PATH") (str (path "bin"))))
    (is (str/includes? dir "breto-test-"))
    (is (= 60 timeout))))

(deftest validate-rejects-malformed-tests
  (doseq [tests [["alpha"] [[]] [{:args ["alpha"] :timeout 0}] [{:args ["alpha"] :unknown true}]]]
    (s/define! "alpha" {:source {:github "acme/alpha"} :asset "alpha" :tests tests})
    (is (some #(str/includes? % ":tests is a vector of tests") (registry/validate (str (path "packages"))))
        (pr-str tests))))
