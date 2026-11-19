;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.share
  "Man pages, shell completions and desktop entries of a package, linked
  where man, the shells and the desktop look for them. Files from the release
  keep their path inside the installed version; completions a command prints
  are kept under .breto/completions in it, and the desktop entry breto writes
  under .breto/desktop. Every link goes through the package's `current` link,
  so switching versions switches them too."
  (:require [babashka.fs :as fs]
            [breto.sealed :as sealed]
            [clojure.string :as str]))

(def shells #{:bash :zsh :fish})

(def ^:private link-name
  "The file name each shell looks a command's completions up under."
  {:bash identity
   :zsh #(str "_" %)
   :fish #(str % ".fish")})

(defn generated-path [shell]
  (str ".breto/completions/" (name shell)))

(defn command
  "The command whose completions the package provides: :command under
  :completions, else the package's own name when it links a command of that
  name, else its first command."
  [{:keys [name bins completions]}]
  (or (:command completions)
      (when (contains? bins name) name)
      (first (sort (keys bins)))))

(defn shipped-completions
  "The paths of the completion files the release ships."
  [{:keys [completions]}]
  (filter string? (vals (select-keys completions shells))))

(defn shipped-files
  "The paths of the files the release must ship besides the binaries: the
  completion files and the icon of the desktop entry."
  [pkg]
  (concat (shipped-completions pkg)
          (some-> (get-in pkg [:desktop :icon]) vector)))

(defn kept-files
  "Globs of the files the binary layout keeps besides the binaries: the man
  pages and the files the release must ship."
  [{:keys [man] :as pkg}]
  (concat man (shipped-files pkg)))

(defn man-section
  "The section a man page belongs to, from its name: ortie.1.gz is in 1."
  [file-name]
  (some-> (re-find #"\.([1-9n])[a-z]*$" (str/replace file-name #"\.(gz|bz2|xz|zst)$" ""))
          second))

(def desktop-path ".breto/desktop/entry.desktop")

(defn desktop-entries-problems
  "What is wrong with the :desktop-entries of `config`."
  [{:keys [desktop-entries]}]
  (when-not (or (nil? desktop-entries) (boolean? desktop-entries)
                (and (map? desktop-entries) (= #{:except} (set (keys desktop-entries)))
                     (sequential? (:except desktop-entries))
                     (every? string? (:except desktop-entries))))
    [":desktop-entries must be true, false or {:except [package names]}"]))

(defn desktop-dir
  "The directory the desktop entry of `pkg` is linked into, or nil when `pkg`
  declares none or the config turns it off: :desktop-entries false turns off
  every entry, {:except [names]} those of the named packages, and a value
  breto does not understand every entry as well."
  [{:keys [desktop-entries] :as config} pkg]
  (when (and (:desktop pkg) (:desktop-dir config)
             (empty? (desktop-entries-problems config))
             (or (nil? desktop-entries) (true? desktop-entries)
                 (and (map? desktop-entries)
                      (not-any? #{(:name pkg)} (:except desktop-entries)))))
    (:desktop-dir config)))

(defn desktop-command
  "The command the desktop entry of `pkg` starts: :command under :desktop,
  else the package's own name when it links a command of that name, else its
  first command."
  [{:keys [name desktop]} bin-paths]
  (or (:command desktop)
      (when (contains? bin-paths name) name)
      (first (sort (keys bin-paths)))))

(defn- escape-string
  "`s` as the value of a string key of a desktop entry."
  [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\n" "\\n")
      (str/replace "\t" "\\t")
      (str/replace "\r" "\\r")))

(defn- quote-argument
  "`arg` as one argument of the Exec key, quoted when it holds a reserved
  character. A literal percent sign is doubled, since Exec reads % as the
  start of a field code."
  [arg]
  (let [arg (str/replace arg "%" "%%")]
    (if (re-find #"[\s\"'\\><~|&;$*?#()`]" arg)
      (str "\"" (str/replace arg #"[\"`$\\]" #(str "\\" %)) "\"")
      arg)))

(defn- exec-argument
  "An argument of the Exec key from the definition: a field code such as %U
  stays as it is, anything else is quoted."
  [arg]
  (if (re-matches #"%[fFuUick]" arg) arg (quote-argument arg)))

(defn- list-value [items]
  (apply str (map #(str (escape-string %) ";") items)))

(defn desktop-entry
  "The text of the desktop entry of `pkg`, which starts `program`, the link
  of its command in the bin directory, and shows the icon inside `current`."
  [pkg program current]
  (let [{entry-name :name :keys [comment categories mime-types icon args terminal wm-class]} (:desktop pkg)
        exec (str/join " " (cons (quote-argument (str program)) (map exec-argument args)))]
    (str/join
     (for [[k v] [[nil "[Desktop Entry]"]
                  ["Type" "Application"]
                  ["Name" (escape-string entry-name)]
                  ["Comment" (some-> comment escape-string)]
                  ["TryExec" (escape-string (str program))]
                  ["Exec" (escape-string exec)]
                  ["Icon" (some-> icon (->> (fs/path current) str escape-string))]
                  ["Terminal" (if terminal "true" "false")]
                  ["Categories" (some-> (seq categories) list-value)]
                  ["MimeType" (some-> (seq mime-types) list-value)]
                  ["StartupWMClass" (some-> wm-class escape-string)]
                  ["X-Breto-Package" (escape-string (:name pkg))]]
           :when v]
       (str (when k (str k "=")) v "\n")))))

(defn write-desktop!
  "Writes the desktop entry of `pkg` into `version-dir` when the config wants
  one and its text differs from what is there, and deletes it when the config
  does not want one, so its link dangles and is removed. The command is
  looked up in `bin-paths`; without one, no entry is written. `program-of`
  turns a command name into the path the entry starts."
  [config pkg version-dir current bin-paths program-of]
  (let [target (fs/path version-dir desktop-path)
        command (desktop-command pkg bin-paths)
        text (when (and (desktop-dir config pkg) command)
               (desktop-entry pkg (program-of command) current))]
    (cond
      (and text (not (and (fs/regular-file? target) (= text (slurp (str target))))))
      (sealed/writing! version-dir target
        #(do (fs/create-dirs (fs/parent target))
             (fs/delete-if-exists target)
             (spit (str target) text)))

      (and (nil? text) (fs/exists? target))
      (sealed/writing! version-dir target
        #(fs/delete target)))))

(defn links
  "Link path -> target for the man pages, completions and desktop entry of
  `pkg` that exist in `version-dir`, each target inside `current`."
  [{:keys [man-dir completion-dirs] :as config} pkg version-dir current]
  (concat
   (when man-dir
     (for [pattern (:man pkg)
           file (sort (fs/glob version-dir pattern))
           :let [section (man-section (str (fs/file-name file)))]
           :when (and section (fs/regular-file? file))]
       [(str (fs/path man-dir (str "man" section) (fs/file-name file)))
        (str (fs/path current (fs/relativize version-dir file)))]))
   (for [[shell dir] (sort completion-dirs)
         :let [spec (get-in pkg [:completions shell])
               rel (if (string? spec) spec (generated-path shell))]
         :when (and spec (fs/regular-file? (fs/path version-dir rel)))]
     [(str (fs/path dir ((link-name shell) (command pkg))))
      (str (fs/path current rel))])
   (when-let [dir (desktop-dir config pkg)]
     (when (fs/regular-file? (fs/path version-dir desktop-path))
       [[(str (fs/path dir (str "breto-" (:name pkg) ".desktop")))
         (str (fs/path current desktop-path))]]))))

(defn link-dirs
  "The directories breto links man pages, completions and desktop entries
  into under `config`."
  [{:keys [man-dir completion-dirs desktop-dir]}]
  (concat (when man-dir
            (when (fs/directory? man-dir)
              (filter #(re-matches #"man[1-9n]" (str (fs/file-name %))) (fs/list-dir man-dir))))
          (vals completion-dirs)
          (when desktop-dir [desktop-dir])))

(defn generate!
  "Runs the command at `program` for each configured shell whose completions
  `pkg` generates and that `version-dir` does not hold yet, writing what it
  prints. `run` runs {:command :env :timeout}. Returns a sentence for each
  shell that failed; a failure never fails the install."
  [{:keys [completion-dirs]} pkg program version-dir run]
  (doall
   (for [shell (sort (keys completion-dirs))
         :let [{:keys [args env] :as spec} (get-in pkg [:completions shell])
               target (fs/path version-dir (generated-path shell))]
         :when (and (map? spec) (not (fs/exists? target)))
         :let [problem (try
                         (let [{:keys [exit out err timed-out]}
                               (run {:command (into [(str program)] args) :env (or env {}) :timeout 30})]
                           (cond
                             timed-out "it ran longer than 30 seconds"
                             (not= 0 exit) (str "it exited with " exit (when-not (str/blank? err) (str ": " (str/trim err))))
                             (str/blank? out) "it printed nothing"
                             :else (sealed/writing! version-dir target
                                   #(do (fs/create-dirs (fs/parent target))
                                        (spit (str target) out)
                                        nil))))
                         (catch Exception e (ex-message e)))]
         :when problem]
     (str (name shell) " completions of " (command pkg) " could not be generated: " problem))))
