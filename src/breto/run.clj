;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.run
  "The commands of a project, run with the project's bin directory first on
  PATH and its variables set. A vector :run starts the program directly; a
  string runs through sh -c. Both are templates of {project}, {bin},
  {install-dirs}, {platform}, {os} and {arch}, with {homes} as the deprecated
  name of {install-dirs}; any other braces, such as a shell's ${var}, are
  left alone. A command with :limits runs in a systemd scope of its own that
  caps its CPU, memory and IO."
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

(def scope-version
  "The first systemd release whose systemd-run can leave the arguments of a
  scope alone; earlier ones would expand a shell's ${var} themselves."
  254)

(defn systemd-version
  "The release of the systemd-run on PATH, or nil."
  []
  (try (some->> (:out (process/shell {:out :string :err :string :continue true} "systemd-run" "--version"))
                (re-find #"^systemd (\d+)") second parse-long)
       (catch Exception _ nil)))

(defn limits-mode
  "How commands with :limits run: :ci skips the limits when CI is set, since a
  CI runner sizes its own machine; :unavailable when no user systemd manager
  of at least scope-version can start a scope; else :on. `getenv` reads a
  variable, `which` finds a program and `version` gives systemd's release."
  ([getenv which] (limits-mode getenv which systemd-version))
  ([getenv which version]
   (let [runtime (getenv "XDG_RUNTIME_DIR")]
     (cond (not (str/blank? (getenv "CI"))) :ci
           (not (and (which "systemd-run") (which "systemctl") (not (str/blank? runtime))
                     (or (fs/exists? (fs/path runtime "systemd" "private"))
                         (fs/exists? (fs/path runtime "bus")))
                     (<= scope-version (or (version) 0))))
           :unavailable
           :else :on))))

(defn scope-properties
  "The systemd properties capping a scope at `limits`. Swap is closed off
  with a memory cap, so that a command that needs more is stopped instead of
  paging the machine to a halt."
  [{:keys [cpus memory io-weight]}]
  (cond-> []
    cpus (conj (str "CPUQuota=" (long (Math/ceil (* 100 cpus))) "%"))
    memory (conj (str "MemoryMax=" memory) "MemorySwapMax=0")
    io-weight (conj (str "IOWeight=" io-weight))))

(defn limited
  "`command` started by systemd-run in the scope `unit`, capped at `limits`."
  [command unit limits]
  (-> ["systemd-run" "--user" "--scope" "--quiet" "--expand-environment=no" (str "--unit=" unit)]
      (into (mapcat #(vector "--property" %)) (scope-properties limits))
      (conj "--")
      (into command)))

(defn scope-name
  "A unit name for a scope running the command `name`, unique to this run."
  [name]
  (str "breto-" (str/replace name #"[^A-Za-z0-9_.-]" "_") "-"
       (subs (str (java.util.UUID/randomUUID)) 0 8)))

(defn- scope-result
  "The Result of the scope `unit` after it ended, such as \"oom-kill\", and
  clears a failed one so that it does not linger in the user manager."
  [unit]
  (let [scope (str unit ".scope")
        result (try (str/trim (:out (process/shell {:out :string :err :string :continue true}
                                                   "systemctl" "--user" "show" "--property=Result" "--value" scope)))
                    (catch Exception _ nil))]
    (when (and result (not= "success" result))
      (try (process/shell {:out :string :err :string :continue true} "systemctl" "--user" "reset-failed" scope)
           (catch Exception _ nil)))
    result))

(defn oom-message
  "What to say when the kernel stopped a process of the command `name` for
  using more memory than its `limits` allow."
  [name {:keys [memory]}]
  (str "[ERROR] the command " name " ran out of memory"
       (when memory (str " under its :limits of " memory))
       "; raise :memory in its :limits, or in the :limits of .breto.local.edn for this machine"))

(defn exec!
  "Runs `command` in the terminal and returns its exit code; with `limits`, in
  a capped systemd scope, reporting a process the kernel stopped for running
  out of memory."
  [{:keys [command env dir name limits]}]
  (let [command (on-path command (get env "PATH"))
        unit (when limits (scope-name name))
        code (:exit @(apply process/process {:inherit true :extra-env env :dir (str dir)}
                            (if limits (limited command unit limits) command)))]
    (when (and limits (= "oom-kill" (scope-result unit)))
      (binding [*out* *err*] (println (oom-message name limits))))
    code))

(defn run-order!
  "Runs each [name command] of `order` that has a :run, the last one with
  `args`, in the project directory or the command's :dir below it, and stops
  at the first that fails. Returns the exit code. `exec` runs one
  invocation, {:command :env :dir :name}, and returns its exit code; the
  invocation carries the command's :limits as well when `limits?`."
  ([order opts] (run-order! order opts exec!))
  ([order {:keys [dir env path args vars limits?]} exec]
   (let [last-name (first (last order))]
     (reduce (fn [_ [name {:keys [run] :as command}]]
               (if-not run
                 0
                 (let [code (exec (cond-> {:command (invocation name command (if (= name last-name) args []) vars)
                                           :env (merge env (render-env (:env command {}) vars) {"PATH" path})
                                           :dir (fs/normalize (fs/path dir (:dir command ".")))
                                           :name name}
                                    (and limits? (:limits command)) (assoc :limits (:limits command))))]
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
