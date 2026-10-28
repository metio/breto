;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.groups-test
  (:require [babashka.fs :as fs]
            [breto.diagnose :as diagnose]
            [breto.registry :as registry]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- group! [dir name group]
  (fs/create-dirs (path dir))
  (spit (path dir (str "@" name ".edn")) (pr-str group)))

(defn- problem [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest groups-load-from-at-files-and-are-not-packages
  (s/define! "a" {:source {:github "acme/a"} :asset "a"})
  (group! "packages" "suite" {:members ["a" "b"]})
  (is (= ["a"] (keys (registry/load-registry [(path "packages")]))))
  (is (= {"@suite" {:members ["a" "b"] :files [(path "packages" "@suite.edn")]}}
         (registry/load-groups [(path "packages")]))))

(deftest later-registries-replace-members
  (group! "packages" "suite" {:members ["a" "b"]})
  (group! "overlay" "suite" {:members ["a"]})
  (let [groups (registry/load-groups [(path "packages") (path "overlay")])]
    (is (= ["a"] (get-in groups ["@suite" :members])))
    (is (= 2 (count (get-in groups ["@suite" :files]))))))

(deftest invalid-groups-name-their-file
  (group! "packages" "empty" {:members []})
  (let [data (problem #(registry/load-groups [(path "packages")]))]
    (is (= :invalid-package (:breto/problem data)))
    (is (= "@empty" (:package data)))
    (is (= [(path "packages" "@empty.edn")] (:files data))))
  (fs/delete (path "packages" "@empty.edn"))
  (spit (path "packages" "@broken.edn") "{:members [")
  (is (str/includes? (first (:problems (problem #(registry/load-groups [(path "packages")])))) "not valid EDN")))

(deftest expansion-is-recursive-ordered-and-distinct
  (let [groups {"@mail" {:members ["himalaya" "@contacts"]}
                "@contacts" {:members ["cardamum" "tcard"]}}]
    (is (= ["helm" "himalaya" "cardamum" "tcard" "kind"]
           (registry/expand groups ["helm" "@mail" "kind" "tcard"])))
    (is (= ["a"] (registry/expand {} ["a"])))))

(deftest unknown-groups-and-cycles-are-explained
  (let [unknown (problem #(registry/expand {"@mail" {:members ["x"]}} ["@male"]))]
    (is (= :unknown-group (:breto/problem unknown)))
    (is (some #(str/includes? % "@mail") (:facts (diagnose/diagnose (ex-info "x" unknown) {})))))
  (let [cycle (problem #(registry/expand {"@a" {:members ["@b"]} "@b" {:members ["@a"]}} ["@a"]))]
    (is (= :invalid-package (:breto/problem cycle)))
    (is (str/includes? (first (:problems cycle)) "@a -> @b -> @a"))))

(defn- world []
  (let [files (into {} (for [name ["a" "b" "c"]]
                         [(str "https://github.com/acme/" name "/releases/download/v1.0.0/" name)
                          (s/file! name (str "#!/bin/sh\necho " name "\n"))]))
        texts (into {} (for [name ["a" "b" "c"]]
                         [(str "https://github.com/acme/" name "/releases/latest") "{\"tag_name\":\"v1.0.0\"}"]))]
    (doseq [name ["a" "b" "c"]]
      (s/define! name {:source {:github (str "acme/" name)} :asset name :verify false}))
    (group! "packages" "suite" {:members ["a" "b"]})
    (s/fake-io files texts (atom []))))

(deftest configured-groups-install-their-members
  (let [io (world)
        {:keys [code out]} (s/breto! {:packages ["@suite" "c"]} ["update"] {:io io})]
    (is (= 0 code) out)
    (is (str/includes? out "3 installed"))
    (is (every? #(fs/exists? (path "bin" %)) ["a" "b" "c"]))))

(deftest commands-take-groups-as-arguments
  (let [io (world)]
    (is (str/includes? (:out (s/breto! {:packages []} ["install" "@suite"] {:io io})) "2 installed"))
    (is (str/includes? (:out (s/breto! {:packages []} ["info" "@suite"])) ":packages [\"a\" \"b\"]"))
    (is (str/includes? (:out (s/breto! {:packages []} ["uninstall" "@suite"] {:io io})) "2 removed"))))

(deftest pruning-keeps-the-members-of-configured-groups
  (let [io (world)]
    (s/breto! {:packages ["@suite" "c"]} ["update"] {:io io})
    (testing "a member that stays in the group"
      (is (not (str/includes? (:out (s/breto! {:packages ["@suite"] :prune true} ["gc"] {:io io})) "a removed"))))
    (testing "a member the group drops"
      (group! "packages" "suite" {:members ["a"]})
      (let [{:keys [out]} (s/breto! {:packages ["@suite"] :prune true} ["gc"] {:io io})]
        (is (str/includes? out "[OK] b removed"))
        (is (fs/exists? (path "bin" "a")))))))

(deftest lock-files-list-the-members
  (let [io (world)]
    (s/breto! {:packages ["@suite"] :lockfile (path "breto.lock")} ["update"] {:io io})
    (let [lock (slurp (path "breto.lock"))]
      (is (str/includes? lock "\"a\" {:version \"1.0.0\""))
      (is (str/includes? lock "\"b\" {:version \"1.0.0\""))
      (is (not (str/includes? lock "@suite"))))))

(deftest unknown-groups-stop-the-run-with-a-diagnosis
  (world)
  (let [{:keys [code out]} (try (s/breto! {:packages ["@suiet"]} ["update"])
                                (catch clojure.lang.ExceptionInfo e
                                  {:code 1 :out (:summary (diagnose/diagnose e {}))}))]
    (is (= 1 code))
    (is (str/includes? out "No registry defines a group called @suiet"))))

(deftest doctor-names-what-stops-the-definitions-from-loading
  (world)
  (let [{:keys [code out]} (s/breto! {:packages ["@suiet"]} ["doctor"])]
    (is (= 1 code))
    (is (str/includes? out "[FAIL] The definitions do not load"))
    (is (str/includes? out "No registry defines a group called @suiet"))))
