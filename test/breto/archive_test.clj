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
