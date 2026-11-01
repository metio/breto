;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.config-test
  (:require [babashka.fs :as fs]
            [breto.config :as config]
            [clojure.test :refer [deftest is]]))

(deftest the-bin-dir-is-the-users-directory-path-lists-first
  (is (= "/home/u/.local/bin" (config/default-bin-dir "/home/u" "/usr/bin:/home/u/.local/bin:/home/u/bin")))
  (is (= "/home/u/bin" (config/default-bin-dir "/home/u" "/home/u/bin/:/home/u/.local/bin")))
  (is (= "/home/u/bin" (config/default-bin-dir "/home/u" "/home/u/.cargo/bin:/home/u/bin:/usr/bin")))
  (is (= "/home/u/.local/bin" (config/default-bin-dir "/home/u" "/home/u/.cargo/bin:/usr/bin")))
  (is (= "/home/u/.local/bin" (config/default-bin-dir "/home/u" nil))))

(deftest extra-registries-come-after-the-registries
  (let [file (str (fs/create-temp-file {:suffix ".edn"}))]
    (try
      (spit file (pr-str {:registries ["/a"] :extra-registries [{:github "o/r"} "/b"]}))
      (let [config (config/load-config file)]
        (is (= ["/a" {:github "o/r"} "/b"] (:registries config)))
        (is (not (contains? config :extra-registries))))
      (spit file (pr-str {:extra-registries ["~/defs"]}))
      (is (= [{:github "metio/packages"} (str (System/getProperty "user.home") "/defs")] (:registries (config/load-config file))))
      (finally (fs/delete file)))))

(deftest metio-packages-is-the-default-registry-and-registries-replaces-it
  (let [file (str (fs/create-temp-file {:suffix ".edn"}))]
    (try
      (spit file "{}")
      (is (= [{:github "metio/packages"}] (:registries (config/load-config file))))
      (spit file (pr-str {:registries ["/internal"]}))
      (is (= ["/internal"] (:registries (config/load-config file))))
      (finally (fs/delete file)))))
