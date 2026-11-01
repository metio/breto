;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.shell
  "Text for POSIX sh, bash and fish."
  (:require [clojure.string :as str]))

(defn quote-arg
  "`s` as one single-quoted word, which every shell breto writes for reads
  literally."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))
