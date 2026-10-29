;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.cli
  "The entry point. It prints a shell hook without loading the rest of breto,
  since every new shell asks for one, and hands every other command to
  breto.main."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private hooks
  "The shell hook of each shell, a file on the classpath."
  {"bash" "env-hook.bash"})

(defn print-hook
  "Prints the hook of the shell `args` names, returning the exit code."
  [[shell]]
  (if-let [text (some-> (hooks shell) io/resource slurp)]
    (do (print text) (flush) 0)
    (binding [*out* *err*]
      (println (str "[ERROR] breto hook takes one of: " (str/join ", " (sort (keys hooks)))))
      2)))

(defn -main [& args]
  (if (= "hook" (first args))
    (System/exit (print-hook (rest args)))
    (apply (requiring-resolve 'breto.main/-main) args)))
