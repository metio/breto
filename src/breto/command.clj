;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.command
  "The shape of a command and of variables, which project files, groups and
  package definitions share."
  (:require [babashka.fs :as fs]
            [breto.platform :as platform]
            [clojure.string :as str]))

(defn env-problems [env]
  (when-not (and (map? env)
                 (every? (fn [[k v]] (and (string? k) (re-matches #"[A-Za-z_][A-Za-z0-9_]*" k)
                                          (not= "PATH" k) (not (str/starts-with? k "BRETO_"))
                                          (string? v)))
                         env))
    [":env maps variable names other than PATH and BRETO_* to strings"]))

(defn- run-valid? [run]
  (or (and (string? run) (not (str/blank? run)))
      (and (vector? run) (seq run) (every? string? run))))

(defn problems [name {:keys [run depends description env platforms dir] :as command}]
  (let [entry-valid? (fn [e] (and (map? e) (every? #{:run :env} (keys e))
                                  (or (nil? (:run e)) (run-valid? (:run e)))
                                  (empty? (env-problems (:env e {})))))]
    (cond-> []
      (not (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" (str name)))
      (conj (str "command name " (pr-str name) " needs letters, digits, ., _ and - only"))

      (not (map? command))
      (conj (str "command " name " must be a map with :run, :depends or both"))

      (and (map? command) (nil? run) (empty? depends))
      (conj (str "command " name " needs :run, :depends or both"))

      (and (some? run) (not (run-valid? run)))
      (conj (str "command " name ": :run is a shell line or a vector of program and arguments"))

      (and (some? depends) (not (and (vector? depends) (every? string? depends))))
      (conj (str "command " name ": :depends is a vector of command names"))

      (and (some? description) (not (string? description)))
      (conj (str "command " name ": :description is a string"))

      (and (some? dir) (not (and (string? dir) (not (fs/absolute? dir)))))
      (conj (str "command " name ": :dir is a directory relative to the project"))

      (some? env) (into (map #(str "command " name ": " %) (env-problems env)))

      (and (some? platforms) (not (and (map? platforms) (every? platform/known (keys platforms))
                                       (every? entry-valid? (vals platforms)))))
      (conj (str "command " name ": :platforms maps " (str/join ", " (sort platform/known))
                 " to maps of :run and :env")))))
