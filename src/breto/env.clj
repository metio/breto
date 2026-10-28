;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.env
  "Per-project versions. A .breto.edn file in a project directory or one of
  its parents lists packages, with versions where the project needs them. The
  commands of those versions are linked into a bin directory of the project's
  own, which goes in front of PATH while working on it."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(def file-name ".breto.edn")

(defn find-project
  "The nearest .breto.edn at `dir` or above it, or nil."
  [dir]
  (loop [dir (fs/absolutize dir)]
    (when dir
      (let [file (fs/path dir file-name)]
        (if (fs/regular-file? file) (str file) (recur (fs/parent dir)))))))

(defn read-project
  "The package specs of the project file `file`."
  [file]
  (let [data (try (edn/read-string (slurp file))
                  (catch RuntimeException e
                    (throw (ex-info (str file " is not valid EDN: " (ex-message e))
                                    {:breto/problem :invalid-project :file file
                                     :problems [(str "not valid EDN: " (ex-message e))]}))))
        packages (:packages data)]
    (when-not (and (map? data) (vector? packages) (seq packages) (every? #(and (string? %) (not (str/blank? %))) packages))
      (throw (ex-info (str file " needs :packages, a non-empty vector of PKG, PKG@VERSION or @GROUP")
                      {:breto/problem :invalid-project :file file
                       :problems [":packages must be a non-empty vector of PKG, PKG@VERSION or @GROUP strings"]})))
    packages))

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
     (hold-exactly! (fs/path dir "homes") homes)
     (hold-exactly! bin links)
     (fs/set-last-modified-time bin (java.nio.file.attribute.FileTime/from (java.time.Instant/now)))
     (str bin))))

(defn path-value
  "`path` with the bin directories of every project and repeated entries
  removed and `bin` put in front, so entering another project replaces the
  previous one's entry."
  [{:keys [env-dir]} bin path]
  (let [root (str (fs/absolutize env-dir) "/")]
    (str/join ":" (distinct (cons bin (remove #(or (str/blank? %) (str/starts-with? % root))
                                              (str/split (or path "") #":")))))))

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

(defn exports
  "The shell code that sets PATH to `path`, BRETO_PROJECT to `project-dir`,
  BRETO_ENV_BIN to `bin`, BRETO_ENV_FILES to the colon-separated `files` the
  project's packages come from, and the variables in `vars`, for :sh (also
  bash and zsh) or :fish. The variables of a project entered before get
  their earlier values back first, and the values the new ones replace are
  saved, so leaving the project can restore them; BRETO_ENV_VARS names them."
  ([shell path project-dir bin files] (exports shell path project-dir bin files {}))
  ([shell path project-dir bin files vars]
   (let [names (str/join " " (sort (keys vars)))]
     (case shell
       :fish (str restore-fish
                  (apply str (for [[k v] (sort vars)]
                               (str "set --query " k "; and set --global __breto_saved_" k " $" k ";\n"
                                    "set --global --export " k " " (sh-quote v) ";\n")))
                  "set --global --export BRETO_ENV_VARS " (sh-quote names) ";\n"
                  "set --global --export PATH " (str/join " " (map sh-quote (str/split path #":"))) ";\n"
                  "set --global --export BRETO_PROJECT " (sh-quote project-dir) ";\n"
                  "set --global --export BRETO_ENV_BIN " (sh-quote bin) ";\n"
                  "set --global --export BRETO_ENV_FILES " (sh-quote (str/join ":" files)) ";")
       (str restore-sh
            (apply str (for [[k v] (sort vars)]
                         (str "if [ -n \"${" k "+x}\" ]; then __breto_saved_" k "=$" k "; fi\n"
                              "export " k "=" (sh-quote v) "\n")))
            "export BRETO_ENV_VARS=" (sh-quote names) "\n"
            "export PATH=" (sh-quote path) "\n"
            "export BRETO_PROJECT=" (sh-quote project-dir) "\n"
            "export BRETO_ENV_BIN=" (sh-quote bin) "\n"
            "export BRETO_ENV_FILES=" (sh-quote (str/join ":" files)))))))

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
  (not (and project (fs/regular-file? (fs/path project file-name)))))

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
