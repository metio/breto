;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.share
  "Man pages and shell completions of a package, linked where man and the
  shells look for them. Files from the release keep their path inside the
  installed version; completions a command prints are kept under
  .breto/completions in it. Every link goes through the package's `current`
  link, so switching versions switches them too."
  (:require [babashka.fs :as fs]
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

(defn kept-files
  "Globs of the files the binary layout keeps besides the binaries: the man
  pages and the completions the release ships."
  [{:keys [man completions]}]
  (concat man (filter string? (vals (select-keys completions shells)))))

(defn man-section
  "The section a man page belongs to, from its name: ortie.1.gz is in 1."
  [file-name]
  (some-> (re-find #"\.([1-9n])[a-z]*$" (str/replace file-name #"\.(gz|bz2|xz|zst)$" ""))
          second))

(defn links
  "Link path -> target for the man pages and completions of `pkg` that exist
  in `version-dir`, each target inside `current`."
  [{:keys [man-dir completion-dirs]} pkg version-dir current]
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
      (str (fs/path current rel))])))

(defn link-dirs
  "The directories breto links man pages and completions into under `config`."
  [{:keys [man-dir completion-dirs]}]
  (concat (when man-dir
            (when (fs/directory? man-dir)
              (filter #(re-matches #"man[1-9n]" (str (fs/file-name %))) (fs/list-dir man-dir))))
          (vals completion-dirs)))

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
                             :else (do (fs/create-dirs (fs/parent target))
                                       (spit (str target) out)
                                       nil)))
                         (catch Exception e (ex-message e)))]
         :when problem]
     (str (name shell) " completions of " (command pkg) " could not be generated: " problem))))
