;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.ghcup
  "Haskell tools installed with ghcup. breto downloads the generic Linux
  bindist that ghcup's metadata lists for a version and checks it against the
  hash listed beside it, like any other asset. ghcup then installs that file
  into the version directory with --isolate, reading a copy of the metadata
  whose entry for it points at the verified download, so it neither fetches
  the bindist again nor picks another one for this distribution. GHC and HLS
  bindists configure their install prefix into their wrapper scripts, which
  is why ghcup installs into the version directory itself."
  (:require [babashka.fs :as fs]
            [breto.proc :as proc]
            [clojure.string :as str])
  (:import (java.util.regex Matcher Pattern)))

(def metadata-url "https://raw.githubusercontent.com/haskell/ghcup-metadata/master/ghcup-0.1.0.yaml")

(def tools
  "The ghcup name of each tool and its key in the metadata."
  {"ghc" "GHC" "cabal" "Cabal" "hls" "HLS" "stack" "Stack"})

(defn arch
  "The metadata's name of the architecture breto runs on, or nil."
  []
  (case (System/getProperty "os.arch")
    ("amd64" "x86_64") "A_64"
    "aarch64" "A_ARM64"
    nil))

(defn platform-arch
  "The metadata's name of the architecture of a breto platform such as
  linux-aarch64, or nil."
  [platform]
  (case (second (str/split (str platform) #"-" 2))
    "x86_64" "A_64"
    "aarch64" "A_ARM64"
    nil))

(def ^:private platforms {"A_64" "x86_64-unknown-linux" "A_ARM64" "aarch64-unknown-linux"})

(defn download-path
  "The path of the generic Linux download of `tool` for `arch` in the
  metadata, with the version left as a placeholder."
  [tool arch]
  ["ghcupDownloads" (tools tool) "toolVersions" "{version}" "viArch" (or arch "unsupported")
   "Linux_UnknownLinux" "unknown_versioning"])

(defn layout
  "Where the commands of `tool` are in an isolated install."
  [tool]
  (case tool
    ("ghc" "hls") {:bins {} :bin-dir "bin"}
    {:bins {tool tool}}))

(defn versions
  "The versions of `tool` in the parsed metadata `doc` that have a generic
  Linux download for `arch` and are not tagged as a prerelease."
  [doc tool arch]
  (for [[version info] (get-in doc ["ghcupDownloads" (tools tool) "toolVersions"])
        :when (get-in info ["viArch" arch "Linux_UnknownLinux" "unknown_versioning" "dlUri"])
        :when (not-any? #{"Prerelease" "LatestPrerelease"} (get info "viTags"))]
    (str version)))

(defn pinned-metadata
  "The metadata `text` with every download at `url` pointing at `file`
  instead, or nil when it lists no download at `url`."
  [text url file]
  (let [pattern (re-pattern (str "(?m)^(\\s*dlUri:\\s*)([\"']?)" (Pattern/quote url) "\\2\\s*$"))]
    (when (re-find pattern text)
      (str/replace text pattern (str "$1" (Matcher/quoteReplacement (str "file://" file)))))))

(defn arguments
  "The ghcup command line installing `version` of `tool` from the metadata
  file `metadata` into `dir`."
  [metadata tool version dir]
  ["--metadata-caching" "0" "--metadata-fetching-mode" "Strict"
   "--url-source" (str "file://" metadata) "--platform" (platforms (arch) "unsupported")
   "install" tool version "--isolate" (str dir)])

(defn environment
  "Directories under `work` for everything ghcup keeps outside the isolated
  install, whether it follows the XDG base directories or not, so neither the
  user's ~/.ghcup nor its configuration takes part."
  [work]
  (let [dir #(str (fs/path work "ghcup" %))]
    {"GHCUP_INSTALL_BASE_PREFIX" (dir "home") "GHCUP_USE_XDG_DIRS" "1"
     "XDG_DATA_HOME" (dir "data") "XDG_CONFIG_HOME" (dir "config") "XDG_CACHE_HOME" (dir "cache")
     "XDG_STATE_HOME" (dir "state") "XDG_BIN_HOME" (dir "bin") "TMPDIR" (dir "tmp")}))

(defn install!
  "Installs the verified bindist `file`, downloaded from `url`, as the
  release of `pkg` into `dest` with `ghcup`, the path of a ghcup command.
  `metadata` is the text of the metadata the download was found in."
  [io pkg release file url metadata dest work ghcup context]
  (when-not ghcup
    (throw (ex-info (str (:name pkg) " is installed with ghcup, and no ghcup is installed; install ghcup first")
                    (assoc context :breto/problem :ghcup-missing))))
  (let [tool (get-in pkg [:source :ghcup])
        bindist (fs/path work "bindist" (fs/file-name (str/replace url #"[?#].*$" "")))
        metadata-file (fs/path work "ghcup-metadata.yaml")
        pinned (or (pinned-metadata metadata url bindist)
                   (throw (ex-info (str "the ghcup metadata no longer lists " url)
                                   (assoc context :breto/problem :no-release :url metadata-url))))
        args (arguments metadata-file tool (:version release) dest)
        run (or (:run-in io) proc/run-in)
        env (environment work)]
    (fs/create-dirs (fs/parent bindist))
    (fs/move file bindist)
    (spit (str metadata-file) pinned)
    (run! #(fs/create-dirs %) (vals (dissoc env "GHCUP_USE_XDG_DIRS")))
    (let [{:keys [exit out err]} (run {:command (cons ghcup args) :env env :dir work})]
      (when-not (zero? exit)
        (throw (ex-info (str "ghcup install " tool " " (:version release) " failed: "
                             (str/trim (str (if (str/blank? err) out err))))
                        (assoc context :breto/problem :ghcup-failed :command (vec (cons "ghcup" args))
                               :output (str/trim (str err out)))))))))
