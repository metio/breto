;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.hooks
  "Commands that install and update run on their events: a package installed,
  a package failed, the run finished, and any command that changes the store
  on rewriting the lock file. A hook is a vector of a program and its
  arguments whose {placeholders} are filled from the event; the same values are
  in BRETO_* environment variables. Hooks never run through a shell, so a
  version taken from an upstream tag cannot turn into a command. The hook
  :notify sends the event's message with the desktop notifier :notifier names,
  where :auto picks one for the operating system."
  (:require [breto.report :as report]
            [breto.template :as template]
            [clojure.string :as str]))

(def events #{:installed :failed :finished :locked})

(def notifications
  "The title, default message and urgency of the notification for each event."
  {:installed {:title "breto" :message "{package} {version} installed" :urgency "low"}
   :failed {:title "breto: {package} failed" :message "{summary}" :urgency "normal"}
   :finished {:title "breto {command} finished" :message "{summary}" :urgency "low"}
   :locked {:title "breto {command}" :message "{lockfile} updated" :urgency "low"}})

(def ^:private osascript
  "AppleScript that shows its first argument as the title and its second as the
  message, so neither is ever parsed as script."
  ["osascript" "-e" "on run argv" "-e" "display notification (item 2 of argv) with title (item 1 of argv)"
   "-e" "end run" "{title}" "{message}"])

(def ^:private notify-send
  ["notify-send" "--app-name=breto" "--urgency={urgency}" "{title}" "{message}"])

(defn auto-notifier
  "The notifier for the operating system named `os` (the os.name property), nil
  when it has none breto knows or `which` cannot find its program."
  [os which]
  (let [os (str/lower-case (or os ""))
        candidate (cond
                    (str/starts-with? os "mac") osascript
                    (str/starts-with? os "windows") nil
                    :else notify-send)]
    (when (and candidate (which (first candidate)))
      candidate)))

(defn notifier
  "The notifier command `config` selects, nil when there is none."
  [{:keys [notifier]} os which]
  (if (= :auto notifier) (auto-notifier os which) notifier))

(defn- command? [x]
  (and (vector? x) (seq x) (every? string? x)))

(defn problems
  "What is wrong with the hook settings of `config`, as sentences."
  [{:keys [hooks notifier messages hook-timeout]}]
  (cond-> []
    (not (map? hooks))
    (conj ":hooks must be a map of events to commands")

    (map? hooks)
    (into (for [[event hook] hooks
                :let [problem (cond
                                (not (events event)) (str "is not an event; use one of " (str/join ", " (sort events)))
                                (not (or (= :notify hook) (command? hook))) "must be :notify or a vector of strings")]
                :when problem]
            (str ":hooks " (pr-str event) " " problem)))

    (not (or (= :auto notifier) (nil? notifier) (command? notifier)))
    (conj ":notifier must be :auto, nil or a vector of strings")

    (not (and (map? messages) (every? events (keys messages)) (every? string? (vals messages))))
    (conj ":messages must map events to strings")

    (not (pos-int? hook-timeout))
    (conj ":hook-timeout must be a positive number of seconds")))

(defn- env [vars]
  (into {} (map (fn [[k v]] [(str "BRETO_" (str/upper-case (str/replace (name k) "-" "_"))) (str v)])) vars))

(defn- render [command vars]
  (try (mapv #(template/render % vars) command)
       (catch clojure.lang.ExceptionInfo e
         (throw (ex-info (str "the hook " (pr-str command) " uses " (ex-message e))
                         {:breto/problem :hook :command command})))))

(defn- notification-vars [config event vars]
  (let [{:keys [title message urgency]} (notifications event)]
    (assoc vars
           :title (template/render title vars)
           :message (template/render (get-in config [:messages event] message) vars)
           :urgency urgency)))

(defn invocation
  "{:command :env} for `event` with `vars`, or nil when no hook is configured
  for it. `notifier` is the command :notify sends with."
  [config notifier event vars]
  (when-let [hook (get (:hooks config) event)]
    (if (= :notify hook)
      (let [vars (notification-vars config event vars)]
        (when-not notifier
          (throw (ex-info "no desktop notifier is available"
                          {:breto/problem :hook :command nil :no-notifier true})))
        {:command (render notifier vars) :env (env vars)})
      {:command (render hook vars) :env (env vars)})))

(defn- event-vars [command event {:keys [package version diagnosis]}]
  (case event
    (:installed :failed) {:event (name event) :command command :package package :version (or version "")
                          :stage (or (some-> (:stage diagnosis) name) "") :summary (or (:summary diagnosis) "")}))

(defn- finished-vars [command results]
  (let [counts (frequencies (map :status results))]
    {:event "finished" :command command :summary (report/summary-line command results)
     :installed (get counts :installed 0) :failed (get counts :failed 0)
     :current (get counts :current 0)}))

(defn- describe-failure [command {:keys [exit err timed-out]} timeout]
  (str "the hook " (str/join " " command)
       (cond timed-out (str " ran longer than " timeout " seconds and was stopped")
             :else (str " exited with " exit (when-not (str/blank? err) (str ": " (str/trim err)))))))

(defn fire!
  "Runs the hook of `config` for `event` with `vars`, if one is configured.
  `run-hook` runs {:command :env :timeout} and returns {:exit :err :timed-out};
  `warn` receives a sentence when the hook fails. A failing hook never fails
  the command."
  [config event vars {:keys [run-hook warn os which]}]
  (let [timeout (:hook-timeout config)]
    (try
      (when-let [{:keys [command] :as inv} (invocation config (notifier config os which) event vars)]
        (let [result (run-hook (assoc inv :timeout timeout))]
          (when-not (zero? (or (:exit result) -1))
            (warn (describe-failure command result timeout)))))
      (catch Exception e
        (warn (str "the " (name event) " hook did not run: " (ex-message e)))))))

(defn locked!
  "Runs the :locked hook of `config` after `command` rewrote the lock file at
  `lockfile`, unless the hook settings are invalid; the listener of the
  command already warned about those."
  [config command lockfile deps]
  (when (empty? (problems config))
    (fire! config :locked {:event "locked" :command command :lockfile (str lockfile)} deps)))

(defn listener
  "A progress callback that runs the hooks of `config` for `command`; see
  fire!."
  [config command {:keys [warn] :as deps}]
  (let [wrong (problems config)
        results (atom [])
        fire #(fire! config %1 %2 deps)]
    (cond
      (seq wrong)
      (do (warn (str "no hooks run: " (str/join "; " wrong))) (fn [_]))

      (empty? (:hooks config))
      (fn [_])

      :else
      (fn [{:keys [type result]}]
        (case type
          :finish (do (swap! results conj result)
                      (case (:status result)
                        :installed (fire :installed (event-vars command :installed result))
                        :failed (fire :failed (event-vars command :failed result))
                        nil))
          :end (fire :finished (finished-vars command @results))
          nil)))))
