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

(defn stamp!
  "Records `inputs` as what the links of the project at `dir` were made from.
  The stamp replaces the old one atomically, so a tool running meanwhile reads
  one or the other. It is written last, once the links are in place."
  [config dir inputs]
  (let [stamp (stamp-path config dir)
        staged (fs/path (fs/parent stamp) ".stamp.breto")]
    (fs/create-dirs (fs/parent stamp))
    (spit (str staged) (digest inputs))
    (fs/move staged stamp {:replace-existing true :atomic-move true})
    (str stamp)))

(defn fresh?
  "Whether the links of the project at `dir` were made from exactly `inputs`."
  [config dir inputs]
  (let [stamp (stamp-path config dir)]
    (and (fs/regular-file? stamp)
         (= (str/trim (slurp (str stamp))) (digest inputs)))))

(defn touch!
  "Marks the stamp of the project at `dir` as current, for files rewritten with
  the content they had."
  [config dir]
  (fs/set-last-modified-time (stamp-path config dir)
                             (java.nio.file.attribute.FileTime/from (java.time.Instant/now))))

(defn stale-test
  "The sh condition true when the files of `inputs` may no longer match the
  stamp at `stamp`: the stamp is missing, a present file is gone or newer than
  it, or an absent project file appeared. It uses shell builtins alone. It can
  be true without a real change, never the reverse, as a change of content
  gives the file a new modification time."
  [{:keys [present absent]} stamp]
  (let [s (shell/quote-arg stamp)]
    (str/join " || "
              (concat [(str "[ ! -f " s " ]")]
                      (mapcat (fn [path]
                                (let [f (shell/quote-arg path)]
                                  [(str "[ ! -f " f " ]") (str "[ " f " -nt " s " ]")]))
                              present)
                      (map #(str "[ -e " (shell/quote-arg %) " ]") absent)))))
