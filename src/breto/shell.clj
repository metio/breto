;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.shell
  "Text for POSIX sh, bash and fish."
  (:require [clojure.string :as str]))

(defn quote-arg
  "`s` as one single-quoted word for POSIX sh, bash and zsh, which read it
  literally."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn fish-quote
  "`s` as one single-quoted word for fish, which reads \\ and \\' as escapes
  inside single quotes, so both are escaped."
  [s]
  (str "'" (-> (str s) (str/replace "\\" "\\\\") (str/replace "'" "\\'")) "'"))

(defn quote-for
  "`s` as one word for `shell`, :fish or any POSIX shell."
  [shell s]
  (if (= :fish shell) (fish-quote s) (quote-arg s)))
