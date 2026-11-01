;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.run
  "The commands of a project, run with the project's bin directory first on
  PATH and its variables set. A vector :run starts the program directly; a
  string runs through sh -c. Both are templates of {project}, {bin},
  {install-dirs}, {platform}, {os} and {arch}, with {homes} as the deprecated
  name of {install-dirs}; any other braces, such as a shell's ${var}, are
  left alone."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.platform :as platform]
            [breto.shell :as shell]
            [clojure.string :as str]))

(defn template-vars
  "The values commands and project variables can refer to."
  [project-dir bin homes platform]
  {"project" (str project-dir) "bin" (str bin) "install-dirs" (str homes) "homes" (str homes)
   "platform" platform "os" (platform/os-of platform) "arch" (platform/arch-of platform)})

(defn render
  "`s` with each {name} of `vars` replaced by its value."
  [s vars]
  (str/replace s #"\{([a-z-]+)\}" (fn [[whole k]] (get vars k whole))))

(defn render-env [env vars]
  (update-vals env #(render % vars)))

(defn invocation
  "The program and arguments running `command` named `name` with `args`
  after it: appended to a vector :run, and as \"$@\" of a string one."
  [name {:keys [run]} args vars]
  (if (string? run)
    (into ["sh" "-c" (render run vars) name] args)
    (into (mapv #(render % vars) run) args)))

(defn- on-path
  "`command` with its program found on `path`, since a new process looks a
  bare program name up on the PATH of breto, not on the PATH it is given."
  [[program & args :as command] path]
  (if-let [found (when (and path (not (str/includes? program "/")))
                   (some (fn [dir]
                           (let [f (fs/path dir program)]
                             (when (and (fs/regular-file? f) (fs/executable? f)) (str f))))
                         (str/split path (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))))]
    (into [found] args)
    command))

(defn exec!
  "Runs `command` in the terminal and returns its exit code."
  [{:keys [command env dir]}]
  (:exit @(apply process/process {:inherit true :extra-env env :dir (str dir)} (on-path command (get env "PATH")))))

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
  (str "#!/bin/sh\nexec breto run --dir " (shell/quote-arg project-dir) " "
       (shell/quote-arg name) " \"$@\"\n"))

(defn program?
  "Whether `file` starts like something the system runs: a script or an ELF or
  Mach-O binary. Packages also link data, such as a jar a command names on a
  classpath, which has to stay readable as itself at its path in the bin
  directory."
  [file]
  (with-open [in (java.io.FileInputStream. (str file))]
    (let [head (byte-array 4)
          magic (vec (map #(bit-and % 0xff) (take (.read in head) head)))]
      (or (= [0x23 0x21] (take 2 magic))
          (contains? #{[0x7f 0x45 0x4c 0x46]
                       [0xfe 0xed 0xfa 0xce] [0xfe 0xed 0xfa 0xcf]
                       [0xce 0xfa 0xed 0xfe] [0xcf 0xfa 0xed 0xfe]
                       [0xca 0xfe 0xba 0xbe]}
                     magic)))))

(defn tool-wrapper
  "The script in a project's bin directory that runs the command `name` of one
  of its packages: `target` directly while `stale-test` is false, else through
  breto exec, which brings the project up to date and runs this script again
  with BRETO_LINKED set, so that the second run cannot loop. The variable is
  unset before the tool starts, so the tools it runs check for themselves. A
  project pinning breto itself links it without this script, whose call to
  breto would find the script again."
  [project-dir name target stale-test]
  (str "#!/bin/sh\n"
       "if [ -z \"${BRETO_LINKED-}\" ] && { " stale-test "; }; then\n"
       "  exec breto exec --dir " (shell/quote-arg project-dir) " " (shell/quote-arg name) " \"$@\"\n"
       "fi\n"
       "unset BRETO_LINKED\n"
       "exec " (shell/quote-arg target) " \"$@\"\n"))

(defn menu
  "The lines naming the commands of `project`, with their descriptions."
  [{:keys [dir commands]}]
  (when (seq commands)
    (let [width (apply max (map count (keys commands)))]
      (cons (str "breto: commands of " dir ", run with breto run [COMMAND]:")
            (for [[name {:keys [description]}] (sort-by key commands)]
              (str "  " name (when description (str (apply str (repeat (- (inc width) (count name)) " ")) description))))))))
