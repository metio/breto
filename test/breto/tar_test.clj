;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.tar-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.archive :as archive]
            [breto.checksum :as checksum]
            [breto.support :as s :refer [path]]
            [breto.tar :as tar]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(def ^:private long-name
  (str "release/" (str/join "/" (repeat 12 "a-rather-long-directory")) "/file.txt"))

(defn- tree!
  "A directory with an executable, a plain file, a symlink, a hard link, an
  empty directory and a path longer than the 100 bytes of a ustar name."
  []
  (let [src (path "src")]
    (fs/create-dirs (fs/path src "release" "bin"))
    (fs/create-dirs (fs/path src "release" "empty"))
    (fs/create-dirs (fs/parent (fs/path src long-name)))
    (spit (str (fs/path src "release" "bin" "tool")) "#!/bin/sh\necho tool\n")
    (fs/set-posix-file-permissions (fs/path src "release" "bin" "tool") "rwxr-xr-x")
    (spit (str (fs/path src "release" "README")) "read me\n")
    (spit (str (fs/path src long-name)) "deep\n")
    (fs/create-sym-link (fs/path src "release" "tool") "bin/tool")
    (fs/create-link (fs/path src "release" "README.hard") (fs/path src "release" "README"))
    src))

(defn- gnu-tar! [out & args]
  (apply process/shell {:out :string :err :string} "tar" "--create" "--file" (str out) args)
  out)

(defn- snapshot
  "Every entry under `dir` as relative path -> [kind detail], where detail is
  the content hash, the link target or the executable bit."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob dir "**" {:hidden true})
              :let [rel (str (fs/relativize dir f))]]
          [rel (cond
                 (fs/sym-link? f) [:link (str (fs/read-link f))]
                 (fs/directory? f) [:dir]
                 :else [:file (checksum/digest "SHA-256" f) (fs/executable? f)])])))

(defn- gnu-unpack [file strip]
  (let [out (path (str "gnu-" (fs/file-name file) "-" strip))]
    (fs/create-dirs out)
    (process/shell {:out :string :err :string} "tar" "--extract" "--file" (str file)
                   "--directory" (str out) (str "--strip-components=" strip))
    out))

(defn- ours [file strip]
  (let [out (path (str "ours-" (fs/file-name file) "-" strip))]
    (tar/unpack! file out strip)
    out))

(deftest unpacks-like-gnu-tar
  (let [src (tree!)
        archives {"gnu.tar.gz" ["--gzip" "--format=gnu"]
                  "pax.tar.gz" ["--gzip" "--format=pax"]
                  "dot.tar.gz" ["--gzip"]}]
    (doseq [[name flags] archives
            :let [members (if (= "dot.tar.gz" name) ["./release"] ["release"])
                  file (apply gnu-tar! (path name) (concat flags ["--directory" (str src)] members))]
            strip [0 1 2]]
      (testing (str name " with strip " strip)
        (is (tar/readable? file))
        (is (= (snapshot (gnu-unpack file strip)) (snapshot (ours file strip))))))
    (testing "ustar, whose names are limited to 255 bytes"
      (fs/delete-tree (fs/path src "release" "a-rather-long-directory"))
      (let [file (gnu-tar! (path "short.tar") "--format=ustar" "--directory" (str src) "release")]
        (doseq [strip [0 1]]
          (is (= (snapshot (gnu-unpack file strip)) (snapshot (ours file strip)))))))))

(deftest keeps-executables-executable
  (let [src (tree!)
        file (gnu-tar! (path "t.tar.gz") "--gzip" "--directory" (str src) "release")
        out (ours file 1)]
    (is (fs/executable? (fs/path out "bin" "tool")))
    (is (not (fs/executable? (fs/path out "README"))))
    (is (= "#!/bin/sh\necho tool\n" (slurp (str (fs/path out "tool")))))))

(deftest strip-counts-a-leading-dot-like-gnu-tar
  (is (= "release/bin/tool" (tar/relative-path "./release/bin/tool" 1)))
  (is (= "bin/tool" (tar/relative-path "./release/bin/tool" 2)))
  (is (= "bin/tool" (tar/relative-path "release/bin/tool" 1)))
  (is (nil? (tar/relative-path "release/" 1)))
  (is (= :escapes (tar/relative-path "../evil" 0)))
  (is (= :escapes (tar/relative-path "release/../../evil" 1)))
  (is (= :escapes (tar/relative-path "/etc/passwd" 0))))

(defn- problem [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest refuses-entries-outside-of-the-target
  (spit (str (path "evil")) "evil\n")
  (let [file (gnu-tar! (path "escape.tar.gz") "--gzip" "--directory" (str (path)) "--transform=s,^,../,"
                       "evil")
        data (problem #(tar/unpack! file (path "deep" "out") 0))]
    (is (= :unpack-failed (:breto/problem data)))
    (is (= ["../evil"] (:escaping data)))
    (is (not (fs/exists? (path "deep" "evil"))))))

(deftest refuses-to-write-through-a-symlink
  (let [outside (path "outside")
        a (path "a")
        b (path "b")
        file (path "through.tar")]
    (fs/create-dirs outside)
    (fs/create-dirs a)
    (fs/create-sym-link (fs/path a "link") (str outside))
    (gnu-tar! file "--directory" (str a) "link")
    (fs/create-dirs (fs/path b "link"))
    (spit (str (fs/path b "link" "planted")) "x\n")
    (process/shell {:out :string :err :string} "tar" "--append" "--file" (str file) "--directory" (str b) "link/planted")
    (let [data (problem #(tar/unpack! file (path "out") 0))]
      (is (= :unpack-failed (:breto/problem data)))
      (is (str/includes? (:stderr data) "passes through a symlink")))
    (is (empty? (fs/list-dir outside)))))

(deftest damaged-and-truncated-archives-are-reported
  (let [src (tree!)
        file (gnu-tar! (path "t.tar") "--directory" (str src) "release")
        bytes (fs/read-all-bytes file)]
    (testing "a damaged header"
      (let [damaged (path "damaged.tar")]
        (aset-byte bytes 10 (byte 65))
        (fs/write-bytes damaged bytes)
        (is (not (tar/readable? damaged)))
        (is (str/includes? (:stderr (problem #(tar/unpack! damaged (path "d") 0))) "checksum"))))
    (testing "a cut-off archive"
      (let [cut (path "cut.tar")]
        (fs/write-bytes cut (java.util.Arrays/copyOf (fs/read-all-bytes file) 700))
        (is (str/includes? (:stderr (problem #(tar/unpack! cut (path "c") 0))) "ends in the middle"))))))

(deftest other-formats-go-to-the-tar-program
  (let [src (tree!)]
    (is (not (tar/readable? (s/file! "random.tar.gz" "not an archive at all"))))
    (when (fs/which "xz")
      (let [file (gnu-tar! (path "t.tar.xz") "--xz" "--directory" (str src) "release")]
        (is (not (tar/readable? file)))
        (archive/unpack! file :tar "t.tar.xz" (path "xz-out") 1)
        (is (fs/executable? (fs/path (path "xz-out") "bin" "tool")))))))

(deftest pax-records-are-parsed
  (is (= {"path" "a/b" "size" "12"} (tar/pax-records "12 path=a/b\n11 size=12\n")))
  (is (= {"path" "with space=and equals"} (tar/pax-records "30 path=with space=and equals\n"))))

(deftest permissions-render-as-posix-strings
  (is (= "rwxr-xr-x" (tar/permissions 0755 false)))
  (is (= "rw-------" (tar/permissions 0 false)))
  (is (= "rwx------" (tar/permissions 0 true))))

(deftest refuses-hard-links-that-read-through-a-symlink
  (let [outside (path "outside")
        a (path "a")
        b (path "b")
        file (path "leak.tar")]
    (fs/create-dirs outside)
    (spit (str (fs/path outside "secret")) "key\n")
    (fs/create-dirs a)
    (fs/create-sym-link (fs/path a "d") (str outside))
    (gnu-tar! file "--directory" (str a) "d")
    (fs/create-dirs b)
    (spit (str (fs/path b "x")) "x\n")
    (fs/create-link (fs/path b "leak") (fs/path b "x"))
    (process/shell {:out :string :err :string} "tar" "--append" "--file" (str file) "--directory" (str b)
                   "--transform=s,^x$,d/secret,R" "x" "leak")
    (let [data (problem #(tar/unpack! file (path "out") 0))]
      (is (= :unpack-failed (:breto/problem data)))
      (is (not (fs/exists? (path "out" "leak")))))))
