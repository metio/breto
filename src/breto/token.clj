;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.token
  "The GitHub token: from GITHUB_TOKEN or GH_TOKEN, else from the output of the
  configured :token-command, which runs at most once per process."
  (:require [breto.proc :as proc]
            [clojure.string :as str]))

(def env-names ["GITHUB_TOKEN" "GH_TOKEN"])

(defn resolve-token
  "{:token t :source s} from `env` (a function of a variable name) or from
  running `command` with `run`, or nil when neither yields one."
  [env command run]
  (if-let [name (some #(when-not (str/blank? (env %)) %) env-names)]
    {:token (str/trim (env name)) :source name}
    (when (seq command)
      (let [{:keys [exit out err]} (try (run command)
                                        (catch Exception e
                                          (throw (ex-info (str ":token-command " (pr-str command) " could not run: " (ex-message e))
                                                          {:breto/problem :token-command :command command
                                                           :error (ex-message e)}))))
            token (str/trim (or out ""))]
        (cond
          (not (zero? exit))
          (throw (ex-info (str ":token-command " (pr-str command) " exited with " exit)
                          {:breto/problem :token-command :command command :exit exit
                           :stderr (str/trim (or err ""))}))

          (str/blank? token)
          (throw (ex-info (str ":token-command " (pr-str command) " printed no token")
                          {:breto/problem :token-command :command command :exit exit :empty true}))

          :else {:token (first (str/split-lines token)) :source ":token-command"})))))

(defn provider
  "A function returning the token map, resolving it on first use only. A
  failure is remembered too, so a broken command runs once."
  ([command] (provider #(System/getenv %) command proc/run-command))
  ([env command run]
   (let [memo (atom nil)]
     (fn []
       (let [{:keys [value error]} (or @memo
                                       (reset! memo (try {:value (resolve-token env command run)}
                                                         (catch clojure.lang.ExceptionInfo e {:error e}))))]
         (if error (throw error) value))))))
