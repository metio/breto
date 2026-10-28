;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.lockfile-test
  (:require [babashka.fs :as fs]
            [breto.diagnose :as diagnose]
            [breto.lockfile :as lockfile]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each s/with-root)

(deftest headers-survive-a-round-trip
  (let [text ";; SPDX-FileCopyrightText: The dotfiles Authors\n;; SPDX-License-Identifier: 0BSD\n\n{\"b\" {:version \"2\"}\n \"a\" {:version \"1\" :sha256 \"ab\"}}\n"
        lock (lockfile/parse text)]
    (is (= {"a" {:version "1" :sha256 "ab"} "b" {:version "2"}} (:entries lock)))
    (is (= (str ";; SPDX-FileCopyrightText: The dotfiles Authors\n;; SPDX-License-Identifier: 0BSD\n\n"
                "{\"a\" {:version \"1\" :sha256 \"ab\"}\n \"b\" {:version \"2\"}}\n")
           (lockfile/render lock)))))

(deftest empty-and-missing-lock-files-hold-nothing
  (is (= {} (:entries (lockfile/parse ""))))
  (is (= {} (:entries (lockfile/read-lock (path "missing.lock")))))
  (is (= "{}\n" (lockfile/render {:header "" :entries {}}))))

(deftest unchanged-lock-files-are-not-rewritten
  (let [file (path "breto.lock")
        config {:store (path "store") :packages ["absent"]}]
    (spit file "{\"absent\" {:version \"1\"}}\n")
    (fs/set-last-modified-time file (java.nio.file.attribute.FileTime/fromMillis 0))
    (lockfile/write! (str file) config)
    (is (zero? (.toMillis (fs/last-modified-time file))))
    (lockfile/write! (str file) (assoc config :packages []))
    (is (= "{}\n" (slurp (str file))))))

(deftest broken-lock-files-are-diagnosed
  (spit (path "broken.lock") "{\"a\" ")
  (let [e (try (lockfile/read-lock (path "broken.lock")) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (= :lockfile-format (:breto/problem (ex-data e))))
    (is (str/includes? (:summary (diagnose/diagnose e {})) "cannot be read"))))

(defn- kind-world []
  (let [binary (s/file! "kind" "#!/bin/sh\necho kind\n")
        url "https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-linux-amd64"]
    (s/define! "kind" {:source {:github "kubernetes-sigs/kind"} :asset "kind-linux-amd64"
                       :checksum "{asset}.sha256sum"})
    {:binary binary
     :io (s/fake-io {url binary}
                    {"https://github.com/kubernetes-sigs/kind/releases/latest" "{\"tag_name\":\"v0.33.0\"}"
                     (str url ".sha256sum") (str (s/sha256 binary) "  kind-linux-amd64\n")}
                    (atom []))}))

(deftest updates-record-versions-and-hashes
  (let [{:keys [binary io]} (kind-world)]
    (spit (path "breto.lock") ";; header\n{}\n")
    (s/breto! {:packages ["kind" "absent"] :lockfile (path "breto.lock")} ["update"] {:io io})
    (is (= (str ";; header\n{\"kind\" {:version \"0.33.0\" :sha256 \"" (s/sha256 binary) "\" :asset \"kind-linux-amd64\"}}\n")
           (slurp (path "breto.lock"))))))

(deftest locked-updates-install-the-locked-version-and-hash
  (let [{:keys [binary io]} (kind-world)]
    (spit (path "breto.lock") (str "{\"kind\" {:version \"0.33.0\" :sha256 \"" (s/sha256 binary) "\"}}"))
    (let [{:keys [code out]} (s/breto! {:packages ["kind"] :lockfile (path "breto.lock")} ["update" "--locked"] {:io io})]
      (is (= 0 code))
      (is (str/includes? out "[OK] kind 0.33.0 installed")))))

(deftest locked-hashes-catch-replaced-assets
  (let [{:keys [io]} (kind-world)]
    (spit (path "breto.lock") (str "{\"kind\" {:version \"0.33.0\" :sha256 \"" (apply str (repeat 64 "0")) "\"}}"))
    (let [{:keys [code out]} (s/breto! {:packages ["kind"] :lockfile (path "breto.lock")} ["update" "--locked"] {:io io})]
      (is (= 1 code))
      (is (str/includes? out "differs from the one the lock file recorded"))
      (is (not (fs/exists? (path "bin" "kind")))))))

(deftest locked-updates-need-an-entry-and-a-file
  (let [{:keys [io]} (kind-world)]
    (let [{:keys [out]} (s/breto! {:packages ["kind"] :lockfile (path "breto.lock")} ["update" "--locked"] {:io io})]
      (is (str/includes? out "--locked found no lock file")))
    (spit (path "breto.lock") "{}")
    (let [{:keys [out]} (s/breto! {:packages ["kind"] :lockfile (path "breto.lock")} ["update" "--locked"] {:io io})]
      (is (str/includes? out "kind has no entry in the lock file")))
    (let [{:keys [out]} (s/breto! {:packages ["kind"]} ["update" "--locked"] {:io io})]
      (is (str/includes? out "the config names none")))))

(deftest snapshots-keep-entries-the-store-cannot-replace
  (let [cfg {:store (path "store")}]
    (fs/create-dirs (path "store" "old" "1.0"))
    (fs/create-sym-link (path "store" "old" "current") "1.0")
    (is (= {"old" {:version "1.0" :sha256 "kept"}
            "elsewhere" {:version "3.0" :sha256 "x"}}
           (lockfile/snapshot cfg ["old" "elsewhere" "unknown"]
                              {"old" {:version "1.0" :sha256 "kept"}
                               "elsewhere" {:version "3.0" :sha256 "x"}
                               "dropped" {:version "9"}})))
    (is (= {"old" {:version "1.0"}}
           (lockfile/snapshot cfg ["old"] {"old" {:version "0.9" :sha256 "stale"}})))))
