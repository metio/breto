;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.platform
  "The operating system and architecture a definition is resolved for, named
  like linux-x86_64. The top level of a definition describes linux-x86_64;
  its :platforms entries override keys for the others."
  (:require [clojure.string :as str]))

(def default "linux-x86_64")

(def known #{"linux-x86_64" "linux-aarch64" "darwin-x86_64" "darwin-aarch64"})

(defn- os [os-name]
  (let [n (str/lower-case (str os-name))]
    (cond (str/starts-with? n "linux") "linux"
          (or (str/starts-with? n "mac") (str/starts-with? n "darwin")) "darwin"
          (str/starts-with? n "windows") "windows"
          :else n)))

(defn- arch [os-arch]
  (case (str os-arch)
    ("amd64" "x86_64") "x86_64"
    ("aarch64" "arm64") "aarch64"
    (str os-arch)))

(defn detect
  "The platform of the running JVM, from os.name and os.arch."
  ([] (detect (System/getProperty "os.name") (System/getProperty "os.arch")))
  ([os-name os-arch] (str (os os-name) "-" (arch os-arch))))

(defn current
  "The platform `config` names in :platform, else the detected one."
  [config]
  (or (:platform config) (detect)))

(defn os-of [platform] (first (str/split platform #"-" 2)))

(defn arch-of [platform] (second (str/split platform #"-" 2)))
