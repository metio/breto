;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.sealed
  "Read-only directory trees. An installed version is sealed, so neither a
  program nor its user can change the files a lock file vouches for. Deleting
  a sealed tree needs its directories writable again; files may stay
  read-only, since removing one only needs its directory writable."
  (:require [babashka.fs :as fs]
            [clojure.set :as set])
  (:import [java.nio.file Files FileVisitOption LinkOption]
           [java.nio.file.attribute PosixFilePermission]))

(def ^:private write-bits
  #{PosixFilePermission/OWNER_WRITE PosixFilePermission/GROUP_WRITE PosixFilePermission/OTHERS_WRITE})

(defn- entries
  "The files and directories in the tree at `dir`, without following links
  and without the links themselves, whose permissions are their targets'."
  [dir]
  (with-open [s (Files/walk (fs/path dir) (into-array FileVisitOption []))]
    (into [] (remove fs/sym-link?) (iterator-seq (.iterator s)))))

(defn- permissions [path]
  (set (Files/getPosixFilePermissions (fs/path path) (into-array LinkOption []))))

(defn- set-permissions! [path perms]
  (Files/setPosixFilePermissions (fs/path path) (java.util.HashSet. ^java.util.Collection perms)))

(defn- seal-one! [path]
  (let [perms (permissions path)]
    (when (seq (set/intersection perms write-bits))
      (set-permissions! path (set/difference perms write-bits)))))

(defn- unseal-one! [path]
  (let [perms (permissions path)]
    (when-not (perms PosixFilePermission/OWNER_WRITE)
      (set-permissions! path (conj perms PosixFilePermission/OWNER_WRITE)))))

(defn seal!
  "Takes the write permissions off every file and directory in the tree at
  `dir`."
  [dir]
  (run! seal-one! (entries dir)))

(defn sealed?
  "Whether the directory `dir` exists and is not writable."
  [dir]
  (and (fs/directory? dir {:nofollow-links true})
       (not ((permissions dir) PosixFilePermission/OWNER_WRITE))))

(defn delete-tree!
  "Deletes the tree at `dir`, sealed or not."
  [dir]
  (when (fs/directory? dir {:nofollow-links true})
    (run! unseal-one! (filter #(fs/directory? % {:nofollow-links true}) (entries dir))))
  (fs/delete-tree dir))

(defn- chain
  "`root` and each directory below it down to the parent of `target`."
  [root target]
  (reductions fs/path root (remove #(= "" (str %)) (fs/relativize root (fs/parent target)))))

(defn writing!
  "Runs `f`, which writes the file `target` inside the tree at `root`. A
  sealed tree has the directories on the way to `target` opened for it and
  sealed again afterwards, together with what `f` wrote."
  [root target f]
  (if-not (sealed? root)
    (f)
    (let [dirs (chain root target)]
      (run! unseal-one! (filter fs/directory? dirs))
      (try
        (f)
        (finally
          (run! seal-one! (filter #(fs/exists? % {:nofollow-links true}) (concat (reverse dirs) [target]))))))))
