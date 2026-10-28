;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.proc
  (:require [babashka.process :as process]))

(defn run-command
  "Runs `command`, a sequence of program and arguments, returning its exit code
  and output. A program that cannot start throws."
  [command]
  (select-keys (process/shell {:out :string :err :string :continue true} (map str command))
               [:exit :out :err]))

(defn run-bounded
  "Runs `command` with `env` added to the environment and waits at most
  `timeout` seconds; a command still running then is stopped. A program that
  cannot start throws."
  [{:keys [command env timeout]}]
  (let [p (apply process/process {:out :string :err :string :extra-env env} (map str command))
        result (deref p (* 1000 timeout) ::timeout)]
    (if (= ::timeout result)
      (do (process/destroy p) {:exit nil :out "" :err "" :timed-out true})
      (select-keys result [:exit :out :err]))))
