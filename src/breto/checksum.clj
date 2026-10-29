;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.checksum
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(def ^:private algorithm-by-hex-length
  {32 "MD5" 40 "SHA-1" 64 "SHA-256" 128 "SHA-512"})

(defn digest
  "Hex digest of `file` using a java.security algorithm name such as \"SHA-256\"."
  [algorithm file]
  (let [md (MessageDigest/getInstance algorithm)
        buffer (byte-array 65536)]
    (with-open [in (io/input-stream (fs/file file))]
      (loop []
        (let [n (.read in buffer)]
          (when (pos? n)
            (.update md buffer 0 n)
            (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn- hex? [s]
  (and (contains? algorithm-by-hex-length (count s))
       (boolean (re-matches #"[0-9a-fA-F]+" s))))

(defn- listed-name [s]
  (-> s (str/replace #"^\*" "") fs/file-name str))

(defn expected
  "The hash for `asset` in a checksum file. Accepts `sha256sum`-style listings
  and single-entry files, whose one hash is taken whatever name it lists or
  whether it lists one at all. Entries for files of the same name in several
  directories are told apart by the end of the asset's `url`."
  ([text asset] (expected text asset nil))
  ([text asset url]
   (let [lines (->> (str/split-lines text)
                    (map str/trim)
                    (remove str/blank?)
                    (map #(str/split % #"\s+")))
         matches (for [[h & more] lines
                       :when (and (hex? h) (seq more) (= asset (listed-name (last more))))]
                   [(str/lower-case h) (str/replace (last more) #"^\*?(\./)?" "")])
         by-path (filter (fn [[_ path]] (and url (str/ends-with? url (str "/" path)))) matches)
         hashes #(distinct (map first %))]
     (cond
       (= 1 (count (hashes matches))) (ffirst matches)
       (= 1 (count (hashes by-path))) (ffirst by-path)
       (seq matches) (throw (ex-info (str "the checksum file lists " asset " more than once with different hashes")
                                     {:breto/problem :checksum-ambiguous :asset asset :url url
                                      :entries (mapv second matches)}))
       :else (when-let [[[h]] (seq lines)]
               (when (and (= 1 (count lines)) (hex? h))
                 (str/lower-case h)))))))

(defn json-expected
  "The hash at `path` in the JSON document `text`, or nil. Strings and
  keywords in `path` are keys, integers are indices."
  [text path]
  (let [step (fn [node k] (if (nat-int? k)
                            (when (sequential? node) (get (vec node) k))
                            (when (map? node) (get node (if (keyword? k) (name k) k)))))
        value (reduce step (json/parse-string text) path)]
    (when (and (string? value) (hex? value))
      (str/lower-case value))))

(defn verify!
  "Throws unless `file` hashes to the hex string `want`. The algorithm follows
  from the length of `want`; `origin` names where `want` came from when it is
  not the release itself, such as :lockfile."
  ([file asset want] (verify! file asset want nil))
  ([file asset want origin]
   (let [want (str/lower-case want)
         algorithm (algorithm-by-hex-length (count want))]
     (when-not algorithm
       (throw (ex-info (str "unrecognised checksum for " asset ": " want)
                       {:breto/problem :checksum-format :asset asset :checksum want})))
     (let [have (digest algorithm file)]
       (when-not (= want have)
         (throw (ex-info (str "checksum mismatch for " asset ": expected " want ", got " have)
                         (cond-> {:breto/problem :checksum-mismatch :asset asset :expected want :actual have}
                           origin (assoc :origin origin)))))))))

(defn listed-names
  "The file names a checksum listing mentions."
  [text]
  (->> (str/split-lines text)
       (map #(str/split (str/trim %) #"\s+"))
       (keep (fn [[h & more]] (when (and (hex? h) (seq more)) (listed-name (last more)))))
       vec))
