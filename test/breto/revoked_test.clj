;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.revoked-test
  (:require [babashka.fs :as fs]
            [breto.diagnose :as diagnose]
            [breto.doctor :as doctor]
            [breto.install :as install]
            [breto.lockfile :as lockfile]
            [breto.package :as package]
            [breto.registry :as registry]
            [breto.store :as store]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(def ^:private reason "Release binaries published by an attacker; they steal credentials.")

(def ^:private versions ["0.8.0" "0.9.0" "1.0.0"])

(defn- define!
  "Defines alpha with `revoked` as its :revoked map."
  [revoked]
  (s/define! "alpha" (cond-> {:source {:github "acme/alpha"} :asset "alpha" :verify false}
                       (seq revoked) (assoc :revoked revoked))))

(defn- download-url [version]
  (str "https://github.com/acme/alpha/releases/download/v" version "/alpha"))

(defn- world
  "Package alpha with releases 0.8.0, 0.9.0 and 1.0.0, the latest, revoked
  as `revoked` says, the hash of every asset, and the URLs asked for."
  [revoked]
  (let [assets (into {} (for [version versions]
                          [version (s/file! (str "alpha-" version) (str "#!/bin/sh\necho alpha " version "\n"))]))
        requests (atom [])]
    (define! revoked)
    {:hash #(s/sha256 (get assets %))
     :requests requests
     :io (s/fake-io (into {} (for [[version file] assets] [(download-url version) file]))
                    {"https://github.com/acme/alpha/releases/latest" "{\"tag_name\":\"v1.0.0\"}"
                     "https://api.github.com/repos/acme/alpha/releases?per_page=100"
                     (str "[" (str/join "," (for [v versions] (str "{\"tag_name\":\"v" v "\"}"))) "]")}
                    requests)}))

(defn- breto! [config io & args]
  (binding [*err* (java.io.StringWriter.)]
    (s/breto! config args {:io io})))

(defn- downloaded? [requests version]
  (some #{(download-url version)} @requests))

(defn- problem [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- active []
  (when (fs/exists? (path "bin" "alpha"))
    (slurp (path "bin" "alpha"))))

(deftest revoked-maps-versions-to-reasons
  (is (= reason (package/revocation (s/normalize {:name "alpha" :source {:github "acme/alpha"} :asset "alpha"
                                                  :revoked {"1.0.0" reason}})
                                    "1.0.0")))
  (doseq [bad [["1.0.0"] {"1.0.0" ""} {"1.0.0" nil} {:v1 reason}]]
    (is (= :invalid-package (:breto/problem (problem #(s/normalize {:name "alpha" :source {:github "acme/alpha"}
                                                                   :asset "alpha" :revoked bad}))))
        (pr-str bad))))

(deftest revocations-merge-across-platforms-history-and-overlays
  (let [pkg (s/normalize {:name "alpha" :source {:github "acme/alpha"} :asset "alpha"
                          :revoked {"1.0.0" reason}
                          :platforms {"linux-x86_64" {:revoked {"0.9.0" "bad build"}}}
                          :history [{:before "0.5.0" :revoked {"0.4.0" "old"}}]}
                         "linux-x86_64")]
    (is (= reason (package/revocation pkg "1.0.0")))
    (is (= "bad build" (package/revocation pkg "0.9.0")))
    (is (= "old" (package/revocation pkg "0.4.0")))
    (is (nil? (package/revocation pkg "0.8.0"))))
  (define! {"1.0.0" reason})
  (fs/create-dirs (path "overlay"))
  (spit (path "overlay" "alpha.edn") (pr-str {:revoked {"0.9.0" "bad build"}}))
  (let [pkg (get (s/load-registry [(path "packages") (path "overlay")]) "alpha")]
    (is (= reason (package/revocation pkg "1.0.0")))
    (is (= "bad build" (package/revocation pkg "0.9.0")))))

(deftest a-revoked-latest-release-falls-back-to-the-newest-other-one
  (let [{:keys [io requests]} (world {"1.0.0" reason})
        {:keys [code out]} (breto! {} io "install" "alpha")]
    (is (= 0 code) out)
    (is (str/includes? out "[OK] alpha 0.9.0 installed"))
    (is (not (downloaded? requests "1.0.0")))))

(deftest revoking-every-release-is-explained
  (let [{:keys [io]} (world (zipmap versions (repeat reason)))
        data (problem #(install/resolve-release (get (s/load-registry [(path "packages")]) "alpha") io nil))]
    (is (= :all-skipped (:breto/problem data)))
    (is (= versions (:revoked data)))
    (is (str/includes? (:summary (diagnose/explain data {})) ":revoked"))))

(deftest an-explicit-revoked-version-is-refused-before-its-download
  (let [{:keys [io requests]} (world {"1.0.0" reason})
        {:keys [code out]} (breto! {} io "install" "alpha@1.0.0")]
    (is (= 1 code))
    (is (str/includes? out "[FAIL] alpha 1.0.0"))
    (is (str/includes? out reason))
    (is (not (downloaded? requests "1.0.0")))
    (is (not (fs/exists? (path "store" "alpha" "1.0.0"))))))

(deftest the-refusal-is-explained
  (let [{:keys [summary facts advice]} (diagnose/explain {:breto/problem :revoked :package "alpha" :version "1.0.0" :reason reason} {})]
    (is (str/includes? summary "alpha 1.0.0 is revoked"))
    (is (= [(str "Reason: " reason)] facts))
    (is (some #(str/includes? % "another version") advice))
    (is (some #(str/includes? % ":pins") advice))))

(deftest update-refuses-a-revoked-pin
  (let [{:keys [io requests]} (world {"0.9.0" reason})
        {:keys [code out]} (breto! {:packages ["alpha"] :pins {"alpha" "0.9.0"}} io "update")]
    (is (= 1 code))
    (is (str/includes? out "[FAIL] alpha 0.9.0"))
    (is (not (downloaded? requests "0.9.0")))))

(deftest a-group-pinning-a-revoked-version-installs-nothing-of-it
  (let [{:keys [io requests]} (world {"0.9.0" reason})]
    (spit (path "packages" "@tools.edn") (pr-str {:members ["alpha@0.9.0"]}))
    (let [{:keys [code out]} (breto! {} io "install" "@tools")]
      (is (= 1 code))
      (is (str/includes? out "[FAIL] alpha 0.9.0")))
    (is (not (downloaded? requests "0.9.0")))
    (testing "validate reports the pin"
      (is (= [(str "the group @tools pins alpha@0.9.0, which is revoked: " reason)]
             (registry/validate (path "packages")))))))

(deftest lock-refuses-a-revoked-pin
  (let [{:keys [io requests]} (world {"0.9.0" reason})]
    (fs/create-dirs (path "shared"))
    (spit (path "shared" "@tools.edn") (pr-str {:members ["alpha@0.9.0"]}))
    (let [{:keys [code out]} (breto! {:registries [(path "packages") (path "shared")]} io "lock" (path "shared")
                                     "--platforms" "all")]
      (is (= 1 code))
      (is (str/includes? out reason)))
    (is (empty? @requests))
    (is (not (fs/exists? (path "shared" "breto.lock"))))))

(deftest a-project-pinning-a-revoked-version-fails
  (let [{:keys [io requests]} (world {"0.9.0" reason})]
    (fs/create-dirs (path "app"))
    (spit (path "app" ".breto.edn") (pr-str {:packages ["alpha@0.9.0"]}))
    (is (= 1 (:code (breto! {} io "env" "--dir" (path "app")))))
    (is (not (downloaded? requests "0.9.0")))
    (testing "also when the version was installed before it was revoked"
      (define! {})
      (is (= 0 (:code (breto! {} io "env" "--dir" (path "app")))))
      (define! {"0.9.0" reason})
      (is (= 1 (:code (breto! {} io "env" "--dir" (path "app")))))
      (testing "and its linked commands do not run it"
        (let [ran (atom 0)]
          (is (= 1 (:code (breto! {} (assoc io :exec (fn [_] (swap! ran inc) 0)) "exec" "--dir" (path "app") "alpha"))))
          (is (zero? @ran)))))))

(deftest use-refuses-a-revoked-version
  (let [{:keys [io]} (world {})]
    (breto! {} io "install" "alpha@0.9.0")
    (define! {"0.9.0" reason})
    (is (= :revoked (:breto/problem (problem #(breto! {} io "use" "alpha" "0.9.0")))))))

(deftest sync-refuses-a-revoked-lock-entry-and-deactivates-it
  (let [{:keys [io hash]} (world {})
        config {:packages ["alpha"] :lockfile (path "breto.lock")}]
    (breto! {} io "install" "alpha@0.8.0")
    (breto! {} io "install" "alpha@0.9.0")
    (spit (path "breto.lock") (pr-str {"alpha" {:version "0.9.0" :sha256 (hash "0.9.0")}}))
    (define! {"0.9.0" reason})
    (testing "a dry run names the deactivation"
      (is (str/includes? (:out (breto! config io "sync" "--dry-run")) "alpha would be deactivated, since 0.9.0 is revoked")))
    (let [{:keys [code out]} (breto! config io "sync")]
      (is (= 1 code))
      (is (str/includes? out "[FAIL] alpha 0.9.0"))
      (is (str/includes? out "[OK] alpha 0.9.0 deactivated, since it is revoked: "))
      (is (str/includes? out "0.8.0 is active instead")))
    (is (= "#!/bin/sh\necho alpha 0.8.0\n" (active)))))

(deftest update-deactivates-an-active-revoked-version
  (testing "in favour of the newest installed version that is not revoked"
    (let [{:keys [io]} (world {})]
      (breto! {} io "install" "alpha@0.8.0")
      (breto! {} io "install" "alpha@1.0.0")
      (define! {"1.0.0" reason "0.9.0" reason})
      (let [{:keys [out]} (breto! {:packages ["alpha"] :pins {"alpha" "1.0.0"}} io "update")]
        (is (str/includes? out "alpha 1.0.0 deactivated"))
        (is (str/includes? out "1 deactivated")))
      (is (= "#!/bin/sh\necho alpha 0.8.0\n" (active)))
      (is (= "0.8.0" (str (fs/file-name (fs/read-link (path "store" "alpha" "current")))))))))

(deftest update-unlinks-a-revoked-version-without-a-replacement
  (let [{:keys [io]} (world {})]
    (breto! {} io "install" "alpha@1.0.0")
    (define! (zipmap versions (repeat reason)))
    (let [{:keys [code out]} (breto! {:packages ["alpha"]} io "update")]
      (is (= 1 code))
      (is (str/includes? out "no other version is installed, so its commands are unlinked")))
    (is (not (fs/exists? (path "bin" "alpha") {:nofollow-links true})))
    (is (not (fs/exists? (path "store" "alpha" "current") {:nofollow-links true})))
    (is (fs/directory? (path "store" "alpha" "1.0.0")))))

(deftest list-and-doctor-name-installed-revoked-versions
  (let [{:keys [io]} (world {})]
    (breto! {} io "install" "alpha@0.9.0")
    (breto! {} io "install" "alpha@1.0.0")
    (define! {"0.9.0" reason})
    (let [{:keys [out]} (breto! {} io "list")]
      (is (str/includes? out (str "\trevoked 0.9.0: " reason)))
      (is (not (str/includes? out "revoked 1.0.0"))))
    (let [config {:store (path "store") :bin-dir (path "bin")}
          checks (doctor/revoked-check config (s/load-registry [(path "packages")]))]
      (is (= [[:fail "alpha 0.9.0 is installed and revoked" reason]]
             (map (juxt :level :title :detail) checks))))
    (define! {})
    (is (= [:ok] (map :level (doctor/revoked-check {:store (path "store")} (s/load-registry [(path "packages")])))))))

(deftest gc-deletes-revoked-versions-even-when-active
  (let [{:keys [io]} (world {})]
    (breto! {} io "install" "alpha@0.8.0")
    (breto! {} io "install" "alpha@0.9.0")
    (breto! {} io "install" "alpha@1.0.0")
    (define! {"1.0.0" reason "0.8.0" reason})
    (let [{:keys [code out]} (breto! {:keep 5} io "gc")]
      (is (= 0 code) out)
      (is (str/includes? out "[OK] alpha 1.0.0 deactivated"))
      (is (str/includes? out "[OK] removed alpha 1.0.0"))
      (is (str/includes? out "[OK] removed alpha 0.8.0")))
    (is (= ["0.9.0"] (map :version (store/versions {:store (path "store")} "alpha"))))
    (is (= "#!/bin/sh\necho alpha 0.9.0\n" (active)))))

(deftest the-lock-file-drops-an-inactive-revoked-entry
  (let [{:keys [hash]} (world {"0.9.0" reason})
        registry (s/load-registry [(path "packages")])]
    (is (= {} (lockfile/snapshot {:store (path "store")} registry ["alpha"]
                                 {"alpha" {:version "0.9.0" :sha256 (hash "0.9.0")}})))
    (is (= {"alpha" {:version "0.8.0"}}
           (lockfile/snapshot {:store (path "store")} registry ["alpha"] {"alpha" {:version "0.8.0"}})))))
