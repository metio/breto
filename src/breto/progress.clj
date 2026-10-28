;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.progress
  "Live progress of a batch of packages: a redrawn status line on a terminal,
  one line per package and wait otherwise."
  (:require [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn terminal?
  "Whether standard error is a terminal."
  []
  (try (zero? (:exit (process/shell {:continue true} "test" "-t" "2")))
       (catch Exception _ false)))

(defn- columns []
  (or (try (some-> (process/shell {:continue true :in (io/file "/dev/tty") :out :string :err :string}
                                  "stty" "size")
                   :out str/trim (str/split #"\s+") second parse-long)
           (catch Exception _ nil))
      (some-> (System/getenv "COLUMNS") parse-long)
      80))

(defn step
  "The progress state after `event`."
  [state {:keys [type] :as event}]
  (case type
    :start (-> state
               (merge (select-keys event [:index :total :package]))
               (dissoc :stage :done :size :wait))
    :wait (assoc state :wait (select-keys event [:left :why]))
    :stage (-> state (assoc :stage (:stage event)) (dissoc :wait))
    :bytes (merge state (select-keys event [:done :size]))
    :finish (-> state
                (update-in [:counts (:status event)] (fnil inc 0))
                (dissoc :stage :done :size :wait))
    state))

(def ^:private eighths [" " "▏" "▎" "▍" "▌" "▋" "▊" "▉"])

(defn bar
  "A bar `width` characters wide, filled to `fraction` in eighths of a character."
  [fraction width]
  (let [n (long (* (max 0 (min 1 fraction)) width 8))
        full (quot n 8)
        part (rem n 8)]
    (str (str/join (repeat full "█"))
         (when (< full width) (eighths part))
         (str/join (repeat (- width full (if (< full width) 1 0)) " ")))))

(defn size [bytes]
  (let [units ["B" "KB" "MB" "GB"]
        [value unit] (loop [v (double bytes) [u & more] units]
                       (if (and (>= v 1024) (seq more)) (recur (/ v 1024) more) [v u]))]
    (if (= "B" unit) (str (long value) " B") (format "%.1f %s" value unit))))

(defn clock [seconds]
  (format "%d:%02d" (quot seconds 60) (rem seconds 60)))

(def ^:private stage-label
  {:resolve "resolving" :download "downloading" :verify "verifying" :unpack "unpacking"
   :place "placing" :activate "activating" :check "checking" :signature "checking the signature"})

(defn status-line
  "The status line for `state`, colored with ANSI escapes when `color?`."
  [{:keys [index total package stage done wait counts] expected :size} color?]
  (let [paint (fn [code s] (if color? (str "\u001b[" code "m" s "\u001b[0m") s))
        finished (reduce + 0 (vals counts))
        tally (str/join " " (keep (fn [[status mark code]]
                                    (when-let [n (get counts status)] (paint code (str mark n))))
                                  [[:installed "✓" "32"] [:checked "✓" "32"] [:current "=" "2"] [:outdated "↑" "33"] [:failed "✗" "31"]]))
        activity (cond
                   wait (str "waiting " (clock (:left wait)) " for " (:why wait))
                   (and (= :download stage) done) (str "downloading " (size done) (when expected (str " / " (size expected))))
                   stage (stage-label stage)
                   :else "")]
    (str "▕" (paint "32" (bar (/ finished (max 1 total)) 24)) "▏ "
         index "/" total " " tally "  " (paint "1" package) "  " activity)))

(defn failure-line
  "The line a failed package leaves in the output, or nil for any other event."
  [{:keys [type status package stage summary]}]
  (when (and (= :finish type) (= :failed status))
    (str "[FAIL] " package (when stage (str " (" (name stage) ")")) (when summary (str ": " summary))
         " The report at the end says what to do.")))

(defn- visible-length [s]
  (count (str/replace s #"\u001b\[[0-9;]*m" "")))

(defn- fit [s width]
  (if (< (visible-length s) width)
    s
    (str (subs (str/replace s #"\u001b\[[0-9;]*m" "") 0 (max 0 (- width 2))) "…")))

(defn terminal-reporter
  "Redraws one status line on `w` for every event, at most ten times a second
  for download progress, and clears it when the batch ends. A failed package
  leaves a line above it."
  [^java.io.Writer w]
  (let [state (atom {})
        drawn (atom 0)
        color? (str/blank? (System/getenv "NO_COLOR"))
        width (columns)]
    (fn [{:keys [type] :as event}]
      (let [s (swap! state step event)
            now (System/currentTimeMillis)]
        (when-let [line (failure-line event)]
          (.write w (str "\r\u001b[K" (if color? (str "\u001b[31m" line "\u001b[0m") line) "\n")))
        (cond
          (= :end type) (do (.write w "\r\u001b[K") (.flush w))
          (or (not= :bytes type) (> now (+ @drawn 100)))
          (do (reset! drawn now)
              (.write w (str "\r\u001b[K" (fit (status-line s color?) width)))
              (.flush w)))))))

(defn plain-reporter
  "Writes a line to `w` when a package starts or fails and when a wait begins,
  for logs such as the systemd journal."
  [^java.io.Writer w]
  (let [state (atom {})]
    (fn [{:keys [type] :as event}]
      (let [{:keys [index total package]} (swap! state step event)
            line (case type
                   :start (str "[" index "/" total "] " package)
                   :wait (when (= (:left event) (:seconds event))
                           (str "waiting " (:seconds event) "s for " (:why event)))
                   :finish (failure-line event)
                   nil)]
        (when line
          (.write w (str line "\n"))
          (.flush w))))))

(defn reporter []
  (if (terminal?) (terminal-reporter *err*) (plain-reporter *err*)))
