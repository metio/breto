;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.platform-test
  (:require [babashka.fs :as fs]
            [breto.lockfile :as lockfile]
            [breto.package :as package]
            [breto.platform :as platform]
            [breto.registry :as registry]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(def ^:private definition
  {:source {:github "acme/alpha"}
   :asset "alpha-linux-amd64"
   :verify false
   :platforms {"darwin-aarch64" {:asset "alpha-darwin-arm64"}
               "linux-aarch64" {:asset "alpha-linux-arm64"
                                :history [{:before "1.0.0" :asset "alpha-arm64"}]}}
   :history [{:before "1.0.0" :asset "alpha-amd64"}]})

(deftest the-platform-is-named-after-the-system-and-architecture
  (is (= "linux-x86_64" (platform/detect "Linux" "amd64")))
  (is (= "linux-aarch64" (platform/detect "Linux" "aarch64")))
  (is (= "darwin-aarch64" (platform/detect "Mac OS X" "aarch64")))
  (is (= "darwin-x86_64" (platform/detect "Mac OS X" "x86_64")))
  (is (= "linux-aarch64" (platform/current {:platform "linux-aarch64"}))))

(deftest a-platform-entry-overrides-the-top-level-at-every-version
  (let [at (fn [platform version]
             (:asset (package/at (package/normalize (assoc definition :name "alpha") platform) version)))]
    (is (= "alpha-linux-amd64" (at "linux-x86_64" "1.0.0")))
    (is (= "alpha-amd64" (at "linux-x86_64" "0.9.0")))
    (is (= "alpha-darwin-arm64" (at "darwin-aarch64" "1.0.0")))
    (testing "the platform's own history applies over the top level's"
      (is (= "alpha-linux-arm64" (at "linux-aarch64" "1.0.0")))
      (is (= "alpha-arm64" (at "linux-aarch64" "0.9.0"))))
    (testing "without the platform's history, the top level's history still applies"
      (is (= "alpha-darwin-arm64" (at "darwin-aarch64" "0.9.0"))))))

(deftest a-definition-without-the-platform-is-unsupported-there
  (let [pkg (package/normalize (assoc definition :name "alpha") "darwin-x86_64")]
    (is (:unsupported pkg))
    (is (= ["darwin-aarch64" "linux-aarch64" "linux-x86_64"] (:supported pkg))))
  (is (not (:unsupported (package/normalize (assoc definition :name "alpha") "linux-x86_64"))))
  (testing "sources built on the machine serve every platform"
    (is (not (:unsupported (package/normalize {:name "x" :source {:npm "x"}} "darwin-x86_64")))))
  (testing "ghcup serves Linux only"
    (is (not (:unsupported (package/normalize {:name "ghc" :source {:ghcup "ghc"}} "linux-aarch64"))))
    (is (:unsupported (package/normalize {:name "ghc" :source {:ghcup "ghc"}} "darwin-aarch64")))))

(deftest invalid-platform-entries-are-refused
  (doseq [platforms [{"windows-x86_64" {:asset "a.exe"}}
                     {"linux-aarch64" "a"}
                     {"linux-aarch64" {:source {:github "other/alpha"}}}
                     {"linux-aarch64" {:history [{:asset "a"}]}}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid package"
                          (package/normalize {:name "alpha" :source {:github "acme/alpha"} :platforms platforms}))
        (pr-str platforms))))

(deftest an-overlay-adds-platform-entries
  (s/define! "alpha" definition)
  (fs/create-dirs (path "overlay"))
  (spit (path "overlay" "alpha.edn") (pr-str {:platforms {"darwin-x86_64" {:asset "alpha-darwin-amd64"}}}))
  (let [load #(get (registry/load-registry nil [(path "packages") (path "overlay")] %) "alpha")]
    (is (= "alpha-darwin-amd64" (:asset (load "darwin-x86_64"))))
    (is (= "alpha-darwin-arm64" (:asset (load "darwin-aarch64"))))))

(defn- world []
  (let [assets (into {} (for [asset ["alpha-linux-amd64" "alpha-darwin-arm64" "alpha-linux-arm64"]]
                          [asset (s/file! asset (str "#!/bin/sh\necho " asset "\n"))]))]
    (s/define! "alpha" definition)
    (fs/create-dirs (path "shared"))
    {:hash #(s/sha256 (get assets %))
     :io (s/fake-io (into {} (for [[asset file] assets]
                               [(str "https://github.com/acme/alpha/releases/download/v1.0.0/" asset) file]))
                    {"https://github.com/acme/alpha/releases/latest" "{\"tag_name\":\"v1.0.0\"}"}
                    (atom []))}))

(defn- breto!
  "Runs breto with a store of the platform --platform names, as the machine
  of that platform would have its own."
  [io & args]
  (let [machine (or (second (drop-while #(not= "--platform" %) args)) "here")]
    (binding [*err* (java.io.StringWriter.)]
      (s/breto! {:registries [(path "packages") (path "shared")]
                 :store (path machine "store") :bin-dir (path machine "bin")}
                args {:io io}))))

(deftest install-takes-the-asset-of-the-platform
  (let [{:keys [io]} (world)
        {:keys [code out]} (breto! io "install" "alpha" "--platform" "darwin-aarch64")]
    (is (= 0 code) out)
    (is (= "#!/bin/sh\necho alpha-darwin-arm64\n" (slurp (path "darwin-aarch64" "bin" "alpha"))))))

(deftest install-refuses-a-platform-without-releases
  (let [{:keys [io]} (world)
        {:keys [code out]} (breto! io "install" "alpha" "--platform" "darwin-x86_64")]
    (is (= 1 code))
    (is (str/includes? out "alpha has no release for darwin-x86_64"))
    (is (str/includes? out ":platforms entry"))))

(deftest lock-pins-the-download-of-every-named-platform
  (let [{:keys [io hash]} (world)
        lock-path (path "shared" "breto.lock")]
    (spit (path "shared" "@tools.edn") (pr-str {:members ["alpha@1.0.0"]}))
    (let [{:keys [code out]} (breto! io "lock" (path "shared") "--platforms" "all" "--platform" "linux-x86_64")]
      (is (= 0 code) out))
    (is (= {"alpha@1.0.0" {:sha256 (hash "alpha-linux-amd64") :asset "alpha-linux-amd64"
                           :platforms {"darwin-aarch64" {:sha256 (hash "alpha-darwin-arm64") :asset "alpha-darwin-arm64"}
                                       "linux-aarch64" {:sha256 (hash "alpha-linux-arm64") :asset "alpha-linux-arm64"}}}}
           (:entries (lockfile/read-lock lock-path))))
    (testing "a lock of one platform keeps the hashes of the others"
      (is (= 0 (:code (breto! io "lock" (path "shared") "--check" "--platform" "linux-x86_64")))))
    (testing "an install on another platform checks its own hash"
      (let [{:keys [code out]} (breto! io "install" "alpha@1.0.0" "--platform" "darwin-aarch64")]
        (is (= 0 code) out))
      (spit lock-path (str/replace (slurp lock-path) (hash "alpha-linux-arm64") (apply str (repeat 64 "0"))))
      (let [{:keys [code out]} (breto! io "install" "alpha@1.0.0" "--platform" "linux-aarch64")]
        (is (= 1 code))
        (is (str/includes? out "[FAIL] alpha"))))
    (testing "a changed download of another platform fails the lock"
      (is (= 1 (:code (breto! io "lock" (path "shared") "--platforms" "linux-aarch64" "--platform" "linux-x86_64")))))))

(deftest lock-entries-hold-each-platform-in-its-place
  (let [linux (package/normalize (assoc definition :name "alpha") "linux-x86_64")
        mac (package/normalize (assoc definition :name "alpha") "darwin-aarch64")
        npm (package/normalize {:name "x" :source {:npm "x"}} "darwin-aarch64")
        entry (-> {:version "1.0.0"}
                  (lockfile/with-hashes linux {:sha256 "aa"})
                  (lockfile/with-hashes mac {:sha256 "bb"}))]
    (is (= {:version "1.0.0" :sha256 "aa" :platforms {"darwin-aarch64" {:sha256 "bb"}}} entry))
    (is (= {:sha256 "bb"} (lockfile/hashes entry mac)))
    (is (= {:version "1.0.0" :sha256 "aa"} (lockfile/hashes entry linux)))
    (testing "a package built on the machine has one entry for all"
      (is (= {:sha256 "cc"} (lockfile/hashes {:sha256 "cc"} npm))))
    (testing "the rendered entry reads back the same"
      (is (= {"alpha" entry}
             (:entries (lockfile/parse (lockfile/render {:header "" :entries {"alpha" entry}}))))))))
