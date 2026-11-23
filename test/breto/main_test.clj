;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.main-test
  (:require [breto.main :as main]
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
