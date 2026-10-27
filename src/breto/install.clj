;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.install
  (:require [babashka.fs :as fs]
            [breto.archive :as archive]
            [breto.checksum :as checksum]
            [breto.github :as github]
            [breto.package :as package]
            [breto.source :as source]
            [breto.store :as store]
            [clojure.string :as str]))

(defn resolve-release
  "The tag and version to install: the requested `version`, or the latest one."
  [pkg io version]
  (if version
    {:tag (package/tag-of-version (package/at pkg version) version) :version version}
    (source/latest pkg io)))

(defn- github-asset? [pkg artifact]
  (when-let [repo (get-in pkg [:source :github])]
    (str/starts-with? (:url artifact) (str "https://github.com/" repo "/releases/download/"))))

(defn expected-hash
  "The hash a download must match: the digest GitHub recorded for a release
  asset, else the entry in the package's checksum file, else nil. A failed
  GitHub lookup only falls through when a checksum file can stand in."
  [pkg io release artifact]
  (or (when (github-asset? pkg artifact)
        (try
          (github/asset-digest (github/release io (get-in pkg [:source :github]) (:tag release))
                               (:asset artifact))
          (catch clojure.lang.ExceptionInfo e
            (when-not (:checksum-url artifact)
              (throw e)))))
      (when-let [url (:checksum-url artifact)]
        (or (checksum/expected ((:get-text io) url {}) (:asset artifact))
            (throw (ex-info (str "no entry for " (:asset artifact) " in " url) {:url url}))))))

(defn- fetch!
  "Downloads and verifies the artifact into `work`, returning the file."
  [pkg io release artifact work verify?]
  (let [file (fs/path work (:asset artifact))]
    ((:download! io) (:url artifact) file)
    (when verify?
      (if-let [want (expected-hash pkg io release artifact)]
        (checksum/verify! file (:asset artifact) want)
        (throw (ex-info (str (:name pkg) ": nothing to verify " (:asset artifact)
                             " against; GitHub has no digest for it and the package names no :checksum."
                             " Use --no-verify to install it anyway.")
                        {:package (:name pkg) :asset (:asset artifact)}))))
    file))

(defn- place!
  "Copies what `pkg` keeps from the unpacked archive into `dest`."
  [{:keys [layout] :as pkg} artifact unpacked dest]
  (if (= :tree layout)
    (fs/move unpacked dest)
    (do (fs/create-dirs dest)
        (doseq [path (distinct (vals (:bins artifact)))]
          (let [src (fs/path unpacked path)]
            (when-not (fs/regular-file? src)
              (throw (ex-info (str (:name pkg) ": " path " is not in " (:asset artifact))
                              {:package (:name pkg) :path path})))
            (fs/copy src (fs/path dest (package/file-name path)) {:replace-existing true})))))
  (doseq [path (distinct (vals (package/bin-paths pkg artifact)))]
    (fs/set-posix-file-permissions (fs/path dest path) "rwxr-xr-x")))

(defn install!
  "Installs `pkg` at `version` (latest when nil) and activates it unless
  `:activate?` is false. Returns {:version v :installed? bool}."
  [config io pkg {:keys [version activate? verify?] :or {activate? true verify? true}}]
  (let [release (resolve-release pkg io version)
        version (:version release)
        pkg (package/at pkg version)
        artifact (package/artifact pkg release)
        package-dir (store/package-dir config (:name pkg))
        installed? (not (store/installed? config (:name pkg) version))]
    (when installed?
      (fs/create-dirs package-dir)
      (let [work (fs/create-temp-dir {:dir package-dir :prefix ".work-"})]
        (try
          (let [file (fetch! pkg io release artifact work verify?)
                unpacked (fs/path work "unpacked")
                staging (fs/path package-dir (str ".staging-" version))]
            (archive/unpack! file (:archive artifact) (:asset artifact) unpacked (:strip pkg))
            (fs/delete-tree staging)
            (place! pkg artifact unpacked staging)
            (fs/move staging (store/version-dir config (:name pkg) version) {:atomic-move true}))
          (finally
            (fs/delete-tree work)))))
    (when activate?
      (store/activate! config pkg (package/bin-paths pkg artifact) version))
    {:version version :installed? installed?}))

(defn use!
  "Activates an already installed version."
  [config pkg version]
  (when-not (store/installed? config (:name pkg) version)
    (throw (ex-info (str (:name pkg) " " version " is not installed")
                    {:package (:name pkg) :version version})))
  (let [pkg (package/at pkg version)
        artifact (package/artifact pkg {:tag (package/tag-of-version pkg version) :version version})]
    (store/activate! config pkg (package/bin-paths pkg artifact) version)))
