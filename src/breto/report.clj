;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.report
  (:require [babashka.fs :as fs]
            [breto.filelock :as filelock]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)))

(def ^:dynamic *state-home*
  "The XDG state directory; nil reads XDG_STATE_HOME."
  nil)

(defn state-path []
  (let [state (or *state-home* (System/getenv "XDG_STATE_HOME"))]
    (str (fs/path (if (str/blank? state)
                    (fs/path (System/getProperty "user.home") ".local" "state")
                    state)
                  "breto" "last-run.edn"))))

(def ^:private status-label
  {:installed "installed" :current "up to date" :outdated "outdated"
   :removed "removed" :would-remove "to remove" :checked "checked" :failed "failed"})

(defn summary-line [command results]
  (let [counts (frequencies (map :status results))]
    (str "breto " command ": "
         (if (empty? results)
           "nothing to do"
           (str/join ", " (for [status [:current :installed :checked :outdated :removed :would-remove :failed]
                                :let [n (get counts status 0)]
                                :when (pos? n)]
                            (str n " " (status-label status))))))))

(defn- failure-block [{:keys [package version diagnosis]}]
  (let [{:keys [message summary facts advice stage]} diagnosis]
    (str/join "\n"
              (concat [(str "[FAIL] " package (when version (str " " version))
                            (when stage (str " (" (name stage) ")")))
                       (str "  " message)
                       (str "  " summary)]
                      (map #(str "  · " %) facts)
                      (when (seq advice) ["  What to do:"])
                      (map #(str "  - " %) advice)))))

(def ^:private verification
  {:digest "verifiable by its GitHub digest" :checksum "verifiable by its checksum file"
   :none "installs unverified, as its definition says"})

(defn- line [{:keys [package status version from links unverified signed signature-bootstrap hash-recorded verified-by]}]
  (case status
    :checked (str "[OK] " package " " version " " (verification verified-by)
                  (when signed ", signature bundle present"))
    :installed (str "[OK] " package " " version " installed"
                    (cond unverified " without verification"
                          signed " with a verified signature"
                          signature-bootstrap " by its checksum; no cosign existed yet to check its signature"))
    :current (when hash-recorded
               (str "[OK] " package " " version " is up to date; recorded "
                    (if (= :download hash-recorded)
                      "the hash of a fresh download for the lock file, since upstream publishes none"
                      "upstream's hash for the lock file")))
    :removed (str "[OK] " package " removed" (when (seq links) (str " with " (count links) " links")))
    :would-remove (str package " would be removed" (when (seq links) (str " with links " (str/join ", " links))))
    :outdated (str package "\t" (or from "-") " -> " version)
    nil))

(defn- local-time [instant]
  (.format (.atZone (Instant/parse instant) (ZoneId/systemDefault))
           (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss z")))

(defn heading
  "The line above a saved run: when it finished, or how far a running one got."
  [{:keys [command running total results updated finished]}]
  (if running
    (str "breto " command " is running: " (count results) (when total (str " of " total)) " done"
         (when updated (str ", last at " (local-time updated))))
    (str "breto " command " finished " (local-time finished))))

(defn- aborted-line [command {:keys [message]}]
  (str "breto " command " stopped: " message))

(defn render
  "The report a command prints: one line per change, a block per failure, and
  a summary line. A command that stopped before reaching any package has its
  error in `:error` instead."
  [{:keys [command results error]}]
  (if error
    (str/join "\n" (concat [(failure-block {:package (str "breto " command) :diagnosis error}) ""]
                            [(aborted-line command error)]))
    (let [lines (mapcat (fn [{:keys [package warnings] :as result}]
                          (remove nil? (cons (line result) (map #(str "[WARN] " package ": " %) warnings))))
                        results)
          failures (filter #(= :failed (:status %)) results)]
      (str/join "\n"
                (concat lines
                        (when (and (seq lines) (seq failures)) [""])
                        (interpose "" (map failure-block failures))
                        (when (or (seq lines) (seq failures)) [""])
                        [(summary-line command results)])))))

(defn- clip [width text]
  (if (> (count text) width) (str (subs text 0 (dec width)) "…") text))

(defn brief
  "A few short lines for a desktop notification: when `run` finished, its
  counts, and the first failures with their diagnosis summaries. The full
  report stays one breto report away."
  [{:keys [command results error running] :as run} {:keys [failures width] :or {failures 3 width 100}}]
  (let [when-line (if running (heading run) (str "breto " command ", " (local-time (:finished run))))
        failed (filter #(= :failed (:status %)) results)
        shown (take failures failed)]
    (str/join "\n"
              (map #(clip width %)
                   (concat [when-line]
                           (if error
                             [(:message error) (:summary error)]
                             (concat [(subs (summary-line command results) (count (str "breto " command ": ")))]
                                     (for [{:keys [package version diagnosis]} shown]
                                       (str "✗ " package (when version (str " " version)) ": "
                                            (or (:summary diagnosis) (:message diagnosis))))
                                     (when (> (count failed) (count shown))
                                       [(str "+" (- (count failed) (count shown)) " more failures; breto report shows them all")]))))))))

(defn load-runs
  "The last recorded run of each command, oldest first."
  []
  (let [path (state-path)
        runs (when (fs/exists? path) (edn/read-string (slurp path)))]
    (sort-by :finished (if (:command runs) [runs] (vals runs)))))

(defn- save-unlocked! [report]
  (let [path (state-path)
        runs (into {} (map (juxt :command identity)) (load-runs))
        stamped (if (:running report)
                  (assoc report :updated (str (Instant/now)))
                  (assoc (dissoc report :running :total :updated) :finished (str (Instant/now))))
        staged (str path ".tmp")]
    (fs/create-dirs (fs/parent path))
    (spit staged (pr-str (assoc runs (:command report) stamped)))
    (fs/move staged path {:replace-existing true :atomic-move true})))

(defn save!
  "Records `report` as the last run of its command, keeping the other commands'.
  A report marked `:running` records when it was last updated instead of when
  it finished."
  [report]
  (filelock/with-lock (str (fs/parent (state-path))) (fn [_])
    #(save-unlocked! report)))
