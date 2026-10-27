;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.log
  (:require [clojure.string :as str]))

(def ^:dynamic *debug* false)

(defn debug [& parts]
  (when *debug*
    (binding [*out* *err*]
      (println (str "[DEBUG] " (str/join " " parts))))))
