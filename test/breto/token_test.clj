;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.token-test
  (:require [babashka.fs :as fs]
            [breto.diagnose :as diagnose]
            [breto.github :as github]
            [breto.support :as s]
            [breto.token :as token]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- env [m] {:getenv #(get m %)})

(defn- script!
  "An executable shell script `body` under the test root, as a command."
  [name body]
  (let [file (s/path name)]
    (spit file (str "#!/bin/sh\n" body "\n"))
    (fs/set-posix-file-permissions file "rwxr-xr-x")
    [file]))

(defn- never-run []
  (script! "never" (str "touch " (s/path "ran"))))

(deftest environment-variables-win-over-the-command
  (let [command (never-run)]
    (is (= {:token "abc" :source "GITHUB_TOKEN"}
           (token/resolve-token command (env {"GITHUB_TOKEN" " abc\n" "GH_TOKEN" "def"}))))
    (is (= {:token "def" :source "GH_TOKEN"}
           (token/resolve-token command (env {"GITHUB_TOKEN" "" "GH_TOKEN" "def"}))))
    (is (= {:token "def" :source "GH_TOKEN"}
           (token/resolve-token command (env {"GITHUB_TOKEN" "  " "GH_TOKEN" "def"}))))
    (is (not (fs/exists? (s/path "ran"))))))

(deftest the-command-provides-the-first-line-of-its-output
  (let [command (into (script! "gopass" "[ \"$*\" = 'show -o github' ] || exit 9\nprintf 'ghp_123\\nlogin: me\\n'")
                      ["show" "-o" "github"])]
    (is (= {:token "ghp_123" :source ":token-command"}
           (token/resolve-token command (env {}))))))

(deftest no-variable-and-no-command-means-no-token
  (is (nil? (token/resolve-token nil (env {}))))
  (is (nil? (token/resolve-token [] (env {})))))

(defn- problem [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest failing-commands-are-reported-without-their-output
  (testing "a non-zero exit"
    (let [data (binding [*err* (java.io.StringWriter.)]
                 (problem #(token/resolve-token (script! "gh" "echo ghp_leaked; echo 'not logged in' >&2; exit 1") (env {}))))]
      (is (= :token-command (:breto/problem data)))
      (is (= 1 (:exit data)))
      (is (not (str/includes? (pr-str data) "ghp_leaked")) "the output of a failed command is never kept")
      (is (not (contains? data :token)))))
  (testing "empty output"
    (is (:empty (problem #(token/resolve-token (script! "blank" "printf '  \\n'") (env {}))))))
  (testing "a blank first line"
    (is (:empty (problem #(token/resolve-token (script! "late" "printf '\\nghp_123\\n'") (env {}))))))
  (testing "a real command that is missing"
    (let [data (problem #(token/resolve-token ["/nonexistent/breto-token"] (env {})))]
      (is (= :token-command (:breto/problem data)))
      (is (string? (:error data))))))

(deftest commands-that-hang-are-stopped
  (let [data (problem #(token/resolve-token (script! "pasejo" "sleep 30") (assoc (env {}) :timeout-ms 200)))]
    (is (= :token-command (:breto/problem data)))
    (is (= token/timeout (:timed-out data)))
    (is (some #(str/includes? % "stopped after 60 seconds")
              (:facts (diagnose/diagnose (ex-info "x" data) {}))))))

(deftest providers-run-the-command-once-even-when-it-fails
  (let [runs (s/path "runs")
        ok (token/provider (script! "ok" (str "echo x >> " runs "; echo t")) (env {}))]
    (is (= {:token "t" :source ":token-command"} (ok)))
    (ok)
    (is (= 1 (count (str/split-lines (slurp runs))))))
  (let [runs (s/path "failing-runs")
        failing (token/provider (script! "failing" (str "echo x >> " runs "; exit 2")) (env {}))]
    (is (thrown? clojure.lang.ExceptionInfo (failing)))
    (is (thrown? clojure.lang.ExceptionInfo (failing)))
    (is (= 1 (count (str/split-lines (slurp runs)))))))

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
