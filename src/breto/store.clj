;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.store
  (:require [babashka.fs :as fs]
            [clojure.string :as str])
  (:import (java.nio.channels FileChannel)
           (java.nio.file OpenOption StandardOpenOption)))

(def ^:private current-link "current")

(defn package-dir [{:keys [store]} name]
  (fs/path store name))

(defn version-dir [config name version]
  (fs/path (package-dir config name) version))

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

(defn activate!
  "Makes `version` the one that `current`, the binaries, and any extra links of
  the package resolve to."
  [config {:keys [name links]} bin-paths version]
  (let [current (fs/path (package-dir config name) current-link)]
    (link! current version)
    (doseq [[link path] bin-paths]
      (link! (fs/path (:bin-dir config) link) (fs/path current path)))
    (doseq [[link path] links]
      (link! (expand-home link) (fs/path current path)))))

(defn gc-candidates
  "Versions to delete when keeping the `keep` most recent; the active version
  is never a candidate."
  [versions current keep]
  (->> versions
       (drop keep)
       (map :version)
       (remove #{current})))

(defn remove-version! [config name version]
  (fs/delete-tree (version-dir config name version)))

(defn- resolves-into? [dir link]
  (and (fs/sym-link? link)
       (fs/starts-with? (fs/canonicalize (fs/path (fs/parent link) (fs/read-link link))) dir)))

(defn links-into
  "The links in the bin directory, or among `extra-links`, that resolve into
  the store directory of `name`, whatever definition created them."
  [config name extra-links]
  (let [dir (fs/canonicalize (package-dir config name))
        bin-dir (:bin-dir config)]
    (->> (concat (when (fs/directory? bin-dir) (fs/list-dir bin-dir))
                 (map (comp fs/path expand-home) extra-links))
         (filter #(resolves-into? dir %))
         (map str)
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

(def ^:private lock-file ".lock")

(defn- channel [dir]
  (FileChannel/open (fs/path dir lock-file)
                    (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE])))

(defn hold!
  "Locks the work directory `dir` until the returned channel is closed. The
  operating system releases the lock when the process dies, however it dies."
  [dir]
  (let [ch (channel dir)]
    (.lock ch)
    ch))

(defn abandoned?
  "Whether no running process holds the work directory `dir`. One without a
  lock file counts only once it is an hour old, since its install may be
  about to take the lock."
  [dir]
  (if (fs/exists? (fs/path dir lock-file))
    (with-open [ch (channel dir)]
      (try
        (some? (.tryLock ch))
        (catch IllegalStateException _ false)))
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
