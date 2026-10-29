;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.sealed-test
  (:require [babashka.fs :as fs]
            [breto.sealed :as sealed]
            [breto.support :as s :refer [path]]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- tree! []
  (fs/create-dirs (path "tree" "lib"))
  (spit (path "tree" "lib" "a") "a")
  (spit (path "outside") "o")
  (fs/create-sym-link (path "tree" "link") (path "outside"))
  (path "tree"))

(defn- perms [p] (fs/posix->str (fs/posix-file-permissions p {:nofollow-links true})))

(deftest sealing-takes-every-write-bit-off-files-and-directories
  (let [dir (tree!)]
    (fs/set-posix-file-permissions (path "tree" "lib" "a") "rwxrwxr-x")
    (sealed/seal! dir)
    (is (sealed/sealed? dir))
    (is (= "r-xr-xr-x" (perms (path "tree" "lib"))))
    (is (= "r-xr-xr-x" (perms (path "tree" "lib" "a"))))
    (testing "the target of a link out of the tree keeps its permissions"
      (is (fs/writable? (path "outside"))))
    (is (thrown? java.io.IOException (spit (path "tree" "lib" "b") "b")))))

(deftest sealed-trees-can-be-deleted
  (let [dir (tree!)]
    (sealed/seal! dir)
    (sealed/delete-tree! dir)
    (is (not (fs/exists? dir)))
    (is (fs/exists? (path "outside")))))

(deftest writing-into-a-sealed-tree-seals-what-was-written
  (let [dir (tree!)
        target (fs/path dir ".breto" "completions" "bash")]
    (sealed/seal! dir)
    (sealed/writing! dir target #(do (fs/create-dirs (fs/parent target)) (spit (str target) "complete")))
    (is (= "complete" (slurp (str target))))
    (doseq [p [dir (fs/path dir ".breto") (fs/parent target) target]]
      (is (not (fs/writable? p)) (str p)))))

(deftest writing-into-an-open-tree-leaves-it-open
  (let [dir (tree!)
        target (fs/path dir "x")]
    (sealed/writing! dir target #(spit (str target) "x"))
    (is (fs/writable? dir))
    (is (fs/writable? target))))
