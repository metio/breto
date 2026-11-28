;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.store-test
  (:require [babashka.fs :as fs]
            [breto.store :as store]
            [breto.support :as s :refer [path]]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- names [dir]
  (set (map (comp str fs/file-name) (fs/list-dir dir))))

(deftest links-point-at-their-new-target-and-leave-nothing-behind
  (fs/create-dirs (path "links"))
  (fs/create-sym-link (path "links" "current") "1.0.0")
  (store/link! (path "links" "current") "2.0.0")
  (is (= "2.0.0" (str (fs/read-link (path "links" "current")))))
  (is (= #{"current"} (names (path "links")))))

(deftest links-create-their-directory
  (store/link! (path "a" "b" "tool") (path "target"))
  (is (= (path "target") (str (fs/read-link (path "a" "b" "tool"))))))

(deftest links-replace-a-regular-file
  (fs/create-dirs (path "links"))
  (spit (path "links" "tool") "a file")
  (store/link! (path "links" "tool") "target")
  (is (fs/sym-link? (path "links" "tool")))
  (is (= "target" (str (fs/read-link (path "links" "tool"))))))

(deftest files-other-processes-prepare-are-staged
  (is (store/staged? (path (str ".tool." (random-uuid) ".breto"))))
  (is (not (store/staged? (path "tool"))))
  (is (not (store/staged? (path ".profile")))))
