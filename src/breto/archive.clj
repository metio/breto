;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.archive
  (:require [babashka.fs :as fs]
            [babashka.process :as process]))

(defn- subdirectories [dir]
  (filter #(fs/directory? % {:nofollow-links true}) (fs/list-dir dir)))

(defn- strip!
  "Moves the entries `n` directory levels below `from` into `to`. Tar archives
  strip with `--strip-components` instead, which also counts a leading `./`."
  [from to n]
  (let [dirs (nth (iterate #(mapcat subdirectories %) [from]) n)]
    (fs/create-dirs to)
    (doseq [dir dirs
            entry (fs/list-dir dir)]
      (fs/move entry (fs/path to (fs/file-name entry)) {:replace-existing true}))))

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
          :tar (do (process/shell {:out :string :err :string}
                                  "tar" "--extract" "--file" (str file) "--directory" (str scratch)
                                  (str "--strip-components=" strip))
                   (strip! scratch dest 0))
          :zip (do (fs/unzip file scratch)
                   (strip! scratch dest strip)))
        (finally
          (fs/delete-tree scratch))))))
