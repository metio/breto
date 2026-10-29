;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.version)

(defn- tokens [v]
  (map #(if (re-matches #"\d+" %) (bigint %) %)
       (re-seq #"\d+|[^\d\W_]+" v)))

(defn- compare-token [a b]
  (cond
    (and (number? a) (number? b)) (compare a b)
    (number? a) 1
    (number? b) -1
    :else (compare (.toLowerCase ^String a) (.toLowerCase ^String b))))

(def ^:private pre-release-words
  #{"alpha" "beta" "rc" "pre" "preview" "dev" "snapshot" "nightly" "canary" "milestone" "ea"})

(defn- pre-release? [token]
  (and (string? token) (contains? pre-release-words (.toLowerCase ^String token))))

(defn compare-versions
  "Orders version strings by their runs of digits and letters, with numbers
  compared numerically and ranked above words, so `1.10` follows `1.9` and
  `2026.9` follows `2025.12`. A version that is a prefix of another sorts
  first, unless the rest marks a pre-release: `1.0.0-rc1` precedes `1.0.0`."
  [a b]
  (loop [[x & xs :as as] (tokens a)
         [y & ys :as bs] (tokens b)]
    (cond
      (and (empty? as) (empty? bs)) 0
      (empty? as) (if (pre-release? y) 1 -1)
      (empty? bs) (if (pre-release? x) -1 1)
      :else (let [c (compare-token x y)]
              (if (zero? c) (recur xs ys) c)))))

(defn older? [a b]
  (neg? (compare-versions a b)))

(defn next-version
  "`version` with its last number incremented, the smallest :before boundary
  that still covers `version`. The number keeps its zero padding."
  [version]
  (if-let [[_ head n tail] (re-matches #"(.*?)(\d+)(\D*)" version)]
    (str head (format (str "%0" (count n) "d") (inc (parse-long n))) tail)
    (str version ".1")))
