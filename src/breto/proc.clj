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
