;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.inputs
  "The files that decide which versions a project links: both project files,
  .breto.edn and .breto.local.edn, whether they exist or not, and the group
  files they draw on. The stamp beside the project's links holds a digest of
  them. A linked tool compares modification times against the stamp in plain
  sh, so running it starts no breto until one of the files changes; breto then
  compares the digest to tell a real change from a file merely rewritten."
  (:require [babashka.fs :as fs]
            [breto.env :as env]
            [breto.project :as project]
            [breto.shell :as shell]
            [breto.store :as store]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(defn of-project
  "{:present :absent} of `proj`: the files that exist, and the project files
  that do not, whose appearing changes the project just the same."
  [{:keys [dir files]}]
  (let [candidates (project/candidates dir)]
    {:present (vec (distinct (concat (filter fs/regular-file? candidates) files)))
     :absent (vec (remove fs/regular-file? candidates))}))

(defn- hex [^bytes bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn digest
  "A hash of the paths of `inputs` and the contents of those present. A present
  file that went missing counts as absent, so the digest then differs."
  [{:keys [present absent]}]
  (let [md (MessageDigest/getInstance "SHA-256")
        add! (fn [^String s] (.update md (.getBytes s "UTF-8")) (.update md (byte 0)))]
    (doseq [path (concat present absent)]
      (if (fs/regular-file? path)
        (do (add! "present") (add! path) (.update md ^bytes (fs/read-all-bytes path)) (.update md (byte 0)))
        (do (add! "absent") (add! path))))
    (hex (.digest md))))

(defn stamp-path
  "The stamp of the project at `dir`."
  [config dir]
  (fs/path (env/env-dir config dir) "stamp"))

(defn loaded
  "`inputs` with the :digest they have now, taken as the project is loaded so
  the stamp can record what the links are made from."
  [inputs]
  (assoc inputs :digest (digest inputs)))

(defn- marker
  "The file beside `stamp` that carries the modification time `path` had when
  the stamp was made."
  [stamp path]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (fs/path (str stamp ".d") (subs (hex (.digest md (.getBytes (str path) "UTF-8"))) 0 16))))

(defn mark!
  "Gives each present file of `inputs` a marker beside `stamp` with the
  file's modification time, so the shell test sees any other time, older
  ones included."
  [stamp {:keys [present]}]
  (let [dir (fs/path (str stamp ".d"))]
    (fs/delete-tree dir)
    (fs/create-dirs dir)
    (doseq [path present :when (fs/regular-file? path)
            :let [m (marker stamp path)]]
      (spit (str m) "")
      (fs/set-last-modified-time m (fs/last-modified-time path)))))

(defn stamp!
  "Records `inputs`, taken with `loaded` when the project was read, as what
  the links of the project at `dir` were made from. The stamp replaces the
  old one atomically, so a tool running meanwhile reads one or the other. It
  is written last, once the links are in place, and the markers of the
  files' times after it. When the files changed since they were read, as an
  edit during a long install does, no stamp is written: the old one stays
  stale and the next run links again. Returns the stamp's path, or nil when
  it was left alone."
  [config dir inputs]
  (let [stamp (stamp-path config dir)
        staged (store/staged-path (fs/parent stamp) "stamp")
        recorded (or (:digest inputs) (digest inputs))]
    (when (= recorded (digest inputs))
      (fs/create-dirs (fs/parent stamp))
      (spit (str staged) recorded)
      (fs/move staged stamp {:replace-existing true :atomic-move true})
      (mark! stamp inputs)
      (str stamp))))

(defn fresh?
  "Whether the links of the project at `dir` were made from exactly `inputs`."
  [config dir inputs]
  (let [stamp (stamp-path config dir)]
    (and (fs/regular-file? stamp)
         (= (str/trim (slurp (str stamp))) (digest inputs)))))

(defn touch!
  "Marks the stamp of the project at `dir` as current for `inputs`, files
  rewritten with the content they had."
  [config dir inputs]
  (let [stamp (stamp-path config dir)]
    (fs/set-last-modified-time stamp (java.nio.file.attribute.FileTime/from (java.time.Instant/now)))
    (mark! stamp inputs)))

(defn stale-test
  "The sh condition true when the files of `inputs` may no longer match the
  stamp at `stamp`: the stamp is missing, a present file is gone or has
  another modification time than its marker, newer or older, or an absent
  project file appeared. A file replaced by an older one, as mv, cp -p or
  rsync -a leave it, therefore counts too. It uses shell builtins alone, where
  neither -nt nor -ot holding means the same time. It can be true without a
  real change, never the reverse."
  [{:keys [present absent]} stamp]
  (let [s (shell/quote-arg stamp)]
    (str/join " || "
              (concat [(str "[ ! -f " s " ]")]
                      (mapcat (fn [path]
                                (let [f (shell/quote-arg path)
                                      m (shell/quote-arg (marker stamp path))]
                                  [(str "[ ! -f " f " ]") (str "[ " f " -nt " m " ]") (str "[ " f " -ot " m " ]")]))
                              present)
                      (map #(str "[ -e " (shell/quote-arg %) " ]") absent)))))
