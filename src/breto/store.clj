;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.store
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

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

(defn installed? [config name version]
  (fs/directory? (version-dir config name version)))

(defn- version-in [config name target]
  (let [dir (fs/normalize (fs/absolutize (package-dir config name)))
        target (fs/normalize (fs/absolutize target))]
    (when (and (fs/starts-with? target dir) (not= dir target))
      (str (fs/file-name (fs/path dir (first (fs/relativize dir target))))))))

(defn current-version
  "The active version of `name`: the target of its `current` link, or for an
  install that predates that link, the version its first binary link points into."
  [config name bin-links]
  (let [current (fs/path (package-dir config name) current-link)]
    (if (fs/sym-link? current)
      (str (fs/file-name (fs/read-link current)))
      (some (fn [link]
              (let [bin (fs/path (:bin-dir config) link)]
                (when (fs/sym-link? bin)
                  (version-in config name (fs/read-link bin)))))
            bin-links))))

(defn- expand-home [s]
  (str/replace-first (str s) #"^~(?=/|$)" (System/getProperty "user.home")))

(defn- link!
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
