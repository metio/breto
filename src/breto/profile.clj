;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.profile
  "Profiles: variables a project switches between, such as the cluster or the
  cloud a shell talks to. :profiles maps each profile, such as \"kube\", to
  its options, such as \"staging\" and \"production\", each with :env and
  optionally a :description and :confirm. At most one option of each profile
  is selected at a time, so selecting a cluster and a cloud never mixes two
  clusters. The configuration and the project files define profiles; the
  selection belongs to one project and stays out of version control."
  (:require [babashka.fs :as fs]
            [breto.command :as command]
            [breto.store :as store]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private name-pattern
  "Names of profiles and options: they appear in BRETO_PROFILES as
  profile=option, separated by spaces, and on the command line."
  #"[A-Za-z0-9][A-Za-z0-9._-]*")

(defn valid-name? [s]
  (and (string? s) (some? (re-matches name-pattern s))))

(def ^:private option-keys #{:env :description :confirm})

(defn- option-problems [profile option-name option]
  (let [where (str "option " option-name " of profile " profile)]
    (cond
      (not (valid-name? option-name))
      [(str "the option " (pr-str option-name) " of profile " profile
            " is no name of letters, digits, dots, underscores and dashes")]
      (not (map? option)) [(str where " is a map with :env")]
      :else
      (cond-> []
        (seq (remove option-keys (keys option)))
        (conj (str where " has unknown keys " (str/join ", " (map pr-str (sort (remove option-keys (keys option)))))
                   "; an option has :env, :description and :confirm"))
        (some? (:env option)) (into (map #(str where ": " %) (command/env-problems (:env option))))
        (and (some? (:description option)) (not (string? (:description option))))
        (conj (str where ": :description is a string"))
        (and (some? (:confirm option)) (not (boolean? (:confirm option))))
        (conj (str where ": :confirm is true or false"))))))

(defn problems
  "What is wrong with `profiles`, the :profiles of a configuration or project
  file."
  [profiles]
  (if-not (map? profiles)
    [":profiles maps profile names to their options"]
    (vec (mapcat (fn [[profile options]]
                   (cond
                     (not (valid-name? profile))
                     [(str "the profile " (pr-str profile) " is no name of letters, digits, dots, underscores and dashes")]
                     (not (and (map? options) (seq options)))
                     [(str "the profile " profile " maps option names to options")]
                     :else (mapcat (fn [[k v]] (option-problems profile k v)) options)))
                 profiles))))

(defn merge-profiles
  "The profiles of `layers`, later layers over earlier ones: a profile gains
  the options of every layer, and an option a later layer defines replaces
  the earlier one of the same name as a whole."
  [& layers]
  (apply merge-with merge {} (remove nil? layers)))

(defn read-selection
  "The selection stored at `path`, profile -> option, empty when there is
  none."
  [path]
  (if (fs/regular-file? path)
    (let [data (try (edn/read-string (slurp (str path)))
                    (catch RuntimeException _ ::invalid))]
      (if (and (map? data) (every? valid-name? (keys data)) (every? valid-name? (vals data)))
        data
        (throw (ex-info (str path " holds no selection of profiles")
                        {:breto/problem :invalid-selection :file (str path)}))))
    {}))

(defn write-selection!
  "Stores `selection` at `path`, replacing the file atomically. An empty
  selection is written too, since the shell hook notices a file that
  changed, never one that went missing."
  [path selection]
  (let [staged (store/staged-path (fs/parent path) (str (fs/file-name path)))]
    (fs/create-dirs (fs/parent path))
    (spit (str staged) (str (pr-str (into (sorted-map) selection)) "\n"))
    (fs/move staged path {:replace-existing true :atomic-move true}))
  (str path))

(defn active
  "{:selected :warnings}: the entries of `selection` naming an option of
  `profiles`, and a warning for each that does not, since the definitions may
  have changed since the selection was made."
  [profiles selection]
  (reduce (fn [acc [profile option]]
            (if (get-in profiles [profile option])
              (assoc-in acc [:selected profile] option)
              (update acc :warnings conj
                      (str "the selected option " option " of profile " profile
                           " is no longer defined, so its variables are not set"))))
          {:selected {} :warnings []}
          (sort selection)))

(defn variables
  "The variables the `selected` options of `profiles` set. Two of them setting
  one variable to different values is an error, since neither selection has
  the better claim."
  [profiles selected]
  (let [owners (for [[profile option] (sort selected)
                     [k v] (get-in profiles [profile option :env])]
                 [k (str profile "=" option) v])]
    (doseq [[k entries] (group-by first owners)
            :when (< 1 (count (distinct (map #(nth % 2) entries))))]
      (throw (ex-info (str "the selected profiles " (str/join " and " (map second entries)) " all set " k)
                      {:breto/problem :profile-conflict :variable k :selected (mapv second entries)})))
    (into {} (map (fn [[k _ v]] [k v])) owners)))

(defn label
  "The selection as BRETO_PROFILES carries it: profile=option pairs, sorted
  and separated by spaces."
  [selected]
  (str/join " " (for [[profile option] (sort selected)] (str profile "=" option))))
