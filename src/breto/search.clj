;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.search
  "Finding packages in the registries by name, description and source, and
  choosing among them with a fuzzy finder or a numbered prompt."
  (:require [babashka.fs :as fs]
            [breto.package :as package]
            [clojure.string :as str]))

(defn origin
  "Where the releases of `pkg` come from, as a repository or URL."
  [{:keys [source]}]
  (some #(get source %) (sort package/source-kinds)))

(defn- one-line [s]
  (str/trim (str/replace (str s) #"\s+" " ")))

(defn matches
  "The definitions of `registry` whose name, description or origin contains
  every one of `terms`, ignoring case, sorted by name."
  [registry terms]
  (let [terms (map str/lower-case (remove str/blank? terms))]
    (->> (vals registry)
         (filter (fn [pkg]
                   (let [text (str/lower-case (str (:name pkg) " " (:description pkg) " " (origin pkg)))]
                     (every? #(str/includes? text %) terms))))
         (sort-by :name))))

(defn row
  "One tab-separated line per package: name, description, origin."
  [pkg]
  (let [description (one-line (:description pkg))
        description (if-let [successor (:superseded-by pkg)]
                      (str/triml (str description " (superseded by " successor ")"))
                      description)]
    (str/join "\t" [(:name pkg) description (one-line (origin pkg))])))

(def ^:private finders ["fzf" "sk"])

(defn finder
  "The fuzzy finder to choose with: the :finder command of the config, or
  with :auto the first of fzf and sk linked into the bin directory or on PATH.
  nil when there is none. `which` resolves a command name to its path."
  [{:keys [finder bin-dir]} which]
  (cond
    (vector? finder) (when (which (first finder)) finder)
    (= :auto finder)
    (some (fn [program]
            (let [linked (some-> bin-dir (fs/path program))]
              (cond
                (and linked (fs/executable? linked)) [(str linked)]
                (which program) [program])))
          finders)))

(defn finder-command
  "The finder invocation for `finder`: the built-in finders get multi-select,
  the name and description to search, and the `query`; a configured command
  runs as given."
  [{configured :finder} finder query]
  (if (vector? configured)
    finder
    (into finder ["--multi" "--delimiter" "\t" "--with-nth" "1,2"
                  "--prompt" "breto install> " "--query" (or query "")])))

(defn selected
  "The package names in the lines a finder printed."
  [out]
  (->> (str/split-lines (or out ""))
       (remove str/blank?)
       (map #(first (str/split % #"\t" 2)))
       vec))

(def page 30)

(defn numbered
  "The prompt's listing of `pkgs`: at most `page` numbered lines, and how many
  more matched."
  [pkgs]
  (concat (map-indexed (fn [i pkg]
                         (str (format "%3d) " (inc i)) (:name pkg)
                              (when-let [d (not-empty (one-line (:description pkg)))] (str " - " d))))
                       (take page pkgs))
          (when (> (count pkgs) page)
            [(str "     and " (- (count pkgs) page) " more; search with more words to narrow them down")])))

(defn numbers
  "The numbers in `answer` when it holds only numbers, else nil."
  [answer]
  (let [words (remove str/blank? (str/split (str/trim (or answer "")) #"[\s,]+"))]
    (when (and (seq words) (every? #(re-matches #"\d+" %) words))
      (mapv parse-long words))))

(defn prompt!
  "Asks for a search, lists the matches and reads the numbers to install, until
  an answer of numbers or a blank one. `ask` prints a question and returns the
  answer line, nil at the end of input."
  [registry ask query]
  (loop [terms (when-not (str/blank? query) (str/split query #"\s+"))]
    (if (nil? terms)
      (let [answer (ask "Search for packages: ")]
        (when-not (str/blank? answer)
          (recur (str/split (str/trim answer) #"\s+"))))
      (let [found (vec (matches registry terms))]
        (if (empty? found)
          (do (println (str "No package matches " (str/join " " terms) "."))
              (recur nil))
          (do (run! println (numbered found))
              (let [answer (ask "Numbers to install, or another search (blank to stop): ")
                    chosen (numbers answer)]
                (cond
                  (str/blank? answer) []
                  chosen (vec (distinct (keep #(when (<= 1 % (min page (count found))) (:name (nth found (dec %))))
                                              chosen)))
                  :else (recur (str/split (str/trim answer) #"\s+"))))))))))
