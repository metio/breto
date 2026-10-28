;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.store
  (:require [babashka.fs :as fs]
            [breto.filelock :as filelock]
            [breto.share :as share]
            [clojure.edn :as edn]
            [breto.version :as version]
            [clojure.string :as str]))

(def ^:private current-link "current")

(defn segment!
  "`s` when it is usable as one directory name in the store, else throws. A
  name like .. or one with a slash would put a package or version outside of
  its directory, where deleting it deletes something else."
  [kind s]
  (when-not (and (string? s)
                 (re-matches #"[^/\x00-\x1f]+" s)
                 (not (str/starts-with? s "."))
                 (= s (str/trim s)))
    (throw (ex-info (str "unusable " (name kind) " " (pr-str s))
                    {:breto/problem :unsafe-name :kind kind :value (str s)})))
  s)

(defn package-dir [{:keys [store]} name]
  (fs/path store (segment! :package name)))

(defn version-dir [config name version]
  (fs/path (package-dir config name) (segment! :version version)))

(defn installed-packages [{:keys [store]}]
  (if (fs/directory? store)
    (->> (fs/list-dir store)
         (filter fs/directory?)
         (map (comp str fs/file-name))
         sort)
    []))

(defn versions
  "Installed versions of package `name`, most recently installed first. Hidden
  entries are staging directories and symlinks are activation markers."
  [config name]
  (let [dir (package-dir config name)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(fs/directory? % {:nofollow-links true}))
           (remove #(str/starts-with? (str (fs/file-name %)) "."))
           (map (fn [p] {:version (str (fs/file-name p))
                         :modified (.toMillis (fs/last-modified-time p))}))
           (sort-by :modified >))
      [])))

(defn installed?
  "Whether `version` is installed with every one of `paths` present. A
  directory missing one of them is treated as not installed."
  [config name version paths]
  (let [dir (version-dir config name version)]
    (and (fs/directory? dir)
         (every? #(fs/exists? (fs/path dir %)) paths))))

(defn current-version
  "The active version of `name`: the target of its `current` link."
  [config name]
  (let [current (fs/path (package-dir config name) current-link)]
    (when (fs/sym-link? current)
      (str (fs/file-name (fs/read-link current))))))

(defn expand-home [s]
  (str/replace-first (str s) #"^~(?=/|$)" (System/getProperty "user.home")))

(defn link!
  "Points `link` at `target`, swapping the old link out in one rename."
  [link target]
  (let [link (fs/path link)
        staged (fs/path (fs/parent link) (str "." (fs/file-name link) ".breto"))]
    (fs/create-dirs (fs/parent link))
    (fs/delete-if-exists staged)
    (fs/create-sym-link staged target)
    (fs/move staged link {:replace-existing true :atomic-move true})))

(defn- points-into?
  "Whether `link` is a symlink whose target lies in `dir`, read without
  following it, so a link whose target is gone still counts."
  [dir link]
  (and (fs/sym-link? link)
       (str/starts-with? (str (fs/read-link link)) (str dir "/"))))

(defn share-links-into
  "The man page and completion links that point into the store directory of
  `name`."
  [config name]
  (let [dir (package-dir config name)]
    (->> (share/link-dirs config)
         (filter fs/directory?)
         (mapcat fs/list-dir)
         (filter #(points-into? dir %))
         (map str)
         sort)))

(defn link-share!
  "Links the man pages and completions of `version` of `pkg` and removes the
  package's links whose target is gone. A file that breto did not link is
  left alone; returns a sentence for each."
  [config pkg version]
  (let [dir (package-dir config (:name pkg))
        wanted (share/links config pkg (version-dir config (:name pkg) version) (fs/path dir current-link))]
    (doseq [link (share-links-into config (:name pkg))
            :when (not (fs/exists? link))]
      (fs/delete link))
    (doall
     (for [[link target] wanted
           :let [taken? (and (fs/exists? link {:nofollow-links true}) (not (points-into? dir link)))]
           :when (or taken? (do (link! link target) false))]
       (str link " exists and was not linked by breto, so " (:name pkg) " leaves it alone")))))

(defn activate!
  "Makes `version` the one that `current`, the binaries, any extra links, the
  man pages and the completions of the package resolve to. Returns warnings
  about files in the way of man pages and completions."
  [config {:keys [name links] :as pkg} bin-paths version]
  (let [current (fs/path (package-dir config name) current-link)]
    (link! current version)
    (doseq [[link path] bin-paths]
      (link! (fs/path (:bin-dir config) link) (fs/path current path)))
    (doseq [[link path] links]
      (link! (expand-home link) (fs/path current path)))
    (link-share! config pkg version)))

(defn previous-version
  "The newest of `versions` older than `active`, or nil."
  [versions active]
  (->> versions
       (filter #(version/older? % active))
       (sort version/compare-versions)
       last))

(defn gc-candidates
  "Versions to delete when keeping the `keep` most recent; the active version
  is never a candidate."
  [versions current keep]
  (->> versions
       (drop keep)
       (map :version)
       (remove #{current})))

(defn- meta-file [config name version]
  (fs/path (package-dir config name) (str ".meta-" (segment! :version version) ".edn")))

(defn write-meta!
  "Records what is known about an installed version, such as the hash of the
  download it came from, beside its directory."
  [config name version m]
  (spit (str (meta-file config name version)) (pr-str m)))

(defn read-meta [config name version]
  (let [file (meta-file config name version)]
    (when (fs/exists? file)
      (edn/read-string (slurp (str file))))))

(defn remove-version! [config name version]
  (fs/delete-tree (version-dir config name version))
  (fs/delete-if-exists (meta-file config name version)))

(defn- resolves-into? [dir link]
  (and (fs/sym-link? link)
       (fs/starts-with? (fs/canonicalize (fs/path (fs/parent link) (fs/read-link link))) dir)))

(defn links-into
  "The links in the bin directory, among `extra-links`, and among the man page
  and completion links that resolve into the store directory of `name`,
  whatever definition created them."
  [config name extra-links]
  (let [dir (fs/canonicalize (package-dir config name))
        bin-dir (:bin-dir config)]
    (->> (concat (->> (concat (when (fs/directory? bin-dir) (fs/list-dir bin-dir))
                              (map (comp fs/path expand-home) extra-links))
                      (filter #(resolves-into? dir %))
                      (map str))
                 (share-links-into config name))
         distinct
         sort)))

(defn uninstall!
  "Deletes `links`, then the store directory of `name`. Links go first so none
  is left pointing at a deleted file."
  [config name links]
  (run! fs/delete links)
  (fs/delete-tree (package-dir config name)))

(defn prune-candidates
  "Installed packages the configuration does not list."
  [installed configured]
  (remove (set configured) installed))

(def work-prefix ".work-")

(defn hold!
  "Locks the work directory `dir` until the returned channel is closed."
  [dir]
  (filelock/hold! dir))

(defn abandoned?
  "Whether no running process holds the work directory `dir`. One without a
  lock file counts only once it is an hour old, since its install may be
  about to take the lock."
  [dir]
  (if (fs/exists? (fs/path dir filelock/lock-file))
    (not (filelock/held? dir))
    (< (.toMillis (fs/last-modified-time dir))
       (- (System/currentTimeMillis) (* 60 60 1000)))))

(defn leftovers
  "Work directories in the store directory of `name` that no running install
  holds, left behind by an install that was killed. Staging directories of
  older breto versions sit beside them and carry no lock."
  [config name]
  (let [dir (package-dir config name)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter #(re-find #"^\.(work|staging)-" (str (fs/file-name %))))
           (filter abandoned?)
           (map str)
           sort)
      [])))

(defn with-store-lock
  "Runs `f` holding the lock of the store, so two breto runs never change it at
  once. When another run holds it, `on-wait` is called with that run's process
  id and `f` runs once the lock is free."
  [{:keys [store]} on-wait f]
  (filelock/with-lock store on-wait f))
