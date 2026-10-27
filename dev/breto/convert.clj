;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.convert
  "Turns `install-latest-*` wrappers around the `install-binary-from-github`
  shell helpers into package definitions."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.http :as http]
            [breto.package :as package]
            [breto.source :as source]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import (java.net URLDecoder)))

(def ^:private parameters
  {"install-binary-from-github"
   [:orga :binary :index :archive :strip :downloaded :target]
   "install-binary-from-github-with-checksum"
   [:orga :binary :index :archive :checksum :strip :downloaded :target :additional]})

(def ^:private stub
  "#!/usr/bin/env sh\nprintf '%s\\0' \"$(basename \"$0\")\" \"$@\"\n")

(defn- stub-home! []
  (let [home (fs/create-temp-dir {:prefix "breto-convert-"})
        scripts (fs/path home ".local" "scripts")]
    (fs/create-dirs scripts)
    (doseq [helper (keys parameters)]
      (spit (str (fs/path scripts helper)) stub)
      (fs/set-posix-file-permissions (fs/path scripts helper) "rwxr-xr-x"))
    home))

(defn- captured-call
  "Runs `script` against the stub helpers and returns the arguments it passes."
  [home script]
  (let [out (:out (process/shell {:out :string :err :string :continue true :extra-env {"HOME" (str home)}}
                                 "sh" (str script)))
        [helper & args] (str/split out #"\u0000")]
    (when-let [names (get parameters helper)]
      (into {} (remove (comp str/blank? val)) (zipmap names args)))))

(defn- template [s]
  (-> s
      (str/replace-first "latest_tag" "{tag}")
      (str/replace-first "latest_version" "{version}")))

(defn- tag-prefix
  "The helpers take the version from character `index` (1-based) of the tag with
  `/` encoded as %2F. Index 1 and 2 are an empty and a `v` prefix; anything else
  is read off the current release."
  [orga index]
  (case index
    1 ""
    2 "v"
    (let [tag (:tag (source/latest {:name orga :source {:github orga} :tag-prefix ""} http/io))
          encoded (str/replace tag "/" "%2F")]
      (URLDecoder/decode (subs encoded 0 (dec index)) "UTF-8"))))

(defn- definition [name {:keys [orga binary index archive checksum strip downloaded target additional]}]
  (let [asset (template archive)
        raw? (= :raw (package/archive-type asset))
        downloaded (or downloaded binary)
        bins (cond-> {(or target binary) (if (and raw? (= downloaded archive)) "{asset}" (template downloaded))}
               additional (assoc additional additional))
        prefix (tag-prefix orga (parse-long index))
        strip (if (= :tar (package/archive-type asset)) (parse-long (or strip "0")) 0)
        pkg (cond-> {:source {:github orga} :asset asset}
              (not= "v" prefix) (assoc :tag-prefix prefix)
              checksum (assoc :checksum (template checksum))
              (pos? strip) (assoc :strip strip))]
    (cond-> pkg
      (not= bins (:bins (package/normalize (assoc pkg :name name)))) (assoc :bins bins))))

(defn- write! [file pkg]
  ;; REUSE-IgnoreStart
  (spit (str file)
        (str ";; SPDX-FileCopyrightText: The breto Authors\n"
             ";; SPDX-License-Identifier: 0BSD\n\n"
             (with-out-str (pprint/pprint pkg))))) ; REUSE-IgnoreEnd

(defn -main [script-dir out-dir]
  (let [home (stub-home!)]
    (try
      (fs/create-dirs out-dir)
      (doseq [script (sort (fs/glob script-dir "executable_install-latest-*"))
              :let [name (str/replace (str (fs/file-name script)) "executable_install-latest-" "")
                    call (captured-call home script)]]
        (if call
          (do (write! (fs/path out-dir (str name ".edn")) (definition name call))
              (println "[OK]" name))
          (println "[SKIP]" name "does not call a helper")))
      (finally
        (fs/delete-tree home)))))
