;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.env
  "Per-project versions. The project files of a directory or one of its
  parents list packages, with versions where the project needs them. The
  commands of those versions, and the project's own commands, are linked
  into a bin directory of the project's own, which goes in front of PATH
  while working on it."
  (:require [babashka.fs :as fs]
            [breto.project :as project]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(defn env-dir
  "The directory holding the links of the project at `project-dir`, named by a
  hash of its path."
  [{:keys [env-dir]} project-dir]
  (let [digest (->> (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str (fs/absolutize project-dir)) "UTF-8"))
                    (map #(format "%02x" (bit-and % 0xff)))
                    (apply str))]
    (fs/path env-dir (subs digest 0 16))))

(defn bin-dir [config project-dir]
  (fs/path (env-dir config project-dir) "bin"))

(defn home
  "The link to the version directory of `package` that the project at
  `project-dir` uses. Its path stays the same when the version changes, so an
  exported variable pointing at it never goes stale."
  [config project-dir package]
  (fs/path (env-dir config project-dir) "homes" package))

(defn- hold-exactly!
  "Makes `dir` hold exactly `links`, link name -> target, each replaced
  atomically."
  [dir links]
  (fs/create-dirs dir)
  (doseq [existing (fs/list-dir dir)
          :when (not (contains? links (str (fs/file-name existing))))]
    (fs/delete existing))
  (doseq [[link target] links
          :let [path (fs/path dir link)
                staged (fs/path dir (str "." link ".breto"))]]
    (fs/delete-if-exists staged)
    (fs/create-sym-link staged (str target))
    (fs/move staged path {:replace-existing true :atomic-move true})))

(defn homes-dir
  "The directory of the project's links to its packages' version directories."
  [config project-dir]
  (fs/path (env-dir config project-dir) "homes"))

(defn write-commands!
  "Makes the commands directory of `project-dir` hold exactly `scripts`,
  name -> script text, each executable, and returns name -> path for
  linking them into the bin directory."
  [config project-dir scripts]
  (let [dir (fs/path (env-dir config project-dir) "commands")]
    (fs/create-dirs dir)
    (doseq [existing (fs/list-dir dir)
            :when (not (contains? scripts (str (fs/file-name existing))))]
      (fs/delete existing))
    (into {} (for [[name text] scripts
                   :let [path (fs/path dir name)
                         staged (fs/path dir (str "." name ".breto"))]]
               (do (spit (str staged) text)
                   (fs/set-posix-file-permissions staged "rwxr-xr-x")
                   (fs/move staged path {:replace-existing true :atomic-move true})
                   [name (str path)])))))

(defn link!
  "Makes the bin directory of `project-dir` hold exactly `links`, link name ->
  target, its homes directory exactly `homes`, package -> version directory,
  and records which project it belongs to. The bin directory's modification
  time is the time of the run, so a shell hook can tell whether the project
  file changed since."
  ([config project-dir links] (link! config project-dir links {}))
  ([config project-dir links homes]
   (let [dir (env-dir config project-dir)
         bin (fs/path dir "bin")]
     (fs/create-dirs dir)
     (spit (str (fs/path dir "project")) (str (fs/absolutize project-dir)))
     (hold-exactly! (homes-dir config project-dir) homes)
     (hold-exactly! bin links)
     (fs/set-last-modified-time bin (java.nio.file.attribute.FileTime/from (java.time.Instant/now)))
     (str bin))))

(defn- outside-projects
  "The entries of `path` that are no bin directory of a project, without
  blanks and repeats."
  [{:keys [env-dir]} path]
  (let [root (str (fs/absolutize env-dir) "/")]
    (distinct (remove #(or (str/blank? %) (str/starts-with? % root))
                      (str/split (or path "") #":")))))

(defn path-value
  "`path` with the bin directories of every project and repeated entries
  removed and `bin` put in front, so entering another project replaces the
  previous one's entry."
  [config bin path]
  (str/join ":" (distinct (cons bin (outside-projects config path)))))

(defn path-outside
  "`path` with the bin directories of every project and repeated entries
  removed."
  [config path]
  (str/join ":" (outside-projects config path)))

(defn- sh-quote [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(def ^:private restore-sh
  "Gives each variable the previous breto env set its value from before that
  run back, or unsets it."
  (str "for __breto_var in ${BRETO_ENV_VARS-}; do\n"
       "  if eval \"[ -n \\\"\\${__breto_saved_$__breto_var+x}\\\" ]\"; then\n"
       "    eval \"export $__breto_var=\\\"\\$__breto_saved_$__breto_var\\\"; unset __breto_saved_$__breto_var\"\n"
       "  else\n"
       "    unset \"$__breto_var\"\n"
       "  fi\n"
       "done\n"
       "unset __breto_var\n"))

(def ^:private restore-fish
  (str "for __breto_var in (string split --no-empty ' ' -- \"$BRETO_ENV_VARS\");\n"
       "  set --local __breto_saved __breto_saved_$__breto_var;\n"
       "  if set --query $__breto_saved;\n"
       "    set --global --export $__breto_var $$__breto_saved; set --erase $__breto_saved;\n"
       "  else;\n"
       "    set --erase $__breto_var;\n"
       "  end;\n"
       "end;\n"
       "set --erase __breto_var;\n"))

(defn- env-file-line
  "`k` set to `v` in a GitHub Actions environment file, through a delimiter
  the value does not contain when it spans lines."
  [k v]
  (if (str/includes? v "\n")
    (let [delimiter (first (remove #(str/includes? v %) (map #(str "BRETO_EOF_" %) (range))))]
      (str k "<<" delimiter "\n" v "\n" delimiter))
    (str k "=" v)))

(defn exports
  "The shell code that sets PATH to `path`, BRETO_PROJECT to `project-dir`,
  BRETO_ENV_BIN to `bin`, BRETO_ENV_FILES to the colon-separated `files` the
  project's packages come from, and the variables in `vars`, for :sh (also
  bash and zsh) or :fish. :github prints the lines of a GitHub Actions
  environment file instead and leaves PATH to $GITHUB_PATH. The variables of a project entered before get
  their earlier values back first, and the values the new ones replace are
  saved, exported so that a child shell can restore them as well;
  BRETO_ENV_VARS names them."
  ([shell path project-dir bin files] (exports shell path project-dir bin files {}))
  ([shell path project-dir bin files vars]
   (let [names (str/join " " (sort (keys vars)))]
     (case shell
       :github (str/join "\n" (for [[k v] (concat (sort vars)
                                                    [["BRETO_ENV_VARS" names] ["BRETO_PROJECT" project-dir]
                                                     ["BRETO_ENV_BIN" bin] ["BRETO_ENV_FILES" (str/join ":" files)]])]
                                 (env-file-line k (str v))))
       :fish (str restore-fish
                  (apply str (for [[k v] (sort vars)]
                               (str "set --query " k "; and set --global --export __breto_saved_" k " $" k ";\n"
                                    "set --global --export " k " " (sh-quote v) ";\n")))
                  "set --global --export BRETO_ENV_VARS " (sh-quote names) ";\n"
                  "set --global --export PATH " (str/join " " (map sh-quote (str/split path #":"))) ";\n"
                  "set --global --export BRETO_PROJECT " (sh-quote project-dir) ";\n"
                  "set --global --export BRETO_ENV_BIN " (sh-quote bin) ";\n"
                  "set --global --export BRETO_ENV_FILES " (sh-quote (str/join ":" files)) ";")
       (str restore-sh
            (apply str (for [[k v] (sort vars)]
                         (str "if [ -n \"${" k "+x}\" ]; then export __breto_saved_" k "=\"$" k "\"; fi\n"
                              "export " k "=" (sh-quote v) "\n")))
            "export BRETO_ENV_VARS=" (sh-quote names) "\n"
            "export PATH=" (sh-quote path) "\n"
            "export BRETO_PROJECT=" (sh-quote project-dir) "\n"
            "export BRETO_ENV_BIN=" (sh-quote bin) "\n"
            "export BRETO_ENV_FILES=" (sh-quote (str/join ":" files)))))))

(defn leaving
  "The shell code that leaves any project: the variables a project replaced
  get their earlier values back, PATH becomes `path` and the variables naming
  the project are unset. :github needs none, since a job enters one project."
  [shell path]
  (case shell
    :github ""
    :fish (str restore-fish
               "set --global --export PATH " (str/join " " (map sh-quote (str/split path #":"))) ";\n"
               "set --erase BRETO_ENV_VARS BRETO_PROJECT BRETO_ENV_BIN BRETO_ENV_FILES;")
    (str restore-sh
         "export PATH=" (sh-quote path) "\n"
         "unset BRETO_ENV_VARS BRETO_PROJECT BRETO_ENV_BIN BRETO_ENV_FILES")))

(defn environments
  "Every project bin directory: {:dir :project :bin}, :project nil when its
  record is missing."
  [{:keys [env-dir]}]
  (if (and env-dir (fs/directory? env-dir))
    (for [dir (sort (fs/list-dir env-dir))
          :when (fs/directory? dir)
          :let [record (fs/path dir "project")]]
      {:dir (str dir)
       :project (when (fs/regular-file? record) (str/trim (slurp (str record))))
       :bin (str (fs/path dir "bin"))})
    []))

(defn stale?
  "Whether the project of an environment no longer has a project file."
  [{:keys [project]}]
  (not (and project (seq (project/files project)))))

(defn used-versions
  "Package name -> the set of versions some project's links point into, for
  the projects that still have a project file."
  [{:keys [store] :as config}]
  (let [store (str (fs/absolutize store) "/")]
    (reduce (fn [acc link]
              (let [target (str (fs/read-link link))]
                (if (str/starts-with? target store)
                  (let [[name version] (str/split (subs target (count store)) #"/")]
                    (if (and name version) (update acc name (fnil conj #{}) version) acc))
                  acc)))
            {}
            (for [env (remove stale? (environments config))
                  dir [(:bin env) (str (fs/path (:dir env) "homes"))]
                  :when (fs/directory? dir)
                  link (fs/list-dir dir)
                  :when (fs/sym-link? link)]
              link))))
