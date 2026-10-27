;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.progress-test
  (:require [breto.progress :as progress]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- replay [& events]
  (reduce progress/step {} events))

(deftest bars-fill-in-eighths-and-keep-their-width
  (is (= "    " (progress/bar 0 4)))
  (is (= "██  " (progress/bar 1/2 4)))
  (is (= "█▌  " (progress/bar 3/8 4)))
  (is (= "████" (progress/bar 1 4)))
  (is (= "████" (progress/bar 2 4))))

(deftest sizes-and-clocks-are-human-readable
  (is (= "512 B" (progress/size 512)))
  (is (= "1.5 KB" (progress/size 1536)))
  (is (= "51.0 MB" (progress/size (* 51 1024 1024))))
  (is (= "4:05" (progress/clock 245)))
  (is (= "0:09" (progress/clock 9))))

(deftest the-status-line-shows-position-tally-and-activity
  (let [state (replay {:type :start :index 1 :total 3 :package "kind"}
                      {:type :finish :status :current}
                      {:type :start :index 2 :total 3 :package "helm"}
                      {:type :stage :stage :download}
                      {:type :bytes :done (* 2 1024 1024) :size (* 4 1024 1024)})
        line (progress/status-line state false)]
    (is (str/includes? line "2/3 =1  helm  downloading 2.0 MB / 4.0 MB"))
    (is (str/includes? line (progress/bar 1/3 24)))))

(deftest waits-replace-the-activity-until-the-next-stage
  (let [waiting (replay {:type :start :index 2 :total 3 :package "helm"}
                        {:type :wait :seconds 300 :left 245 :why ":delay"})]
    (is (str/ends-with? (progress/status-line waiting false) "waiting 4:05 for :delay"))
    (is (str/ends-with? (progress/status-line (progress/step waiting {:type :stage :stage :resolve}) false)
                        "resolving"))))

(deftest finishing-counts-statuses-and-clears-the-activity
  (let [state (replay {:type :start :index 1 :total 2 :package "a"}
                      {:type :stage :stage :download}
                      {:type :finish :status :failed}
                      {:type :start :index 2 :total 2 :package "b"}
                      {:type :finish :status :installed})]
    (is (= {:failed 1 :installed 1} (:counts state)))
    (is (nil? (:stage state)))))

(deftest plain-progress-logs-packages-and-the-start-of-each-wait
  (let [out (java.io.StringWriter.)
        report (progress/plain-reporter out)]
    (run! report [{:type :start :index 1 :total 2 :package "kind"}
                  {:type :stage :stage :download}
                  {:type :bytes :done 10 :size 20}
                  {:type :finish :status :installed :package "kind"}
                  {:type :start :index 2 :total 2 :package "helm"}
                  {:type :finish :status :failed :package "helm" :stage :download
                   :summary "The release has no asset named helm.tgz."}
                  {:type :wait :seconds 300 :left 300 :why ":delay"}
                  {:type :wait :seconds 300 :left 299 :why ":delay"}
                  {:type :end}])
    (is (= (str "[1/2] kind\n[2/2] helm\n"
                "[FAIL] helm (download): The release has no asset named helm.tgz. The report at the end says what to do.\n"
                "waiting 300s for :delay\n")
           (str out)))))
