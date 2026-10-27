;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.test-runner
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as test]))

(defn- namespace-of [root file]
  (-> (str (fs/strip-ext (fs/relativize root file)))
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))

(defn run
  "Runs every *_test.clj namespace under `root`, exiting non-zero on failure."
  [root]
  (let [namespaces (sort (map #(namespace-of root %) (fs/glob root "**_test.clj")))]
    (apply require namespaces)
    (let [{:keys [fail error]} (apply test/run-tests namespaces)]
      (when (pos? (+ fail error))
        (System/exit 1)))))
