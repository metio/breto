;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.template
  (:require [clojure.string :as str])
  (:import (java.util.regex Pattern)))

(def ^:private placeholder #"\{([a-z-]+)\}")

(defn- value [s vars k]
  (let [v (get vars (keyword k))]
    (when (nil? v)
      (throw (ex-info (str "unknown placeholder {" k "} in " s)
                      {:template s :placeholder k})))
    (str v)))

(defn render
  "Replaces every `{key}` in `s` with the value of `:key` in `vars`."
  [s vars]
  (str/replace s placeholder (fn [[_ k]] (value s vars k))))

(defn matcher
  "A regex matching what `s` renders to for any version, capturing the version
  as group `version`. The other placeholders render from `vars`."
  [s vars]
  (let [literal #(Pattern/quote %)
        parts (str/split s placeholder -1)
        keys (map second (re-seq placeholder s))]
    (re-pattern
     (str (literal (first parts))
          (apply str (map (fn [k part]
                            (str (if (= "version" k) "(?<version>.+?)" (literal (value s vars k)))
                                 (literal part)))
                          keys (rest parts)))))))
