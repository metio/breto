;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.archive-test
  (:require [babashka.fs :as fs]
            [babashka.process]
            [breto.archive :as archive]
            [breto.diagnose :as diagnose]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- zip! [name entries]
  (let [file (path name)]
    (with-open [out (java.util.zip.ZipOutputStream. (java.io.FileOutputStream. file))]
      (doseq [[entry content] entries]
        (.putNextEntry out (java.util.zip.ZipEntry. ^String entry))
        (.write out (.getBytes ^String content))
        (.closeEntry out)))
    file))

(deftest zip-entries-may-not-escape-the-archive
  (let [file (zip! "evil.zip" [["ok.txt" "y"] ["../escaped.txt" "x"]])
        data (try (archive/unpack! file :zip "evil.zip" (path "work" "unpacked") 0) nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= ["../escaped.txt"] (:escaping data)))
    (is (not (fs/exists? (path "work" "escaped.txt"))))
    (is (not (fs/exists? (path "escaped.txt"))))
    (is (str/includes? (:summary (diagnose/diagnose (ex-info "x" data) {})) "outside of its directory"))))

(deftest escaping-entries-are-found-wherever-they-hide
  (is (= ["a/../../x" "/etc/passwd"]
         (archive/escaping-entries (zip! "e.zip" [["a/b" "1"] ["a/../../x" "2"] ["a/../c" "3"] ["/etc/passwd" "4"]])))))

(deftest ordinary-zips-unpack-with-strip
  (let [file (zip! "ok.zip" [["tool-1.0/bin/tool" "t"] ["tool-1.0/README" "r"]])]
    (archive/unpack! file :zip "ok.zip" (path "out") 1)
    (is (= "t" (slurp (path "out" "bin" "tool"))))))

(deftest gzip-compressed-files-are-decompressed-without-the-suffix
  (let [file (path "tool.gz")]
    (with-open [out (java.util.zip.GZIPOutputStream. (java.io.FileOutputStream. (str file)))]
      (.write out (.getBytes "binary")))
    (archive/unpack! file :gz "tool.gz" (path "out") 0)
    (is (= ["tool"] (map (comp str fs/file-name) (fs/list-dir (path "out")))))
    (is (= "binary" (slurp (path "out" "tool"))))))

(deftest bzip2-compressed-files-are-decompressed-without-the-suffix
  (let [file (path "tool")]
    (spit (str file) "binary")
    (babashka.process/shell "bzip2" (str file))
    (archive/unpack! (path "tool.bz2") :bz2 "tool.bz2" (path "out") 0)
    (is (= "binary" (slurp (path "out" "tool"))))))

(deftest zstd-compressed-files-are-decompressed-without-the-suffix
  (let [file (path "tool")]
    (spit (str file) "binary")
    (babashka.process/shell "zstd" "--quiet" "--rm" (str file))
    (archive/unpack! (path "tool.zst") :zst "tool.zst" (path "out") 0)
    (is (= ["tool"] (map (comp str fs/file-name) (fs/list-dir (path "out")))))
    (is (= "binary" (slurp (path "out" "tool"))))))

(deftest zstd-compressed-tar-archives-unpack-with-strip
  (fs/create-dirs (path "src" "tool-1.0" "bin"))
  (spit (str (path "src" "tool-1.0" "bin" "tool")) "t")
  (babashka.process/shell "tar" "--create" "--zstd" "--file" (str (path "tool.tar.zst"))
                          "--directory" (str (path "src")) "tool-1.0")
  (archive/unpack! (path "tool.tar.zst") :tar "tool.tar.zst" (path "out") 1)
  (is (= "t" (slurp (path "out" "bin" "tool")))))

(deftest corrupt-compressed-files-fail-to-unpack
  (spit (str (path "bad.gz")) "not gzip")
  (spit (str (path "bad.bz2")) "not bzip2")
  (spit (str (path "bad.zst")) "not zstd")
  (is (= :unpack-failed (:breto/problem (try (archive/unpack! (path "bad.zst") :zst "bad.zst" (path "zst") 0) nil
                                             (catch clojure.lang.ExceptionInfo e (ex-data e))))))
  (is (= :unpack-failed (:breto/problem (try (archive/unpack! (path "bad.gz") :gz "bad.gz" (path "gz") 0) nil
                                             (catch clojure.lang.ExceptionInfo e (ex-data e))))))
  (is (= :unpack-failed (:breto/problem (try (archive/unpack! (path "bad.bz2") :bz2 "bad.bz2" (path "bz2") 0) nil
                                             (catch clojure.lang.ExceptionInfo e (ex-data e)))))))

(defn- patch!
  "Overwrites the bytes of `file` at `offset` (from the first occurrence of
  `marker`) with `replacement`."
  [file ^bytes marker offset ^bytes replacement]
  (let [data (fs/read-all-bytes file)
        at (first (filter (fn [i] (java.util.Arrays/equals (java.util.Arrays/copyOfRange data (int i) (int (+ i (alength marker)))) marker))
                          (range (- (alength data) (alength marker)))))]
    (System/arraycopy replacement 0 data (+ at offset) (alength replacement))
    (fs/write-bytes file data)))

(defn- int-le [n]
  (byte-array (for [shift [0 8 16 24]] (unchecked-byte (bit-shift-right n shift)))))

(def ^:private central-header (byte-array [0x50 0x4b 0x01 0x02]))

(deftest zip-entries-are-checked-by-the-name-they-unpack-under
  (let [file (zip! "split.zip" [["xx/escaped.txt" "x"]])]
    (patch! file (.getBytes "xx/escaped.txt") 0 (.getBytes "../escaped.txt"))
    (try (archive/unpack! file :zip "split.zip" (path "work" "unpacked") 0)
         (catch clojure.lang.ExceptionInfo _))
    (is (not (fs/exists? (path "work" "escaped.txt"))))
    (is (not (fs/exists? (path "escaped.txt"))))))

(deftest zip-entries-keep-their-unix-modes
  (let [file (zip! "modes.zip" [["tool-1.0/bin/tool" "t"]])]
    (patch! file central-header 5 (byte-array [3]))
    (patch! file central-header 38 (int-le (bit-shift-left 0100755 16)))
    (archive/unpack! file :zip "modes.zip" (path "out") 1)
    (is (fs/executable? (path "out" "bin" "tool")))))

(deftest zip-strip-merges-top-level-directories
  (let [file (zip! "mac.zip" [["tool/tool" "binary"] ["__MACOSX/tool/._tool" "resource fork"]
                              ["a/share/x" "1"] ["b/share/y" "2"]])]
    (archive/unpack! file :zip "mac.zip" (path "out") 1)
    (is (= "binary" (slurp (path "out" "tool"))))
    (is (= ["share/x" "share/y" "tool"] (archive/listing (path "out") 10)))))
