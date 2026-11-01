;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.config-test
  (:require [breto.config :as config]
            [clojure.test :refer [deftest is]]))

(deftest the-bin-dir-is-the-users-directory-path-lists-first
  (is (= "/home/u/.local/bin" (config/default-bin-dir "/home/u" "/usr/bin:/home/u/.local/bin:/home/u/bin")))
  (is (= "/home/u/bin" (config/default-bin-dir "/home/u" "/home/u/bin/:/home/u/.local/bin")))
  (is (= "/home/u/bin" (config/default-bin-dir "/home/u" "/home/u/.cargo/bin:/home/u/bin:/usr/bin")))
  (is (= "/home/u/.local/bin" (config/default-bin-dir "/home/u" "/home/u/.cargo/bin:/usr/bin")))
  (is (= "/home/u/.local/bin" (config/default-bin-dir "/home/u" nil))))
