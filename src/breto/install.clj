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
  "The tag and version to install: the requested `version`, or the newest one
  `:skip` does not name."
  [pkg io version]
  (if version
    {:tag (package/tag-of-version (package/at pkg version) version) :version version}
    (source/newest pkg io)))

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
        (let [text ((:get-text io) url {})]
          (or (checksum/expected text (:asset artifact))
              (throw (ex-info (str "no entry for " (:asset artifact) " in " url)
                              {:breto/problem :checksum-entry-missing :url url :asset (:asset artifact)
                               :entries (checksum/listed-names text)})))))))

(defn with-stage
  "Runs `f`, adding `stage` and `context` to anything it throws. Data from the
  failure itself wins, so the innermost stage is the one reported."
  [stage context f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (ex-message e) (merge context {:stage stage} (ex-data e)) e)))
    (catch java.io.IOException e
      (throw (ex-info (str (or (ex-message e) (.getName (class e))))
                      (merge context {:stage stage :breto/problem :filesystem
                                      :exception (.getName (class e)) :path (ex-message e)})
                      e)))))

(defn- staged
  "Reports `stage` to the progress callback of `io`, if any, then runs `f`
  within it."
  [io stage context f]
  (when-let [progress (:progress io)]
    (progress {:type :stage :stage stage}))
  (with-stage stage context f))

(defn- fetch!
  "Downloads and verifies the artifact into `work`, returning the file."
  [pkg io release artifact work verify? context]
  (let [file (fs/path work (:asset artifact))]
    (staged io :download context #((:download! io) (:url artifact) file))
    (when verify?
      (staged io :verify context
        #(if-let [want (expected-hash pkg io release artifact)]
           (checksum/verify! file (:asset artifact) want)
           (throw (ex-info (str (:name pkg) ": nothing to verify " (:asset artifact)
                                " against; GitHub has no digest for it and the package names no :checksum."
                                " Use --no-verify to install it anyway.")
                           {:breto/problem :no-checksum :package (:name pkg) :asset (:asset artifact)})))))
    file))

(defn- place!
  "Copies what `pkg` keeps from the unpacked archive into `dest`."
  [{:keys [layout] :as pkg} artifact unpacked dest]
  (if (= :tree layout)
    (fs/move unpacked dest)
    (do (fs/create-dirs dest)
        (doseq [[path stored] (package/stored-files pkg artifact)]
          (let [src (fs/path unpacked path)]
            (when-not (fs/regular-file? src)
              (throw (ex-info (str (:name pkg) ": " path " is not in " (:asset artifact))
                              {:breto/problem :path-missing :package (:name pkg) :path path
                               :asset (:asset artifact) :strip (:strip pkg)
                               :listing (archive/listing unpacked 200)})))
            (fs/copy src (fs/path dest stored))))))
  (doseq [path (distinct (vals (package/bin-paths pkg artifact)))]
    (fs/set-posix-file-permissions (fs/path dest path) "rwxr-xr-x")))

(defn install!
  "Installs `pkg` at `version` (latest when nil) and activates it unless
  `:activate?` is false. The download is verified unless `verify?` is false
  or the definition for that version says `:verify false`. Returns
  {:version v :installed? bool :verified? bool}. Failures carry
  the stage and release they happened in."
  [config io pkg {:keys [version activate? verify?] :or {activate? true verify? true}}]
  (let [context {:package (:name pkg) :repo (package/repo pkg) :source (:source pkg)}
        release (staged io :resolve context #(resolve-release pkg io version))
        version (:version release)
        pkg (package/at pkg version)
        verify? (and verify? (:verify pkg true))
        artifact (package/artifact pkg release)
        context (assoc context :version version :tag (:tag release) :asset (:asset artifact)
                       :url (:url artifact) :checksum-url (:checksum-url artifact)
                       :repo (package/repo pkg) :source (:source pkg))
        package-dir (store/package-dir config (:name pkg))
        bin-paths (package/bin-paths pkg artifact)
        installed? (not (store/installed? config (:name pkg) version (vals bin-paths)))]
    (when installed?
      (with-stage :place context #(fs/create-dirs package-dir))
      (let [work (fs/create-temp-dir {:dir package-dir :prefix store/work-prefix})
            lock (store/hold! work)]
        (try
          (let [file (fetch! pkg io release artifact work verify? context)
                unpacked (fs/path work "unpacked")
                staging (fs/path work "staged")]
            (staged io :unpack context
              #(archive/unpack! file (:archive artifact) (:asset artifact) unpacked (:strip pkg)))
            (staged io :place context
              #(do (place! pkg artifact unpacked staging)
                   (fs/delete-tree (store/version-dir config (:name pkg) version))
                   (fs/move staging (store/version-dir config (:name pkg) version) {:atomic-move true}))))
          (finally
            (fs/delete-tree work)
            (.close lock)
            (when (empty? (fs/list-dir package-dir))
              (fs/delete package-dir))))))
    (when activate?
      (staged io :activate context #(store/activate! config pkg bin-paths version)))
    {:version version :installed? installed? :verified? (and installed? verify?)}))

(defn use!
  "Activates an already installed version."
  [config pkg version]
  (let [pkg (package/at pkg version)
        artifact (package/artifact pkg {:tag (package/tag-of-version pkg version) :version version})
        bin-paths (package/bin-paths pkg artifact)]
    (when-not (store/installed? config (:name pkg) version (vals bin-paths))
      (throw (ex-info (str (:name pkg) " " version " is not installed")
                      {:breto/problem :not-installed :package (:name pkg) :version version
                       :installed (mapv :version (store/versions config (:name pkg)))})))
    (store/activate! config pkg bin-paths version)))
