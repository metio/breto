;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.batch
  "Running a command over many packages: one result per package, failures
  diagnosed instead of stopping the rest, pacing between packages, and progress
  events for the display and the saved report."
  (:require [breto.diagnose :as diagnose]
            [breto.http :as http]
            [breto.log :as log]
            [breto.report :as report]
            [clojure.stacktrace :as stacktrace]
            [clojure.string :as str]))

(defn split-spec
  "PKG@VERSION as [PKG VERSION], VERSION nil when absent."
  [spec]
  (let [[name version] (str/split spec #"@" 2)]
    [name version]))

(defn failure
  "The result for a package whose command threw `e`, with its diagnosis."
  [io package version e]
  (when log/*debug*
    (binding [*out* *err*] (stacktrace/print-cause-trace e)))
  {:package package :version (or version (:version (ex-data e))) :status :failed
   :diagnosis (diagnose/diagnose e (diagnose/github-probes io))})

(defn run-batch
  "Runs `f` for every name, collecting a result per name; a failure becomes a
  diagnosed result instead of stopping the others. `progress` receives a start
  and a finish event per name and an end event."
  [io names f progress]
  (let [total (count names)
        results (vec (map-indexed
                      (fn [i name]
                        (progress {:type :start :index (inc i) :total total :package name})
                        (let [result (try (f name)
                                          (catch Exception e
                                            (let [[package version] (split-spec name)]
                                              (failure io package version e))))]
                          (progress {:type :finish :result result :status (:status result) :package (:package result)
                                     :stage (get-in result [:diagnosis :stage])
                                     :summary (get-in result [:diagnosis :summary])})
                          result))
                      names))]
    (progress {:type :end})
    results))

(defn tracked-io
  "`io` reporting stages and download progress to `progress`, and the most
  bytes any download of the current package received to `transferred`."
  [io progress transferred]
  (let [download! (:download! io)]
    (assoc io
           :progress progress
           :download! (fn [url dest]
                        (download! url dest (fn [done size]
                                              (swap! transferred max done)
                                              (progress {:type :bytes :done done :size size})))))))

(defn paced
  "Wraps `f` so that each call first waits for the longer of the wait upstream
  hosts ask for and, when `owed?` said so after the previous call, `delay`
  seconds. A call refused by a rate limit is retried once after the wait the
  host names."
  [delay owed? upstream-wait sleep! f]
  (let [owed (atom false)]
    (fn [x]
      (let [own (if @owed delay 0)
            upstream (upstream-wait)]
        (when (pos? (max own upstream))
          (sleep! (max own upstream) (if (> upstream own) "the rate limit of an upstream host" ":delay"))))
      (try
        (try
          (f x)
          (catch clojure.lang.ExceptionInfo e
            (let [upstream (upstream-wait)]
              (if (and (http/rate-limited? (ex-data e)) (pos? upstream))
                (do (sleep! upstream "the rate limit of an upstream host")
                    (f x))
                (throw e)))))
        (finally
          (reset! owed (owed?)))))))

(defn counting-down
  "A sleep! for `paced` that reports every remaining second to `progress` and
  sleeps each one through `sleep-second!`."
  [progress sleep-second!]
  (fn [seconds why]
    (doseq [left (range seconds 0 -1)]
      (progress {:type :wait :seconds seconds :left left :why why})
      (sleep-second!))))

(defn broadcast
  "One progress callback that passes each event to all of `callbacks`."
  [& callbacks]
  (fn [event] (doseq [f callbacks] (f event))))

(defn recorder
  "A progress callback that saves the results of `command` so far after every
  package, so breto report shows a run that is still going."
  [command]
  (let [results (atom [])
        total (atom nil)]
    (fn [{:keys [type result] :as event}]
      (case type
        :start (reset! total (:total event))
        :finish (let [done (swap! results conj result)]
                  (try (report/save! {:command command :results done :running true :total @total})
                       (catch Exception _ nil)))
        nil))))

(defn finish!
  "Prints and saves the report for `command`, returning the exit code."
  [command results]
  (let [run {:command command :results results}]
    (println (report/render run))
    (try (report/save! run)
         (catch Exception e
           (binding [*out* *err*]
             (println (str "[WARN] could not save the report to " (report/state-path) ": " (ex-message e))))))
    (if (some #(= :failed (:status %)) results) 1 0)))
