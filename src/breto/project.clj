;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.project
  "The project files of a directory: .breto.edn, shared with everyone working
  on the project, and .breto.local.edn, kept out of version control for the
  settings of one machine. Both may list :packages, set :env and define
  :commands; the local file is read over the shared one. The groups a
  project names contribute their own :env and :commands beneath the
  project's."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.platform :as platform]
            [breto.registry :as registry]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def file-name ".breto.edn")

(def local-file-name ".breto.local.edn")

(def file-names
  "The names of the project files, shared before local, the order the local
  file is read over the shared one in."
  [file-name local-file-name])

(defn candidates
  "The absolute paths both project files of `dir` would have, existing or
  not, shared before local."
  [dir]
  (mapv #(str (fs/absolutize (fs/path dir %))) file-names))

(def ^:private keys-allowed #{:packages :env :commands :menu :on-enter})

(defn- has-file? [dir]
  (some fs/regular-file? (candidates dir)))

(defn find-dir
  "The nearest directory at `dir` or above it holding a project file, or nil.
  The current directory is named as the shell names it, by PWD when that is
  the same directory, so that a project reached through a symlink keeps the
  name the env hook gives it."
  ([dir] (find-dir dir (System/getenv) (System/getProperty "user.dir")))
  ([dir env cwd]
   (let [pwd (get env "PWD")
         start (if (and (= "." (str dir)) pwd (fs/absolute? pwd) (fs/exists? pwd) (fs/same-file? pwd cwd))
                 pwd
                 (.resolve (fs/path cwd) (str dir)))]
     (loop [dir (fs/normalize start)]
       (when dir
         (if (has-file? dir) (str dir) (recur (fs/parent dir))))))))

(defn files
  "The project files of `dir` that exist, shared before local."
  [dir]
  (filterv fs/regular-file? (candidates dir)))

(defn- env-problems [env]
  (when-not (and (map? env)
                 (every? (fn [[k v]] (and (string? k) (re-matches #"[A-Za-z_][A-Za-z0-9_]*" k)
                                          (not= "PATH" k) (not (str/starts-with? k "BRETO_"))
                                          (string? v)))
                         env))
    [":env maps variable names other than PATH and BRETO_* to strings"]))

(defn- run-valid? [run]
  (or (and (string? run) (not (str/blank? run)))
      (and (vector? run) (seq run) (every? string? run))))

(defn- command-problems [name {:keys [run depends description env platforms dir] :as command}]
  (let [entry-valid? (fn [e] (and (map? e) (every? #{:run :env} (keys e))
                                  (or (nil? (:run e)) (run-valid? (:run e)))
                                  (empty? (env-problems (:env e {})))))]
    (cond-> []
      (not (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" (str name)))
      (conj (str "command name " (pr-str name) " needs letters, digits, ., _ and - only"))

      (not (map? command))
      (conj (str "command " name " must be a map with :run, :depends or both"))

      (and (map? command) (nil? run) (empty? depends))
      (conj (str "command " name " needs :run, :depends or both"))

      (and (some? run) (not (run-valid? run)))
      (conj (str "command " name ": :run is a shell line or a vector of program and arguments"))

      (and (some? depends) (not (and (vector? depends) (every? string? depends))))
      (conj (str "command " name ": :depends is a vector of command names"))

      (and (some? description) (not (string? description)))
      (conj (str "command " name ": :description is a string"))

      (and (some? dir) (not (and (string? dir) (not (fs/absolute? dir)))))
      (conj (str "command " name ": :dir is a directory relative to the project"))

      (some? env) (into (map #(str "command " name ": " %) (env-problems env)))

      (and (some? platforms) (not (and (map? platforms) (every? platform/known (keys platforms))
                                       (every? entry-valid? (vals platforms)))))
      (conj (str "command " name ": :platforms maps " (str/join ", " (sort platform/known))
                 " to maps of :run and :env")))))

(defn problems
  "What is wrong with the project or group data `data`, read from `label`."
  [data]
  (let [{:keys [packages env commands menu on-enter]} data]
    (cond-> []
      (not (map? data)) (conj "a project file holds a map")
      (and (map? data) (seq (remove keys-allowed (keys data))))
      (conj (str "unknown keys " (str/join ", " (map pr-str (sort (remove keys-allowed (keys data)))))
                 "; a project file has :packages, :env, :commands, :menu and :on-enter"))
      (and (some? packages) (not (and (vector? packages) (every? #(and (string? %) (not (str/blank? %))) packages))))
      (conj ":packages must be a vector of PKG, PKG@VERSION or @GROUP")
      (some? env) (into (env-problems env))
      (and (some? commands) (not (map? commands))) (conj ":commands maps names to commands")
      (map? commands) (into (mapcat (fn [[k v]] (command-problems k v)) commands))
      (and (some? menu) (not (boolean? menu))) (conj ":menu is true or false")
      (and (some? on-enter) (not (string? on-enter))) (conj ":on-enter names a command"))))

(defn read-file
  "The project data in the file `path`, validated."
  [path]
  (let [data (try (edn/read-string (slurp (str path)))
                  (catch RuntimeException e
                    (throw (ex-info (str path " is not valid EDN: " (ex-message e))
                                    {:breto/problem :invalid-project :file (str path)
                                     :problems [(str "not valid EDN: " (ex-message e))]}))))]
    (when-let [ps (seq (problems data))]
      (throw (ex-info (str path ": " (str/join "; " ps))
                      {:breto/problem :invalid-project :file (str path) :problems (vec ps)})))
    data))

(defn merge-packages
  "The specs of `shared` with those of `local` over them: a local spec of a
  package or group the shared list names takes its place, and the others
  follow."
  [shared local]
  (let [key-of #(first (str/split % #"@(?=[^@]*$)" 2))
        key-of #(if (str/starts-with? % "@") % (key-of %))
        local-by (into {} (map (juxt key-of identity)) local)
        replaced (mapv #(get local-by (key-of %) %) shared)
        present (set (map key-of shared))]
    (into replaced (remove #(present (key-of %)) local))))

(defn- merge-groups
  "The :env and :commands of the groups `names`, in the order they are
  reached. Two groups giving one variable or command different values is an
  error, since neither has the better claim."
  [groups names]
  (let [used (registry/reached-groups groups names)
        _ (doseq [group-name used
                  :let [ps (problems (select-keys (get groups group-name) [:env :commands]))]
                  :when (seq ps)]
            (throw (ex-info (str "invalid group " group-name ": " (str/join "; " ps))
                            {:breto/problem :invalid-package :package group-name
                             :files (get-in groups [group-name :files]) :problems (vec ps)})))
        conflict (fn [what k owners]
                   (throw (ex-info (str "the groups " (str/join " and " owners) " define the " what " " k " differently")
                                   {:breto/problem :env-conflict :variable k :packages (vec owners)})))
        gather (fn [field what]
                 (reduce (fn [acc group-name]
                           (reduce (fn [acc [k v]]
                                     (if-let [[owner value] (get acc k)]
                                       (if (= value v) acc (conflict what k [owner group-name]))
                                       (assoc acc k [group-name v])))
                                   acc
                                   (get-in groups [group-name field])))
                         {}
                         used))]
    {:env (update-vals (gather :env "variable") second)
     :commands (update-vals (gather :commands "command") second)
     :files (registry/group-files groups names)}))

(defn ignored-by-git?
  "Whether git ignores `file`: true or false inside a work tree, nil outside
  one or without git."
  [file]
  (try
    (let [{:keys [exit]} (process/shell {:out :string :err :string :continue true
                                         :dir (str (fs/parent file))}
                                        "git" "check-ignore" "--quiet" (str (fs/file-name file)))]
      (case (long exit) 0 true 1 false nil))
    (catch Exception _ nil)))

(defn load-project
  "The project of `dir`: {:dir :files :packages :specs :env :commands :menu
  :on-enter :warnings}, the specs with groups expanded, :files the project
  files and the group files the project draws on, and :env and :commands the
  groups' with the project's own over them. `ignored?` tells whether git
  ignores a file, as ignored-by-git? does; a local file inside a work tree
  that git does not ignore is warned about."
  ([groups dir] (load-project groups dir ignored-by-git?))
  ([groups dir ignored?]
   (let [dir (str (fs/absolutize dir))
         shared-file (fs/path dir file-name)
         local-file (fs/path dir local-file-name)
         shared (when (fs/regular-file? shared-file) (read-file shared-file))
         local (when (fs/regular-file? local-file) (read-file local-file))
         _ (when-not (or shared local)
             (throw (ex-info (str "no " file-name " or " local-file-name " in " dir)
                             {:breto/problem :no-project :dir dir})))
         names (merge-packages (:packages shared []) (:packages local []))
         from-groups (merge-groups groups names)]
     {:dir dir
      :files (vec (concat (files dir) (:files from-groups)))
      :packages names
      :specs (registry/expand groups names)
      :env (merge (:env from-groups) (:env shared) (:env local))
      :commands (merge (:commands from-groups) (:commands shared) (:commands local))
      :menu (get local :menu (get shared :menu true))
      :on-enter (get local :on-enter (:on-enter shared))
      :warnings (cond-> []
                  (and local (false? (ignored? local-file)))
                  (conj (str local-file " is not ignored by git, so its host-local settings may be committed;"
                             " add " local-file-name " to .gitignore")))})))

(defn command-for
  "The command `name` of `project` resolved for `platform`: its :platforms
  entry over it, or nil when the project has no such command."
  [project name platform]
  (when-let [command (get-in project [:commands name])]
    (merge (dissoc command :platforms) (get-in command [:platforms platform]))))

(defn run-order
  "The commands to run for `name`, its :depends first, each once, depth
  first, in the order the dependencies are listed."
  [project name platform]
  (letfn [(walk [[order seen] path n]
            (let [command (or (command-for project n platform)
                              (throw (ex-info (str "no command " n " in the project"
                                                   (when (seq path) (str ", which " (peek path) " depends on")))
                                              {:breto/problem :unknown-command :command n
                                               :known (sort (keys (:commands project)))})))]
              (when (some #{n} path)
                (throw (ex-info (str "the command " n " depends on itself: " (str/join " -> " (conj path n)))
                                {:breto/problem :invalid-project :problems [(str n " depends on itself")]})))
              (if (seen n)
                [order seen]
                (let [[order seen] (reduce #(walk %1 (conj path n) %2) [order seen] (:depends command))]
                  [(conj order [n command]) (conj seen n)]))))]
    (first (walk [[] #{}] [] name))))
