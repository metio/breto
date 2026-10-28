;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.token-test
  (:require [breto.diagnose :as diagnose]
            [breto.proc]
            [breto.github :as github]
            [breto.token :as token]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- env [m] #(get m %))

(defn- never-run [_] (throw (AssertionError. "the command must not run")))

(deftest environment-variables-win-over-the-command
  (is (= {:token "abc" :source "GITHUB_TOKEN"}
         (token/resolve-token (env {"GITHUB_TOKEN" " abc\n" "GH_TOKEN" "def"}) ["gh" "auth" "token"] never-run)))
  (is (= {:token "def" :source "GH_TOKEN"}
         (token/resolve-token (env {"GITHUB_TOKEN" "" "GH_TOKEN" "def"}) nil never-run))))

(deftest the-command-provides-the-first-line-of-its-output
  (is (= {:token "ghp_123" :source ":token-command"}
         (token/resolve-token (env {}) ["gopass" "show" "-o" "github"]
                              (fn [command]
                                (is (= ["gopass" "show" "-o" "github"] command))
                                {:exit 0 :out "ghp_123\nlogin: me\n" :err ""})))))

(deftest no-variable-and-no-command-means-no-token
  (is (nil? (token/resolve-token (env {}) nil never-run)))
  (is (nil? (token/resolve-token (env {}) [] never-run))))

(defn- problem [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest failing-commands-are-reported-with-their-error-output
  (testing "a non-zero exit"
    (let [data (problem #(token/resolve-token (env {}) ["gh" "auth" "token"]
                                              (constantly {:exit 1 :out "" :err "not logged in\n"})))]
      (is (= :token-command (:breto/problem data)))
      (is (= "not logged in" (:stderr data)))
      (is (not (contains? data :token)))))
  (testing "empty output"
    (is (:empty (problem #(token/resolve-token (env {}) ["true"] (constantly {:exit 0 :out "  \n" :err ""}))))))
  (testing "a command that cannot start"
    (is (= :token-command (:breto/problem (problem #(token/resolve-token (env {}) ["nope"]
                                                                         (fn [_] (throw (java.io.IOException. "No such file")))))))))
  (testing "a real command that is missing"
    (is (= :token-command (:breto/problem (problem #(token/resolve-token (env {}) ["/nonexistent/breto-token"] breto.proc/run-command)))))))

(deftest providers-run-the-command-once-even-when-it-fails
  (let [runs (atom 0)
        ok (token/provider (env {}) ["x"] (fn [_] (swap! runs inc) {:exit 0 :out "t" :err ""}))]
    (ok) (ok)
    (is (= 1 @runs)))
  (let [runs (atom 0)
        failing (token/provider (env {}) ["x"] (fn [_] (swap! runs inc) {:exit 2 :out "" :err "locked"}))]
    (is (thrown? clojure.lang.ExceptionInfo (failing)))
    (is (thrown? clojure.lang.ExceptionInfo (failing)))
    (is (= 1 @runs))))

(deftest api-requests-carry-the-token
  (is (= "Bearer t" (get (github/api-headers {:github-token (constantly {:token "t" :source "x"})}) "Authorization")))
  (is (nil? (get (github/api-headers {:github-token (constantly nil)}) "Authorization")))
  (is (nil? (get (github/api-headers {}) "Authorization"))))

(deftest token-command-failures-are-explained
  (let [d (diagnose/diagnose (ex-info "x" {:breto/problem :token-command :command ["gh" "auth" "token"]
                                           :exit 1 :stderr "not logged in"})
                             {})]
    (is (some #(str/includes? % "not logged in") (:facts d)))
    (is (some #(str/includes? % "Run gh auth token yourself") (:advice d)))))
