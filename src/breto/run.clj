;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.run
  "The commands of a project, run with the project's bin directory first on
  PATH and its variables set. A vector :run starts the program directly; a
  string runs through sh -c. Both are templates of {project}, {bin},
  {homes}, {platform}, {os} and {arch}; any other braces, such as a shell's
  ${var}, are left alone."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.platform :as platform]
            [clojure.string :as str]))

(defn template-vars
  "The values commands and project variables can refer to."
  [project-dir bin homes platform]
  {"project" (str project-dir) "bin" (str bin) "homes" (str homes)
   "platform" platform "os" (platform/os-of platform) "arch" (platform/arch-of platform)})

(defn render
  "`s` with each {name} of `vars` replaced by its value."
  [s vars]
  (str/replace s #"\{([a-z]+)\}" (fn [[whole k]] (get vars k whole))))

(defn render-env [env vars]
  (update-vals env #(render % vars)))

(defn invocation
  "The program and arguments running `command` named `name` with `args`
  after it: appended to a vector :run, and as \"$@\" of a string one."
  [name {:keys [run]} args vars]
  (if (string? run)
    (into ["sh" "-c" (render run vars) name] args)
    (into (mapv #(render % vars) run) args)))

(defn exec!
  "Runs `command` in the terminal and returns its exit code."
  [{:keys [command env dir]}]
  (:exit @(apply process/process {:inherit true :extra-env env :dir (str dir)} command)))

(defn run-order!
  "Runs each [name command] of `order` that has a :run, the last one with
  `args`, in the project directory or the command's :dir below it, and stops
  at the first that fails. Returns the exit code. `exec` runs one
  invocation, {:command :env :dir}, and returns its exit code."
  ([order opts] (run-order! order opts exec!))
  ([order {:keys [dir env path args vars]} exec]
   (let [last-name (first (last order))]
     (reduce (fn [_ [name {:keys [run] :as command}]]
               (if-not run
                 0
                 (let [code (exec {:command (invocation name command (if (= name last-name) args []) vars)
                                   :env (merge env (render-env (:env command {}) vars) {"PATH" path})
                                   :dir (fs/normalize (fs/path dir (:dir command ".")))})]
                   (if (zero? code) code (reduced code)))))
             0
             order))))

(defn wrapper
  "The script in a project's bin directory that runs its command `name`
  through breto, so the command works like any other in the project's
  shell."
  [project-dir name]
  (str "#!/bin/sh\nexec breto run --dir '" (str/replace (str project-dir) "'" "'\\''") "' '"
       (str/replace name "'" "'\\''") "' \"$@\"\n"))

(defn menu
  "The lines naming the commands of `project`, with their descriptions."
  [{:keys [dir commands]}]
  (when (seq commands)
    (let [width (apply max (map count (keys commands)))]
      (cons (str "breto: commands of " dir ", run with breto run [COMMAND]:")
            (for [[name {:keys [description]}] (sort-by key commands)]
              (str "  " name (when description (str (apply str (repeat (- (inc width) (count name)) " ")) description))))))))
