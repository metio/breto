;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.tar
  "A reader for tar archives, plain or gzip-compressed, so the common release
  formats unpack without external tools. It understands ustar, GNU long names
  and pax headers, and refuses entries that would land outside the target
  directory or write through a symlink."
  (:require [babashka.fs :as fs]
            [clojure.string :as str])
  (:import (java.io BufferedInputStream EOFException FileInputStream FileOutputStream InputStream)
           (java.nio.charset StandardCharsets)
           (java.util.zip GZIPInputStream)))

(def ^:private block 512)

(defn- problem [file message & {:as data}]
  (ex-info (str (fs/file-name file) ": " message)
           (merge {:breto/problem :unpack-failed :file (str (fs/file-name file)) :stderr message} data)))

(defn- read-fully!
  "Fills `buf` from `in`; false at a clean end of the stream before any byte."
  [^InputStream in ^bytes buf file]
  (let [n (.readNBytes in buf 0 (alength buf))]
    (cond
      (= n (alength buf)) true
      (zero? n) false
      :else (throw (problem file "the archive ends in the middle of an entry")))))

(defn- skip-fully! [^InputStream in n file]
  (try (.skipNBytes in n)
       (catch EOFException _ (throw (problem file "the archive ends in the middle of an entry")))))

(defn- text
  "The NUL-terminated string in `len` bytes of `buf` at `offset`."
  [^bytes buf offset len]
  (let [end (loop [i offset]
              (if (and (< i (+ offset len)) (not (zero? (aget buf i)))) (recur (inc i)) i))]
    (String. buf (int offset) (int (- end offset)) StandardCharsets/UTF_8)))

(defn- number
  "A numeric header field: octal digits, or base-256 when the first byte has
  its high bit set."
  [^bytes buf offset len]
  (if (neg? (aget buf offset))
    (reduce (fn [n i] (+ (* n 256) (bit-and (aget buf i) 0xff)))
            (bit-and (aget buf offset) 0x7f)
            (range (inc offset) (+ offset len)))
    (let [s (str/trim (str/replace (text buf offset len) #"[^0-7 ]" ""))]
      (if (str/blank? s) 0 (Long/parseLong s 8)))))

(defn- checksum-ok? [^bytes buf]
  (let [stored (number buf 148 8)
        sum (reduce (fn [sum i] (+ sum (if (<= 148 i 155) 32 (bit-and (aget buf i) 0xff))))
                    0 (range block))]
    (= stored sum)))

(defn- zero-block? [^bytes buf]
  (every? zero? buf))

(defn- header [^bytes buf]
  (let [prefix (text buf 345 155)
        name (text buf 0 100)]
    {:name (if (and (str/starts-with? (text buf 257 6) "ustar") (seq prefix)) (str prefix "/" name) name)
     :mode (number buf 100 8)
     :size (number buf 124 12)
     :type (char (aget buf 156))
     :link (text buf 157 100)}))

(defn- read-bytes
  "The `size` bytes of an entry's data, with the padding after it consumed."
  [^InputStream in size file]
  (let [buf (byte-array size)]
    (read-fully! in buf file)
    (skip-fully! in (mod (- block (mod size block)) block) file)
    buf))

(defn- read-data
  "The `size` bytes of an entry's data as a string, with the padding after it
  consumed."
  [in size file]
  (String. ^bytes (read-bytes in size file) StandardCharsets/UTF_8))

(defn pax-records
  "The key-value records of a pax extended header, given as bytes or as
  text. Each record starts with its length in bytes."
  [data]
  (let [^bytes data (if (string? data) (.getBytes ^String data StandardCharsets/UTF_8) data)
        total (alength data)]
    (loop [at 0 records {}]
      (let [space (loop [i at] (cond (>= i total) nil
                                     (= 32 (aget data i)) i
                                     (<= 48 (aget data i) 57) (recur (inc i))
                                     :else nil))
            n (when (and space (> space at)) (parse-long (String. data at (- space at) StandardCharsets/US_ASCII)))]
        (if (and n (pos? n))
          (let [end (min total (+ at n))
                [_ k v] (re-matches #"(?s)\d+ ([^=]+)=(.*)\n" (String. data at (- end at) StandardCharsets/UTF_8))]
            (recur end (cond-> records k (assoc k v))))
          records)))))

(defn relative-path
  "The path `name` unpacks to after stripping `strip` leading components, as
  GNU tar's --strip-components does: a leading ./ counts as one. nil when
  nothing is left, :escapes when the path would leave the target directory."
  [name strip]
  (let [parts (drop strip (remove str/blank? (str/split name #"/")))]
    (cond
      (str/starts-with? name "/") :escapes
      (empty? parts) nil
      :else (let [kept (remove #{"."} parts)]
              (cond
                (some #{".."} kept) :escapes
                (empty? kept) nil
                :else (str/join "/" kept))))))

(defn- refuse-symlinked-parents!
  "Throws when a parent of `path` below `root` is a symlink, since reading or
  writing through one could leave `root`."
  [root path file name]
  (loop [dir (fs/parent path)]
    (when (and dir (not= dir root))
      (when (fs/sym-link? dir)
        (throw (problem file (str "the entry " name " passes through a symlink") :escaping [name])))
      (recur (fs/parent dir)))))

(defn- ensure-parents!
  "Creates the parent directories of `target` below `root`, refusing a parent
  that is a symlink."
  [root target file name]
  (refuse-symlinked-parents! root target file name)
  (fs/create-dirs (fs/parent target)))

(defn- replace-existing!
  "Removes what is at `target`, unless it is a directory, so a later entry
  replaces an earlier one without following a symlink."
  [target]
  (when (and (fs/exists? target {:nofollow-links true}) (not (fs/directory? target {:nofollow-links true})))
    (fs/delete target)))

(defn permissions
  "The permission string for a tar `mode`; the owner can always read and
  write, and enter a directory."
  [mode directory?]
  (let [bits (bit-or (bit-and mode 0777) (if directory? 0700 0600))]
    (apply str (for [shift [6 3 0]
                     [bit c] [[4 \r] [2 \w] [1 \x]]]
                 (if (pos? (bit-and bits (bit-shift-left bit shift))) c \-)))))

(defn- write-file! [^InputStream in target size mode file]
  (with-open [out (FileOutputStream. (str target))]
    (let [buf (byte-array 65536)]
      (loop [left size]
        (when (pos? left)
          (let [n (.read in buf 0 (int (min left (alength buf))))]
            (when (neg? n) (throw (problem file "the archive ends in the middle of an entry")))
            (.write out buf 0 n)
            (recur (- left n)))))))
  (skip-fully! in (mod (- block (mod size block)) block) file)
  (fs/set-posix-file-permissions target (permissions mode false)))

(defn- open [file]
  (let [in (BufferedInputStream. (FileInputStream. (str file)) 65536)]
    (.mark in 2)
    (let [magic [(.read in) (.read in)]]
      (.reset in)
      (if (= [0x1f 0x8b] magic)
        (BufferedInputStream. (GZIPInputStream. in 65536) 65536)
        in))))

(defn readable?
  "Whether `file` is a tar archive this reader handles: plain or gzip."
  [file]
  (try
    (with-open [in ^InputStream (open file)]
      (let [buf (byte-array block)]
        (and (read-fully! in buf file) (checksum-ok? buf))))
    (catch Exception _ false)))

(defn unpack!
  "Unpacks the tar archive `file` into the directory `dest`, dropping `strip`
  leading path components from every entry."
  [file dest strip]
  (let [root (fs/absolutize dest)
        buf (byte-array block)]
    (fs/create-dirs root)
    (with-open [in ^InputStream (try (open file)
                                     (catch java.io.IOException e
                                       (throw (problem file (str "not a readable archive: " (ex-message e))))))]
      (loop [overrides {}]
        (when (try (read-fully! in buf file)
                   (catch java.io.IOException e
                     (throw (problem file (str "not a readable archive: " (ex-message e))))))
          (when-not (zero-block? buf)
            (when-not (checksum-ok? buf)
              (throw (problem file "a header checksum does not match; the file is not a tar archive or is damaged")))
            (let [{:keys [type size mode] :as h} (header buf)
                  size (if-let [s (get overrides "size")] (parse-long s) size)
                  name (get overrides "path" (:name h))
                  link (get overrides "linkpath" (:link h))]
              (case type
                \x (recur (merge overrides (pax-records (read-bytes in size file))))
                \g (do (read-data in size file) (recur overrides))
                \L (recur (assoc overrides "path" (str/replace (read-data in size file) #"\u0000+$" "")))
                \K (recur (assoc overrides "linkpath" (str/replace (read-data in size file) #"\u0000+$" "")))
                (let [rel (relative-path name strip)
                      target (when (string? rel) (fs/path root rel))]
                  (when (= :escapes rel)
                    (throw (problem file (str "the entry " name " would unpack outside of the archive") :escaping [name])))
                  (if (nil? target)
                    (skip-fully! in (* block (quot (+ size (dec block)) block)) file)
                    (case type
                      (\0 \u0000 \7)
                      (do (ensure-parents! root target file name)
                          (replace-existing! target)
                          (write-file! in target size mode file))

                      \5
                      (do (ensure-parents! root target file name)
                          (when-not (fs/directory? target {:nofollow-links true})
                            (replace-existing! target)
                            (fs/create-dirs target))
                          (fs/set-posix-file-permissions target (permissions mode true))
                          (skip-fully! in (* block (quot (+ size (dec block)) block)) file))

                      \2
                      (do (ensure-parents! root target file name)
                          (replace-existing! target)
                          (fs/create-sym-link target link)
                          (skip-fully! in (* block (quot (+ size (dec block)) block)) file))

                      \1
                      (let [source (relative-path link strip)]
                        (when-not (string? source)
                          (throw (problem file (str "the hard link " name " points outside of the archive") :escaping [name])))
                        (let [source (fs/path root source)]
                          (refuse-symlinked-parents! root source file name)
                          (when-not (fs/regular-file? source {:nofollow-links true})
                            (throw (problem file (str "the hard link " name " points at " link ", which is not an earlier file"))))
                          (ensure-parents! root target file name)
                          (replace-existing! target)
                          (fs/copy source target {:copy-attributes true})
                          (skip-fully! in (* block (quot (+ size (dec block)) block)) file)))

                      (skip-fully! in (* block (quot (+ size (dec block)) block)) file)))
                  (recur {}))))))))))
