;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.archive
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.tar :as tar]
            [clojure.java.io :as io]
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

(defn- move-into!
  "Moves the entries of `from` into `to`."
  [from to]
  (fs/create-dirs to)
  (doseq [entry (fs/list-dir from)]
    (fs/move entry (fs/path to (fs/file-name entry)) {:replace-existing true})))

(defn- zip-path
  "The path the zip entry `name` unpacks to after dropping `strip` leading
  components: nil when nothing is left, :escapes when it would leave the
  directory it is unpacked into."
  [name strip]
  (let [root (fs/path "/unpack")
        target (.normalize (fs/path root name))]
    (if (or (fs/absolute? (fs/path name)) (not (.startsWith target root)))
      :escapes
      (let [parts (drop strip (map str (fs/relativize root target)))]
        (when (and (seq parts) (not= [""] parts))
          (str/join "/" parts))))))

(defn escaping-entries
  "Names of the entries in the zip `file` that would unpack outside of the
  directory they are unpacked into."
  [file]
  (with-open [zip (java.util.zip.ZipFile. (fs/file file))]
    (->> (enumeration-seq (.entries zip))
         (map #(.getName ^java.util.zip.ZipEntry %))
         (filter #(let [target (zip-path % 0)] (or (= :escapes target) (nil? target))))
         vec)))

(defn- read-le [^java.io.RandomAccessFile raf pos n]
  (.seek raf pos)
  (reduce (fn [value i] (bit-or value (bit-shift-left (long (.read raf)) (* 8 i)))) 0 (range n)))

(defn- central-directory
  "The offset and entry count of the central directory of the zip in `raf`,
  located from the end so that data prepended to the archive does not shift it."
  [^java.io.RandomAccessFile raf]
  (let [length (.length raf)
        tail (min length 65557)
        buf (byte-array tail)
        _ (do (.seek raf (- length tail)) (.readFully raf buf))
        signature? (fn [i b0 b1 b2 b3] (= [b0 b1 b2 b3] (map #(bit-and 0xff (aget buf (int (+ i %)))) (range 4))))
        eocd (some #(when (signature? % 0x50 0x4b 0x05 0x06) (+ (- length tail) %)) (range (- tail 22) -1 -1))]
    (when eocd
      (let [locator (- eocd 20)]
        (if (and (>= locator 0) (= 0x07064b50 (read-le raf locator 4)))
          (let [record (read-le raf (+ locator 8) 8)]
            {:offset (- record (read-le raf (+ record 40) 8)) :count (read-le raf (+ record 32) 8)})
          {:offset (- eocd (read-le raf (+ eocd 12) 4)) :count (read-le raf (+ eocd 10) 2)})))))

(defn- unix-modes
  "Entry name -> permission bits, for the entries of the zip `file` made on
  Unix. java.util.zip does not expose the external attributes they live in."
  [file]
  (with-open [raf (java.io.RandomAccessFile. (fs/file file) "r")]
    (if-let [{:keys [offset count]} (central-directory raf)]
      (loop [pos offset left count modes {}]
        (if (and (pos? left) (= 0x02014b50 (read-le raf pos 4)))
          (let [made-on (bit-shift-right (read-le raf (+ pos 4) 2) 8)
                name-length (read-le raf (+ pos 28) 2)
                mode (bit-and (bit-shift-right (read-le raf (+ pos 38) 4) 16) 07777)
                name (let [bytes (byte-array name-length)]
                       (.seek raf (+ pos 46))
                       (.readFully raf bytes)
                       (String. bytes java.nio.charset.StandardCharsets/UTF_8))]
            (recur (+ pos 46 name-length (read-le raf (+ pos 30) 2) (read-le raf (+ pos 32) 2))
                   (dec left)
                   (cond-> modes (and (= 3 made-on) (pos? mode)) (assoc name mode))))
          modes))
      {})))

(defn- unzip!
  "Unpacks the zip `file` into `dest`, dropping `strip` leading components of
  every entry. Names and contents both come from the central directory, so the
  names checked are the names written. macOS resource forks under __MACOSX/
  are left out."
  [file dest strip]
  (let [problem (fn [message & {:as data}]
                  (ex-info (str (fs/file-name file) " " message)
                           (merge {:breto/problem :unpack-failed :file (str (fs/file-name file))} data)))
        modes (unix-modes file)]
    (fs/create-dirs dest)
    (with-open [zip (java.util.zip.ZipFile. (fs/file file))]
      (doseq [^java.util.zip.ZipEntry entry (enumeration-seq (.entries zip))
              :let [name (.getName entry)
                    rel (zip-path name strip)]
              :when (and rel (not (str/starts-with? name "__MACOSX/")))]
        (when (= :escapes rel)
          (throw (problem (str "has entries outside of the archive: " name) :escaping [name])))
        (let [target (fs/path dest rel)
              mode (get modes name)]
          (if (.isDirectory entry)
            (if (fs/exists? target {:nofollow-links true})
              (when-not (fs/directory? target {:nofollow-links true})
                (throw (problem (str "has both a file and a directory at " rel))))
              (fs/create-dirs target))
            (do (when (fs/exists? target {:nofollow-links true})
                  (throw (problem (str "has more than one entry at " rel))))
                (fs/create-dirs (fs/parent target))
                (with-open [in (.getInputStream zip entry)]
                  (io/copy in (fs/file target)))
                (when mode
                  (fs/set-posix-file-permissions target (tar/permissions mode false))))))))))

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
                   (move-into! scratch dest))
          :zip (let [escaping (try (escaping-entries file)
                                   (catch java.io.IOException e
                                     (throw (ex-info (str (fs/file-name file) " is not a readable zip archive: " (ex-message e))
                                                     {:breto/problem :unpack-failed :file (str (fs/file-name file))
                                                      :stderr (str (ex-message e))}))))]
                 (when (seq escaping)
                   (throw (ex-info (str (fs/file-name file) " has entries outside of the archive: " (str/join ", " (take 5 escaping)))
                                   {:breto/problem :unpack-failed :file (str (fs/file-name file))
                                    :escaping (vec (take 5 escaping))})))
                 (unzip! file scratch strip)
                 (move-into! scratch dest)))
        (finally
          (fs/delete-tree scratch))))))
