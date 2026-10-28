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
    (is (= :fail (:level (doctor/github-check {:get-text (fn [_ _] (throw (ex-info "offline" {})))}))))
    (is (= "GitHub rejects the token"
           (:title (doctor/github-check {:get-text (fn [_ _] (throw (ex-info "GET returned 401" {:breto/problem :http-status :status 401})))}))))))

(deftest rendering-shows-advice-only-for-problems
  (let [text (doctor/render [{:level :ok :title "fine" :detail "d" :advice ["no"]}
                             {:level :fail :title "broken" :detail "why" :advice ["fix it"]}])]
    (is (str/includes? text "[OK]   fine"))
    (is (not (str/includes? text "no\n")))
    (is (str/includes? text "[FAIL] broken\n       why\n       - fix it"))))

(deftest unverified-packages-are-listed-with-their-scope
  (let [registry {"airmux" {:history [{:before "0.2.4" :package {:verify false}}]}
                  "raw" {:verify false}
                  "kind" {}}
        check (doctor/unverified-check {:packages ["airmux" "raw" "kind"]} registry)]
    (is (= :warn (:level check)))
    (is (= "airmux (versions before 0.2.4), raw (every version)" (:detail check))))
  (is (= :ok (:level (doctor/unverified-check {:packages ["kind"]} {"kind" {}})))))

(deftest signature-checks-need-cosign-only-for-signed-packages
  (let [registry {"kind" {} "kyverno" {:signature {}} "cosign" {:signature {} :bins {"cosign" "cosign"}}}]
    (is (= :ok (:level (doctor/signature-check {:packages ["kind"]} registry (constantly nil)))))
    (is (= :ok (:level (doctor/signature-check {:packages ["kyverno"]} registry {"cosign" "/bin/cosign"}))))
    (let [missing (doctor/signature-check {:packages ["kyverno"]} registry (constantly nil))]
      (is (= :fail (:level missing)))
      (is (= "kyverno" (:detail missing)))
      (is (some #(str/includes? % "Add \"cosign\" to :packages") (:advice missing))))
    (let [pending (doctor/signature-check {:packages ["kyverno" "cosign"]} registry (constantly nil))]
      (is (= :warn (:level pending)))
      (is (some #(str/includes? % "installs cosign first") (:advice pending))))
    (is (= :fail (:level (doctor/signature-check {:packages ["cosign" "kyverno"] :cosign "/opt/c"} registry (constantly nil)))))
    (is (= :ok (:level (doctor/signature-check {:packages ["kyverno"] :cosign "/opt/c"} registry {"/opt/c" "/opt/c"}))))))
