;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.token
  "The GitHub token: from GITHUB_TOKEN or GH_TOKEN, else from the output of the
  configured :token-command, which runs at most once per process. Both are
  resolved through briko.secret, so the command runs without a shell, under a
  deadline, with its stderr going straight to ours and its output never
  carried in an error."
  (:require [briko.secret :as secret]
            [clojure.string :as str]))

(def env-names ["GITHUB_TOKEN" "GH_TOKEN"])

(def timeout
  "Seconds the :token-command may take, so a password manager waiting for a
  hardware key or an unlock cannot hold up a scheduled run forever."
  60)

(defn- from-env
  "{:token t :source name} of the first variable of `env-names` that is set
  and not blank. briko.secret refuses a variable that is unset or empty; such
  a variable is skipped here, so GH_TOKEN still counts when GITHUB_TOKEN is
  blank."
  [opts]
  (some (fn [name]
          (try {:token (str/trim (secret/resolve1 name {:env name} opts)) :source name}
               (catch clojure.lang.ExceptionInfo e
                 (when-not (#{::secret/unset ::secret/empty} (:type (ex-data e)))
                   (throw e)))))
        env-names))

(defn- command-problem
  "The ex-info telling why the :token-command `command` gave no token, from
  briko.secret's refusal `e`."
  [command e]
  (let [{:keys [type exit]} (ex-data e)
        base {:breto/problem :token-command :command command}]
    (case type
      ::secret/command-timed-out
      (ex-info (str ":token-command " (pr-str command) " ran longer than " timeout " seconds and was stopped")
               (assoc base :timed-out timeout))

      ::secret/command-failed
      (ex-info (str ":token-command " (pr-str command) " exited with " exit)
               (assoc base :exit exit))

      ::secret/empty
      (ex-info (str ":token-command " (pr-str command) " printed no token")
               (assoc base :exit 0 :empty true))

      (ex-info (str ":token-command " (pr-str command) " could not run: " (ex-message e))
               (assoc base :error (ex-message e))))))

(defn resolve-token
  "{:token t :source s} from GITHUB_TOKEN or GH_TOKEN, else from the first line
  `command` prints, or nil when neither yields one. `opts` are briko.secret
  options: :getenv reads the variables and :timeout-ms bounds the command,
  `timeout` seconds unless given."
  ([command] (resolve-token command nil))
  ([command opts]
   (let [opts (merge {:timeout-ms (* 1000 timeout)} opts)]
     (or (from-env opts)
         (when (seq command)
           (let [command (mapv str command)]
             (try {:token (secret/resolve1 ":token-command" {:command command} opts) :source ":token-command"}
                  (catch clojure.lang.ExceptionInfo e
                    (if (secret/refusal? e)
                      (throw (command-problem command e))
                      (throw e))))))))))

(defn provider
  "A function returning the token map, resolving it on first use only. A
  failure is remembered too, so a broken command runs once."
  ([command] (provider command nil))
  ([command opts]
   (let [memo (atom nil)]
     (fn []
       (let [{:keys [value error]} (or @memo
                                       (reset! memo (try {:value (resolve-token command opts)}
                                                         (catch clojure.lang.ExceptionInfo e {:error e}))))]
         (if error (throw error) value))))))
