;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.main-test
  (:require [babashka.fs :as fs]
            [breto.main :as main]
            [clojure.test :refer [deftest is]]))

(deftest run-and-exec-pass-on-the-exit-code-of-the-program
  (is (= 7 (main/exit-code "run" 7)))
  (is (= 7 (main/exit-code "exec" 7)))
  (is (= 1 (main/exit-code "install" 7)) "other commands fail with 1")
  (is (= 0 (main/exit-code "run" 0)))
  (is (= 1 (main/exit-code "run" 300)) "a code beyond what a process can exit with stays a failure")
  (is (= 3 (main/exit-code "env" :breto.main/missing))))

(deftest a-misspelled-option-is-refused
  (let [problem (try (main/parse ["update" "--dry-rnu"]) nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :usage (:breto/problem problem))))
  (is (= {:dry-run true :verify false :debug true :dir "x"}
         (:opts (main/parse ["update" "--dry-run" "--no-verify" "-d" "--dir" "x"]))))
  (is (= ["run" "lint" "--fix"] (:args (main/parse ["run" "lint" "--fix"])))
      "breto run leaves the options of the command it runs alone"))

(deftest an-unknown-platform-is-refused
  (let [config (str (fs/create-temp-file {:suffix ".edn"}))
        problem #(try (main/context %) nil (catch clojure.lang.ExceptionInfo e (:breto/problem (ex-data e))))]
    (spit config "{}")
    (is (= :usage (problem {:config config :platform "linux-amd64"})))
    (spit config "{:platform \"windows-x86_64\"}")
    (is (= :usage (problem {:config config})))
    (is (= "darwin-aarch64" (get-in (main/context {:config config :platform "darwin-aarch64"}) [:config :platform]))
        "--platform replaces the configured one")))

(deftest a-dry-run-is-refused-where-it-would-change-the-store
  (doseq [command ["update" "install" "lock" "use"]]
    (is (= :usage (try (main/refuse-unsupported! command {:dry-run true}) nil
                       (catch clojure.lang.ExceptionInfo e (:breto/problem (ex-data e)))))
        command))
  (doseq [command ["gc" "uninstall" "sync"]]
    (is (nil? (main/refuse-unsupported! command {:dry-run true})) command))
  (is (nil? (main/refuse-unsupported! "update" {}))))

(deftest breto-update-dry-run-exits-before-touching-anything
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has no --dry-run"
        (main/run ["update" "--dry-run"] {:io (fn [& _] (throw (ex-info "network used" {})))}))))
