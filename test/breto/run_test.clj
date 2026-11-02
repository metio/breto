;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.run-test
  (:require [babashka.fs :as fs]
            [breto.run :as run]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- program-with-head? [& head]
  (let [file (fs/create-temp-file)]
    (try
      (fs/write-bytes file (byte-array (map unchecked-byte head)))
      (run/program? file)
      (finally (fs/delete file)))))

(deftest scripts-and-native-binaries-are-programs
  (is (program-with-head? 0x23 0x21 0x2f 0x62))
  (is (program-with-head? 0x23 0x21))
  (is (program-with-head? 0x7f 0x45 0x4c 0x46 0x02))
  (is (program-with-head? 0xcf 0xfa 0xed 0xfe))
  (is (program-with-head? 0xca 0xfe 0xba 0xbe)))

(deftest data-is-no-program
  (is (not (program-with-head? 0x50 0x4b 0x03 0x04)))
  (is (not (program-with-head? 0x23)))
  (is (not (program-with-head?))))

(deftest limits-become-the-properties-of-a-scope
  (is (= ["systemd-run" "--user" "--scope" "--quiet" "--expand-environment=no" "--unit=breto-x-1"
          "--property" "CPUQuota=150%" "--property" "MemoryMax=14G" "--property" "MemorySwapMax=0"
          "--property" "IOWeight=50" "--" "/bin/test" "a"]
         (run/limited ["/bin/test" "a"] "breto-x-1" {:cpus 1.5 :memory "14G" :io-weight 50})))
  (is (= ["CPUQuota=600%"] (run/scope-properties {:cpus 6})))
  (is (re-matches #"breto-ci-stories_x-[0-9a-f]{8}" (run/scope-name "ci-stories x"))))

(deftest limits-need-a-user-manager-and-stay-off-on-ci
  (let [runtime (fs/create-temp-dir)
        all #(when (#{"systemd-run" "systemctl"} %) %)]
    (try
      (is (= :unavailable (run/limits-mode {"XDG_RUNTIME_DIR" (str runtime)} all (constantly 259))))
      (fs/create-file (fs/path runtime "bus"))
      (is (= :on (run/limits-mode {"XDG_RUNTIME_DIR" (str runtime)} all (constantly 259))))
      (is (= :unavailable (run/limits-mode {"XDG_RUNTIME_DIR" (str runtime)} all (constantly 253))))
      (is (= :unavailable (run/limits-mode {"XDG_RUNTIME_DIR" (str runtime)} all (constantly nil))))
      (is (= :ci (run/limits-mode {"XDG_RUNTIME_DIR" (str runtime) "CI" "true"} all (constantly 259))))
      (is (= :unavailable (run/limits-mode {"XDG_RUNTIME_DIR" (str runtime)} (constantly nil) (constantly 259))))
      (is (= :unavailable (run/limits-mode {} all (constantly 259))))
      (finally (fs/delete-tree runtime)))))

(defn- scopes? []
  (= :on (run/limits-mode #(System/getenv %) #(some-> (fs/which %) str))))

(deftest a-limited-command-runs-in-a-capped-scope
  (if-not (scopes?)
    (println "skipping a-limited-command-runs-in-a-capped-scope: no user systemd manager, or CI")
    (let [dir (fs/create-temp-dir)
          out (fs/path dir "out")]
      (try
        (is (= 0 (run/exec! {:command ["sh" "-c" (str "cat /sys/fs/cgroup$(cut -d: -f3 /proc/self/cgroup)/memory.max > " out
                                                      "; echo \"${CAPPED} ${#CAPPED}\" >> " out)]
                             :env {"CAPPED" "yes"} :dir dir :name "probe" :limits {:memory "64M"}})))
        (is (= ["67108864" "yes 3"] (str/split-lines (slurp (str out)))))
        (testing "the kernel stopping it for its memory is reported"
          (let [err (java.io.StringWriter.)
                code (binding [*err* err]
                       (run/exec! {:command ["sh" "-c" "x=$(head -c 200000000 /dev/zero | tr '\\0' a); echo ${#x}"]
                                   :env {} :dir dir :name "hungry" :limits {:memory "32M"}}))]
            (is (not= 0 code))
            (is (str/includes? (str err) "the command hungry ran out of memory under its :limits of 32M"))))
        (finally (fs/delete-tree dir))))))
