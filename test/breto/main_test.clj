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
