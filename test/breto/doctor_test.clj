;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.doctor-test
  (:require [babashka.fs :as fs]
            [breto.doctor :as doctor]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- levels [checks]
  (into {} (map (juxt :title :level)) checks))

(deftest store-checks-find-dangling-links-leftovers-and-orphans
  (let [root (fs/create-temp-dir)
        cfg {:store (str (fs/path root "store")) :bin-dir (str (fs/path root "bin"))}]
    (try
      (fs/create-dirs (fs/path root "store" "kind" "0.33.0"))
      (fs/create-dirs (fs/path root "store" "kind" ".work-123"))
      (fs/create-dirs (fs/path root "store" "zed" "1.0"))
      (fs/create-dirs (fs/path root "bin"))
      (fs/create-sym-link (fs/path root "bin" "kind") (fs/path root "store" "kind" "current" "kind"))
      (let [checks (doctor/store-check cfg {"kind" {}})]
        (is (= {"Links in the bin directory point at missing files" :fail
                "Interrupted installs left work directories behind" :warn
                "The store holds packages no registry defines" :warn}
               (levels checks)))
        (is (str/includes? (:detail (nth checks 2)) "zed")))
      (finally (fs/delete-tree root)))))

(deftest config-checks-report-unknown-packages-and-missing-registries
  (let [checks (doctor/config-check "/nonexistent/config.edn"
                                    {:registries ["/nonexistent/packages"] :packages ["kind" "nope"]}
                                    {"kind" {}})]
    (is (= [:warn :fail :fail] (map :level checks)))
    (is (= "nope" (:detail (last checks))))))

(deftest github-checks-flag-unauthenticated-and-exhausted-limits
  (let [io (fn [body] {:get-text (fn [_ _] body)})]
    (is (= :warn (:level (doctor/github-check (io "{\"rate\":{\"limit\":60,\"remaining\":59}}")))))
    (is (= :fail (:level (doctor/github-check (io "{\"rate\":{\"limit\":60,\"remaining\":0}}")))))
    (is (= :ok (:level (doctor/github-check (io "{\"rate\":{\"limit\":5000,\"remaining\":4999}}")))))
    (is (= :fail (:level (doctor/github-check {:get-text (fn [_ _] (throw (ex-info "offline" {})))}))))))

(deftest rendering-shows-advice-only-for-problems
  (let [text (doctor/render [{:level :ok :title "fine" :detail "d" :advice ["no"]}
                             {:level :fail :title "broken" :detail "why" :advice ["fix it"]}])]
    (is (str/includes? text "[OK]   fine"))
    (is (not (str/includes? text "no\n")))
    (is (str/includes? text "[FAIL] broken\n       why\n       - fix it"))))
