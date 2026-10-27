;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.filelock
  "Advisory locks on a `.lock` file in a directory. The operating system
  releases a lock when its process dies, however it dies, so a lock that can
  be taken proves its holder is gone."
  (:require [babashka.fs :as fs]
            [clojure.string :as str])
  (:import (java.nio.channels FileChannel)
           (java.nio.file OpenOption StandardOpenOption)))

(def lock-file ".lock")

(def ^:private holder-file ".lock-holder")

(defn- channel [dir]
  (FileChannel/open (fs/path dir lock-file)
                    (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE])))

(defn- try-lock
  "The lock on `ch`, or nil when another process, or another channel of this
  one, holds it."
  [ch]
  (try (.tryLock ch) (catch IllegalStateException _ nil)))

(defn hold!
  "Locks `dir` until the returned channel is closed."
  [dir]
  (let [ch (channel dir)]
    (.lock ch)
    ch))

(defn held?
  "Whether a running process holds the lock of `dir`."
  [dir]
  (with-open [ch (channel dir)]
    (nil? (try-lock ch))))

(defn- own-pid []
  (try (str (fs/read-link "/proc/self")) (catch Exception _ "unknown")))

(defn with-lock
  "Runs `f` holding the lock of `dir`, creating the directory. When another
  process holds it, `on-wait` is called with that process's id first and `f`
  runs once the lock is free."
  [dir on-wait f]
  (fs/create-dirs dir)
  (with-open [ch (channel dir)]
    (when-not (try-lock ch)
      (on-wait (try (str/trim (slurp (str (fs/path dir holder-file)))) (catch Exception _ "unknown")))
      (.lock ch))
    (spit (str (fs/path dir holder-file)) (own-pid))
    (f)))
