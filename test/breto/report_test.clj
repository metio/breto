;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.report-test
  (:require [breto.report :as report]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- failed [package summary]
  {:package package :version "1.0" :status :failed :diagnosis {:message "m" :summary summary}})

(def ^:private finished "2026-10-28T06:00:00Z")

(deftest brief-reports-count-and-name-the-first-failures
  (let [run {:command "update" :finished finished
             :results (concat (repeat 60 {:status :current})
                              [(failed "airmux" "No asset matches.") (failed "kyverno" "The signature does not verify.")
                               (failed "a" "x") (failed "b" "y") (failed "c" "z")])}
        lines (str/split-lines (report/brief run {}))]
    (is (str/starts-with? (first lines) "breto update, 2026-10-28"))
    (is (= "60 up to date, 5 failed" (second lines)))
    (is (= "✗ airmux 1.0: No asset matches." (nth lines 2)))
    (is (= 6 (count lines)))
    (is (= "+2 more failures; breto report shows them all" (last lines)))))

(deftest brief-lines-are-clipped
  (let [run {:command "update" :finished finished :results [(failed "p" (apply str (repeat 300 "x")))]}]
    (is (every? #(<= (count %) 40) (str/split-lines (report/brief run {:width 40}))))
    (is (str/ends-with? (last (str/split-lines (report/brief run {:width 40}))) "…"))))

(deftest brief-shows-why-a-run-stopped
  (let [run {:command "update" :finished finished :results [] :error {:message "boom" :summary "The config is broken."}}]
    (is (= ["boom" "The config is broken."] (rest (str/split-lines (report/brief run {})))))))

(deftest brief-reports-a-running-run-as-such
  (is (str/includes? (report/brief {:command "update" :running true :total 9 :updated finished
                                    :results [{:status :current}]} {})
                     "is running: 1 of 9 done")))

(defn- kind! []
  (let [binary (s/file! "kind" "k")
        url "https://github.com/acme/kind/releases/download/v1.0.0/kind"]
    (s/define! "kind" {:source {:github "acme/kind"} :asset "kind" :checksum "{asset}.sha256"})
    {:io (s/fake-io {url binary}
                    {"https://github.com/acme/kind/releases/latest" "{\"tag_name\":\"v1.0.0\"}"
                     (str url ".sha256") (s/sha256 binary)
                     "https://api.github.com/repos/acme/kind/releases/tags/v1.0.0"
                     (str "{\"tag_name\":\"v1.0.0\",\"assets\":[{\"name\":\"kind\",\"digest\":\"sha256:" (s/sha256 binary) "\"}]}")}
                    (atom []))}))

(deftest the-report-command-selects-runs
  (let [world (kind!)]
    (s/breto! {:packages ["kind"]} ["update"] world)
    (s/breto! {:packages ["kind"]} ["check"] world)
    (testing "every run"
      (let [{:keys [out]} (s/breto! {} ["report"])]
        (is (str/includes? out "breto update finished"))
        (is (str/includes? out "breto check finished"))))
    (testing "one command"
      (let [{:keys [out]} (s/breto! {} ["report" "update"])]
        (is (str/includes? out "breto update finished"))
        (is (not (str/includes? out "breto check")))))
    (testing "brief picks the latest"
      (let [{:keys [code out]} (s/breto! {} ["report" "--brief"])]
        (is (= 0 code))
        (is (str/starts-with? out "breto check, "))
        (is (str/includes? out "1 checked"))))
    (testing "an unknown command"
      (is (str/includes? (:out (s/breto! {} ["report" "outdated"])) "No run of outdated recorded")))))

(deftest runs-that-stop-early-replace-the-last-report
  (let [world (kind!)]
    (s/breto! {:packages ["kind"]} ["update"] world)
    (spit (path "packages" "broken.edn") "{:source")
    (is (thrown? Exception (s/breto! {:packages ["kind"]} ["update"] world)))
    (let [{:keys [out]} (s/breto! {} ["report" "update" "--brief"])
          lines (str/split-lines out)]
      (is (str/starts-with? (first lines) "breto update, "))
      (is (not (str/includes? out "installed"))))
    (is (str/includes? (:out (s/breto! {} ["report" "update"])) "breto update stopped:"))))

(deftest running-headings-tolerate-missing-counts
  (is (= "breto update is running: 2 done" (report/heading {:command "update" :running true :results [{} {}]}))))
