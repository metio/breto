;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.archive-test
  (:require [babashka.fs :as fs]
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
