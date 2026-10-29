;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.document
  "Values read out of a JSON or YAML document by a path."
  (:require [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]))

(defn yaml-url?
  "Whether the document at `url` is YAML, judged by the suffix of its path."
  [url]
  (boolean (re-find #"\.ya?ml$" (str/replace (str url) #"[?#].*$" ""))))

(def ^:private max-aliases
  "How many aliases of a list or map a YAML document may use. ghcup's metadata
  needs far more than SnakeYAML's default of 50; the bound keeps a document of
  nested aliases from expanding without end."
  10000)

(defn parse
  "The document `text` fetched from `url`: YAML when its name says so, else
  JSON, where a document of several values, one per line as in JSON Lines,
  is the list of them."
  [url text]
  (if (yaml-url? url)
    (yaml/parse-string text :keywords false :max-aliases-for-collections max-aliases)
    (let [values (vec (json/parsed-seq (java.io.StringReader. text)))]
      (if (= 1 (count values)) (first values) values))))

(defn- key-name [k]
  (if (keyword? k) (name k) k))

(defn- step [node k]
  (cond (nat-int? k) (when (sequential? node) (get (vec node) k))
        (map? k) (when (sequential? node)
                   (some (fn [element]
                           (when (and (map? element)
                                      (every? (fn [[f v]] (= v (str (get element (key-name f))))) k))
                             element))
                         node))
        :else (when (map? node) (get node (key-name k)))))

(defn at
  "The value at `path` in `doc`, or nil. Strings and keywords in `path` are
  keys, integers are indices, and a map selects the first element of a list
  whose fields have the values it names."
  [doc path]
  (reduce step doc path))

(defn deepest
  "How many steps of `path` lead to a value in `doc`."
  [doc path]
  (count (take-while some? (rest (reductions step doc path)))))

(defn path-valid?
  "Whether `path` is a non-empty vector of keys, indices and field selectors."
  [path]
  (and (vector? path) (seq path)
       (every? #(or (string? %) (keyword? %) (nat-int? %)
                    (and (map? %) (seq %) (every? (fn [[k v]] (and (or (string? k) (keyword? k)) (string? v))) %)))
               path)))
