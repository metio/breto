;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.proc-test
  (:require [babashka.fs :as fs]
            [breto.proc :as proc]
            [breto.signature :as signature]
            [breto.support :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each s/with-root)

(deftest commands-run-as-separate-arguments
  (is (= {:exit 3 :out "a b|c\n" :err "oops\n"}
         (proc/run-command ["sh" "-c" "printf '%s|%s\\n' \"$1\" \"$2\"; echo oops >&2; exit 3" "sh" "a b" "c"]))))

(deftest bounded-commands-run-as-separate-arguments
  (is (= {:exit 0 :out "a b\n" :err ""}
         (proc/run-bounded {:command ["sh" "-c" "echo \"$1\"" "sh" "a b"] :env {} :timeout 10}))))

(deftest commands-in-a-directory-run-without-the-unset-variables
  (let [{:keys [exit out]} (proc/run-in {:command ["sh" "-c" "echo \"${HOME-none} $EXTRA\""] :env {"EXTRA" "x"}
                                         :unset ["HOME"] :dir (s/path)})]
    (is (= 0 exit))
    (is (= "none x\n" out))))

(defn- fake-cosign
  "A script standing in for cosign that records its arguments and exits with
  `exit`."
  [exit]
  (let [script (s/path "cosign")
        log (s/path "cosign.args")]
    (spit script (str "#!/bin/sh\nprintf '%s\\n' \"$@\" > " log "\nexit " exit "\n"))
    (fs/set-posix-file-permissions script "rwxr-xr-x")
    [(str script) log]))

(deftest signatures-are-checked-by-a-real-process
  (let [[cosign log] (fake-cosign 0)
        sig {:identity "https://example.test/release.yml@refs/heads/main" :issuer "https://issuer.test"}]
    (signature/verify! proc/run-command cosign sig "/tmp/file" "/tmp/bundle" {:signed "file"})
    (is (= ["verify-blob" "--bundle" "/tmp/bundle"
            "--certificate-identity" "https://example.test/release.yml@refs/heads/main"
            "--certificate-oidc-issuer" "https://issuer.test" "/tmp/file"]
           (str/split-lines (slurp (str log)))))
    (let [[cosign] (fake-cosign 1)
          e (try (signature/verify! proc/run-command cosign sig "/tmp/file" "/tmp/bundle" {:signed "file"}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :signature-invalid (:breto/problem (ex-data e)))))))
