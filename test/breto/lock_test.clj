;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.lock-test
  (:require [babashka.fs :as fs]
            [breto.lockfile :as lockfile]
            [breto.platform :as platform]
            [breto.registry :as registry]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- world []
  (let [assets (into {} (for [version ["0.9.0" "1.0.0"]]
                          [version (s/file! (str "alpha-" version) (str "#!/bin/sh\necho alpha " version "\n"))]))]
    (s/define! "alpha" {:source {:github "acme/alpha"} :asset "alpha" :verify false})
    (fs/create-dirs (path "shared"))
    {:hash #(s/sha256 (get assets %))
     :io (s/fake-io (into {} (for [[version file] assets]
                               [(str "https://github.com/acme/alpha/releases/download/v" version "/alpha") file]))
                    {"https://github.com/acme/alpha/releases/latest" "{\"tag_name\":\"v1.0.0\"}"}
                    (atom []))}))

(defn- here
  "A lock entry holding `hashes` for the platform running the tests."
  [hashes]
  {:platforms {(platform/detect) hashes}})

(defn- group! [members]
  (spit (path "shared" "@toolchain.edn") (pr-str {:members members})))

(defn- config []
  {:registries [(path "packages") (path "shared")] :env-dir (path "envs")})

(defn- breto! [io & args]
  (binding [*err* (java.io.StringWriter.)]
    (s/breto! (config) args {:io io})))

(deftest lock-records-the-hash-of-every-pinned-version
  (let [{:keys [io hash]} (world)]
    (group! ["alpha@0.9.0"])
    (let [{:keys [code out]} (breto! io "lock" (path "shared"))]
      (is (= 0 code) out))
    (is (= {"alpha@0.9.0" (here {:sha256 (hash "0.9.0") :asset "alpha"})}
           (:entries (lockfile/read-lock (path "shared" "breto.lock")))))
    (testing "the pinned version is installed beside, not activated"
      (is (not (fs/exists? (path "bin" "alpha")))))
    (testing "--check passes while the file is current"
      (is (= 0 (:code (breto! io "lock" (path "shared") "--check")))))
    (testing "a new pin makes --check fail and lock replace the entry"
      (group! ["alpha@1.0.0"])
      (is (= 1 (:code (breto! io "lock" (path "shared") "--check"))))
      (is (= 0 (:code (breto! io "lock" (path "shared")))))
      (is (= {"alpha@1.0.0" (here {:sha256 (hash "1.0.0") :asset "alpha"})}
             (:entries (lockfile/read-lock (path "shared" "breto.lock"))))))))

(deftest a-locked-hash-must-match
  (let [{:keys [io]} (world)]
    (group! ["alpha@0.9.0"])
    (spit (path "shared" "breto.lock") (pr-str {"alpha@0.9.0" (here {:sha256 (apply str (repeat 64 "0")) :asset "alpha"})}))
    (fs/create-dirs (path "app"))
    (spit (path "app" ".breto.edn") (pr-str {:packages ["@toolchain"]}))
    (let [{:keys [code out]} (breto! io "lock" (path "shared"))]
      (is (= 1 code))
      (is (str/includes? out "alpha")))
    (is (not= 0 (:code (breto! io "env" "--dir" (path "app")))))
    (testing "the wrong entry is not replaced"
      (is (= (apply str (repeat 64 "0")) (get-in (lockfile/read-lock (path "shared" "breto.lock")) [:entries "alpha@0.9.0" :platforms (platform/detect) :sha256]))))))

(deftest a-pinned-version-missing-from-the-lock-warns-or-fails
  (let [{:keys [io]} (world)]
    (group! ["alpha@0.9.0"])
    (spit (path "shared" "breto.lock") (pr-str {"alpha@1.0.0" (here {:sha256 "x"})}))
    (fs/create-dirs (path "app"))
    (spit (path "app" ".breto.edn") (pr-str {:packages ["@toolchain"]}))
    (testing ":require-locked refuses it"
      (let [err (java.io.StringWriter.)
            {:keys [code]} (binding [*err* err]
                             (s/breto! (assoc (config) :require-locked true) ["env" "--dir" (path "app")] {:io io}))]
        (is (= 1 code))
        (is (str/includes? (str err) "is in no lock file"))))
    (testing "otherwise it installs with a warning"
      (let [err (java.io.StringWriter.)
            {:keys [code]} (binding [*err* err]
                             (s/breto! (config) ["env" "--dir" (path "app")] {:io io}))]
        (is (= 0 code))
        (is (str/includes? (str err) "[WARN] alpha: 0.9.0 is in no lock file of the registry directories"))))))

(deftest a-machine-lock-entry-without-a-hash-keeps-the-registry-lock
  (let [{:keys [io]} (world)]
    (group! ["alpha@0.9.0"])
    (spit (path "shared" "breto.lock") (pr-str {"alpha@0.9.0" (here {:sha256 (apply str (repeat 64 "0")) :asset "alpha"})}))
    (spit (path "machine.lock") (pr-str {"alpha" {:version "0.9.0"}}))
    (let [{:keys [code out]} (binding [*err* (java.io.StringWriter.)]
                               (s/breto! (assoc (config) :packages ["alpha"] :lockfile (path "machine.lock")) ["sync"] {:io io}))]
      (is (= 1 code))
      (is (str/includes? out "[FAIL] alpha")))
    (is (not (fs/exists? (path "bin" "alpha"))))))

(deftest an-installed-version-must-match-its-locked-hash
  (let [{:keys [io]} (world)]
    (group! ["alpha@0.9.0"])
    (breto! io "install" "alpha@0.9.0")
    (spit (path "shared" "breto.lock") (pr-str {"alpha@0.9.0" (here {:sha256 (apply str (repeat 64 "0")) :asset "alpha"})}))
    (let [before (slurp (path "shared" "breto.lock"))
          {:keys [code out]} (breto! io "lock" (path "shared"))]
      (is (= 1 code) out)
      (is (= before (slurp (path "shared" "breto.lock")))))))

(deftest lock-passes-over-the-groups-built-into-breto
  (let [{:keys [io hash]} (world)]
    (group! ["alpha@0.9.0"])
    (with-redefs [registry/builtin (constantly {"@built-in.edn" (pr-str {:members ["alpha@1.0.0"]})})]
      (let [{:keys [code out]} (breto! io "lock" (path "shared"))]
        (is (= 0 code) out)))
    (is (= {"alpha@0.9.0" (here {:sha256 (hash "0.9.0") :asset "alpha"})}
           (:entries (lockfile/read-lock (path "shared" "breto.lock")))))))
