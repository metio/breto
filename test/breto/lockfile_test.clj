;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.lockfile-test
  (:require [babashka.fs :as fs]
            [breto.diagnose :as diagnose]
            [breto.lockfile :as lockfile]
            [breto.store :as store]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

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

(defn- forget-hash!
  "Removes the recorded metadata of kind 0.33.0, as for a version installed
  before hashes were recorded."
  []
  (fs/delete (path "store" "kind" ".meta-0.33.0.edn")))

(deftest up-to-date-versions-without-a-hash-get-upstreams
  (let [{:keys [binary io]} (kind-world)
        config {:packages ["kind"] :lockfile (path "breto.lock")}]
    (s/breto! config ["update"] {:io io})
    (forget-hash!)
    (spit (path "breto.lock") "{}\n")
    (let [{:keys [code out]} (s/breto! config ["update"] {:io io})]
      (is (= 0 code))
      (is (str/includes? out "[OK] kind 0.33.0 is up to date; recorded upstream's hash for the lock file"))
      (is (str/includes? out "1 up to date")))
    (is (= {:sha256 (s/sha256 binary) :asset "kind-linux-amd64" :sha256-source :upstream}
           (read-string (slurp (path "store" "kind" ".meta-0.33.0.edn")))))
    (is (str/includes? (slurp (path "breto.lock")) (s/sha256 binary)))
    (testing "the next run has nothing left to record"
      (let [{:keys [out]} (s/breto! config ["update"] {:io io})]
        (is (not (str/includes? out "recorded upstream's hash")))))))

(deftest failed-hash-lookups-leave-the-update-alone
  (let [{:keys [io]} (kind-world)
        config {:packages ["kind"] :lockfile (path "breto.lock")}]
    (s/breto! config ["update"] {:io io})
    (forget-hash!)
    (let [offline (assoc io :get-text (fn [url headers]
                                        (if (str/ends-with? url "/latest")
                                          ((:get-text io) url headers)
                                          (throw (ex-info "GET returned 503" {:breto/problem :http-status :status 503})))))
          {:keys [code out]} (s/breto! config ["update"] {:io offline})]
      (is (= 0 code))
      (is (str/includes? out "1 up to date"))
      (is (not (fs/exists? (path "store" "kind" ".meta-0.33.0.edn")))))))

(deftest unverified-versions-get-the-hash-of-a-fresh-download
  (let [{:keys [binary io]} (kind-world)
        texts (atom [])
        downloads (atom [])
        config {:packages ["kind"] :lockfile (path "breto.lock")}
        counted (assoc io
                       :get-text (fn [url headers] (swap! texts conj url) ((:get-text io) url headers))
                       :download! (fn [url dest & more] (swap! downloads conj url) (apply (:download! io) url dest more)))]
    (s/breto! config ["update"] {:io io})
    (forget-hash!)
    (s/define! "kind" {:source {:github "kubernetes-sigs/kind"} :asset "kind-linux-amd64"
                       :checksum "{asset}.sha256sum" :verify false})
    (let [{:keys [code out]} (s/breto! config ["update"] {:io counted})]
      (is (= 0 code))
      (is (str/includes? out "recorded the hash of a fresh download for the lock file, since upstream publishes none")))
    (is (= ["https://github.com/kubernetes-sigs/kind/releases/latest"] @texts))
    (is (= ["https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-linux-amd64"] @downloads))
    (is (= {:sha256 (s/sha256 binary) :asset "kind-linux-amd64" :sha256-source :download}
           (read-string (slurp (path "store" "kind" ".meta-0.33.0.edn")))))
    (is (str/includes? (slurp (path "breto.lock")) (s/sha256 binary)))
    (is (empty? (filter #(str/starts-with? (str (fs/file-name %)) ".work-") (fs/list-dir (path "store" "kind")))))
    (testing "the next run downloads nothing"
      (reset! downloads [])
      (s/breto! config ["update"] {:io counted})
      (is (empty? @downloads)))
    (testing "a failed download leaves the update alone"
      (forget-hash!)
      (let [{:keys [code]} (s/breto! config ["update"] {:io (assoc io :download! (fn [& _] (throw (ex-info "GET returned 503" {:breto/problem :http-status :status 503}))))})]
        (is (= 0 code))
        (is (not (fs/exists? (path "store" "kind" ".meta-0.33.0.edn"))))))))

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

(deftest snapshots-record-module-sums
  (let [cfg {:store (path "store")}]
    (fs/create-dirs (path "store" "tool" "1.0"))
    (fs/create-sym-link (path "store" "tool" "current") "1.0")
    (store/write-meta! cfg "tool" "1.0" {:sum "h1:abc=" :asset "tool"})
    (is (= {"tool" {:version "1.0" :sum "h1:abc=" :asset "tool"}}
           (lockfile/snapshot cfg ["tool"] {})))))
