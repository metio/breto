;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.config-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.config :as config]
            [breto.support :as s]
            [briko.paths :as paths]
            [clojure.string :as str]
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
      (is (= [{:github "metio/packages"} (str (paths/home) "/defs")] (:registries (config/load-config file))))
      (finally (fs/delete file)))))

(deftest metio-packages-is-the-default-registry-and-registries-replaces-it
  (let [file (str (fs/create-temp-file {:suffix ".edn"}))]
    (try
      (spit file "{}")
      (is (= [{:github "metio/packages"}] (:registries (config/load-config file))))
      (spit file (pr-str {:registries ["/internal"]}))
      (is (= ["/internal"] (:registries (config/load-config file))))
      (finally (fs/delete file)))))

(deftest a-dangling-configuration-link-means-the-defaults
  (let [dir (fs/create-temp-dir)
        link (fs/path dir "config.edn")]
    (try
      (fs/create-sym-link link (fs/path dir "gone.edn"))
      (is (= (config/load-config (str (fs/path dir "absent.edn"))) (config/load-config (str link))))
      (finally (fs/delete-tree dir)))))

(deftest desktop-entries-are-linked-where-the-desktop-reads-them
  (is (str/ends-with? (config/desktop-dir "Linux") "/applications"))
  (is (str/ends-with? (config/desktop-dir "FreeBSD") "/applications"))
  (is (nil? (config/desktop-dir "Mac OS X")))
  (is (nil? (config/desktop-dir "Windows 11"))))

(defn- store-under
  "The :store of the defaults in a babashka of its own with `env` as its whole
  environment and user.home set to `user-home`."
  [env user-home]
  (let [{:keys [exit out err]}
        (process/shell {:out :string :err :string :continue true :dir (str (fs/temp-dir))
                        :env (merge {"PATH" (System/getenv "PATH")} env)}
                       (-> (java.lang.ProcessHandle/current) .info .command .get)
                       "--classpath" (s/absolute-classpath) (str "-Duser.home=" user-home)
                       "-e" "(require 'breto.config) (print (:store (breto.config/load-config \"/nonexistent/config.edn\")))")]
    (is (zero? exit) err)
    out))

(deftest the-home-is-home-and-relative-xdg-values-are-ignored
  (is (= "/env/home/.local/share/breto/installations" (store-under {"HOME" "/env/home"} "/passwd/home"))
      "HOME wins over user.home")
  (is (= "/passwd/home/.local/share/breto/installations" (store-under {"HOME" "relative"} "/passwd/home"))
      "a HOME that is not absolute falls back to user.home")
  (is (= "/env/home/.local/share/breto/installations"
         (store-under {"HOME" "/env/home" "XDG_DATA_HOME" "relative/data"} "/passwd/home"))
      "a relative XDG_DATA_HOME is ignored, as the XDG specification asks")
  (is (= "/data/breto/installations" (store-under {"HOME" "/env/home" "XDG_DATA_HOME" "/data"} "/passwd/home"))))
