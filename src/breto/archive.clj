;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.archive
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.tar :as tar]
            [clojure.string :as str]))

(defn listing
  "Relative paths of the regular files under `dir`, at most `limit` of them."
  [dir limit]
  (->> (fs/glob dir "**" {:hidden true})
       (filter fs/regular-file?)
       (map #(str (fs/relativize dir %)))
       sort
       (take limit)
       vec))

(defn- subdirectories [dir]
  (filter #(fs/directory? % {:nofollow-links true}) (fs/list-dir dir)))

(defn- strip!
  "Moves the entries `n` directory levels below `from` into `to`. Tar archives
  strip while unpacking instead, counting a leading `./` as GNU tar's
  `--strip-components` does."
  [from to n]
  (let [dirs (nth (iterate #(mapcat subdirectories %) [from]) n)]
    (fs/create-dirs to)
    (doseq [dir dirs
            entry (fs/list-dir dir)]
      (fs/move entry (fs/path to (fs/file-name entry)) {:replace-existing true}))))

(defn escaping-entries
  "Names of the entries in the zip `file` that would unpack outside of the
  directory they are unpacked into."
  [file]
  (let [root (fs/path "/unpack")]
    (with-open [zip (java.util.zip.ZipFile. (fs/file file))]
      (->> (enumeration-seq (.entries zip))
           (map #(.getName ^java.util.zip.ZipEntry %))
           (remove #(let [target (.normalize (fs/path root %))]
                      (and (not (fs/absolute? (fs/path %))) (.startsWith target root) (not= target root))))
           vec))))

(defn unpack!
  "Unpacks `file` into the empty directory `dest`. A raw download is copied in
  under `file-name`. The scratch space sits next to `dest` so that moving out of
  it never crosses a filesystem."
  [file archive-type file-name dest strip]
  (fs/create-dirs dest)
  (case archive-type
    :raw (fs/copy file (fs/path dest file-name))
    (let [scratch (fs/create-temp-dir {:dir (fs/parent dest) :prefix ".unpack-"})]
      (try
        (case archive-type
          :tar (do (if (tar/readable? file)
                     (tar/unpack! file scratch strip)
                     (let [{:keys [exit err]} (process/shell {:out :string :err :string :continue true}
                                                             "tar" "--extract" "--file" (str file)
                                                             "--directory" (str scratch)
                                                             (str "--strip-components=" strip))]
                       (when-not (zero? exit)
                         (throw (ex-info (str "tar could not unpack " (fs/file-name file) ": " (str/trim err))
                                         {:breto/problem :unpack-failed :file (str (fs/file-name file)) :stderr err})))))
                   (strip! scratch dest 0))
          :zip (let [escaping (try (escaping-entries file)
                                   (catch java.io.IOException e
                                     (throw (ex-info (str (fs/file-name file) " is not a readable zip archive: " (ex-message e))
                                                     {:breto/problem :unpack-failed :file (str (fs/file-name file))
                                                      :stderr (str (ex-message e))}))))]
                 (when (seq escaping)
                   (throw (ex-info (str (fs/file-name file) " has entries outside of the archive: " (str/join ", " (take 5 escaping)))
                                   {:breto/problem :unpack-failed :file (str (fs/file-name file))
                                    :escaping (vec (take 5 escaping))})))
                 (fs/unzip file scratch)
                 (strip! scratch dest strip)))
        (finally
          (fs/delete-tree scratch))))))
