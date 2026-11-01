;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.sync-test
  (:require [babashka.fs :as fs]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- world
  "Packages alpha, beta and gamma, each with releases 1.0.0 (the latest) and
  0.9.0, and the hash of every asset."
  []
  (let [assets (into {} (for [name ["alpha" "beta" "gamma"] version ["0.9.0" "1.0.0"]]
                          [[name version] (s/file! (str name "-" version) (str "#!/bin/sh\necho " name " " version "\n"))]))]
    (doseq [name ["alpha" "beta" "gamma"]]
      (s/define! name {:source {:github (str "acme/" name)} :asset name :verify false}))
    {:hash #(s/sha256 (get assets [%1 %2]))
     :io (s/fake-io (into {} (for [[[name version] file] assets]
                               [(str "https://github.com/acme/" name "/releases/download/v" version "/" name) file]))
                    (into {} (for [name ["alpha" "beta" "gamma"]]
                               [(str "https://github.com/acme/" name "/releases/latest") "{\"tag_name\":\"v1.0.0\"}"]))
                    (atom []))}))

(defn- lock! [entries]
  (spit (path "breto.lock") (pr-str entries)))

(def ^:private config {:packages ["alpha" "beta"]})

(defn- configured []
  (assoc config :lockfile (path "breto.lock")))

(deftest sync-installs-the-locked-versions-and-removes-the-rest
  (let [{:keys [io hash]} (world)]
    (s/breto! {:packages ["alpha" "gamma"]} ["update"] {:io io})
    (lock! {"alpha" {:version "0.9.0" :sha256 (hash "alpha" "0.9.0")}
            "beta" {:version "1.0.0" :sha256 (hash "beta" "1.0.0")}})
    (testing "a dry run lists the changes and changes nothing"
      (let [{:keys [code out]} (s/breto! (configured) ["sync" "--dry-run"] {:io io})]
        (is (= 0 code))
        (is (str/includes? out "alpha would change from 1.0.0 to 0.9.0"))
        (is (str/includes? out "beta would change from nothing to 1.0.0"))
        (is (str/includes? out "gamma would be removed"))
        (is (fs/exists? (path "bin" "gamma")))))
    (let [{:keys [code out]} (s/breto! (configured) ["sync"] {:io io})]
      (is (= 0 code) out)
      (is (str/includes? out "[OK] alpha 0.9.0 installed"))
      (is (str/includes? out "[OK] beta 1.0.0 installed"))
      (is (str/includes? out "[OK] gamma removed")))
    (is (= "#!/bin/sh\necho alpha 0.9.0\n" (slurp (path "bin" "alpha"))))
    (is (not (fs/exists? (path "bin" "gamma"))))
    (testing "a second sync changes nothing"
      (let [{:keys [out]} (s/breto! (configured) ["sync"] {:io io})]
        (is (str/includes? out "2 up to date"))))))

(deftest sync-refuses-downloads-that-differ-from-the-lock
  (let [{:keys [io]} (world)]
    (lock! {"alpha" {:version "1.0.0" :sha256 (apply str (repeat 64 "0"))}})
    (let [{:keys [code out]} (s/breto! (configured) ["sync"] {:io io})]
      (is (= 1 code))
      (is (str/includes? out "[FAIL] alpha"))
      (is (not (fs/exists? (path "bin" "alpha")))))))

(deftest sync-keeps-what-projects-use
  (let [{:keys [io hash]} (world)]
    (s/breto! {:packages ["gamma"]} ["update"] {:io io})
    (fs/create-dirs (path "app"))
    (spit (path "app" ".breto.edn") (pr-str {:packages ["gamma"]}))
    (s/breto! (assoc (configured) :env-dir (path "envs")) ["env" "--dir" (path "app")] {:io io})
    (lock! {"alpha" {:version "1.0.0" :sha256 (hash "alpha" "1.0.0")}})
    (let [{:keys [out]} (s/breto! (assoc (configured) :env-dir (path "envs")) ["sync"] {:io io})]
      (is (not (str/includes? out "gamma removed")) out)
      (is (fs/directory? (path "store" "gamma"))))))

(deftest sync-needs-a-lock-file
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sync needs :lockfile"
                        (s/breto! config ["sync"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"there is no lock file"
                        (s/breto! (configured) ["sync"]))))

(deftest sync-moves-projects-to-the-versions-their-groups-name
  (let [{:keys [io hash]} (world)
        cfg (assoc (configured) :env-dir (path "envs") :registries [(path "packages") (path "shared")])]
    (fs/create-dirs (path "shared"))
    (fs/create-dirs (path "app"))
    (spit (path "shared" "@toolchain.edn") (pr-str {:members ["gamma@0.9.0"]}))
    (spit (path "app" ".breto.edn") (pr-str {:packages ["@toolchain"]}))
    (lock! {"alpha" {:version "1.0.0" :sha256 (hash "alpha" "1.0.0")}
            "beta" {:version "1.0.0" :sha256 (hash "beta" "1.0.0")}})
    (binding [*err* (java.io.StringWriter.)]
      (s/breto! cfg ["env" "--dir" (path "app")] {:io io}))
    (spit (path "shared" "@toolchain.edn") (pr-str {:members ["gamma@1.0.0"]}))
    (let [{:keys [code out]} (s/breto! cfg ["sync"] {:io io})
          bin (first (filter #(str/includes? (str %) "envs") (map str (fs/glob (path "envs") "*/bin"))))]
      (is (= 0 code) out)
      (is (str/includes? out "[OK] gamma 1.0.0 installed"))
      (is (not (str/includes? out "gamma would be removed")))
      (is (= "#!/bin/sh\necho gamma 1.0.0\n" (slurp (str (fs/path bin ".." "targets" "gamma"))))))))

(deftest a-failed-sync-leaves-the-lock-file-alone
  (let [{:keys [io]} (world)]
    (s/breto! {:packages ["alpha"] :pins {"alpha" "0.9.0"}} ["update"] {:io io})
    (lock! {"alpha" {:version "1.0.0" :sha256 (apply str (repeat 64 "0"))}})
    (let [before (slurp (path "breto.lock"))
          {:keys [code]} (s/breto! (configured) ["sync"] {:io io})]
      (is (= 1 code))
      (is (= before (slurp (path "breto.lock")))))))

(deftest sync-refuses-an-installed-version-that-differs-from-the-lock
  (let [{:keys [io]} (world)]
    (s/breto! {:packages ["alpha"]} ["update"] {:io io})
    (lock! {"alpha" {:version "1.0.0" :sha256 (apply str (repeat 64 "0"))}})
    (let [before (slurp (path "breto.lock"))
          {:keys [code out]} (s/breto! (configured) ["sync"] {:io io})]
      (is (= 1 code) out)
      (is (str/includes? out "[FAIL] alpha"))
      (is (= before (slurp (path "breto.lock")))))))
