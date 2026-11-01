;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.run-test
  (:require [babashka.fs :as fs]
            [breto.run :as run]
            [clojure.test :refer [deftest is]]))

(defn- program-with-head? [& head]
  (let [file (fs/create-temp-file)]
    (try
      (fs/write-bytes file (byte-array (map unchecked-byte head)))
      (run/program? file)
      (finally (fs/delete file)))))

(deftest scripts-and-native-binaries-are-programs
  (is (program-with-head? 0x23 0x21 0x2f 0x62))
  (is (program-with-head? 0x23 0x21))
  (is (program-with-head? 0x7f 0x45 0x4c 0x46 0x02))
  (is (program-with-head? 0xcf 0xfa 0xed 0xfe))
  (is (program-with-head? 0xca 0xfe 0xba 0xbe)))

(deftest data-is-no-program
  (is (not (program-with-head? 0x50 0x4b 0x03 0x04)))
  (is (not (program-with-head? 0x23)))
  (is (not (program-with-head?))))
