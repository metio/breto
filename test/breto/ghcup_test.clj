;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.ghcup-test
  (:require [babashka.fs :as fs]
            [breto.ghcup :as ghcup]
            [breto.package :as package]
            [breto.sealed :as sealed]
            [breto.source :as source]
            [breto.store :as store]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- fake-ghcup
  "A ghcup command that logs its arguments and base directory, then, given a
  metadata file whose download is a local file, installs the tool into the
  isolated directory: GHC as bin/ghc-VERSION with a ghc link, any other tool
  as a single binary. While the file ghcup-fails exists, it fails halfway."
  []
  (s/file! "ghcup" (str "#!/bin/sh\n"
                        "echo \"$* | $GHCUP_INSTALL_BASE_PREFIX\" >> " (path "ghcup.log") "\n"
                        "while [ $# -gt 0 ]; do\n"
                        "  case \"$1\" in\n"
                        "    --url-source) metadata=${2#file://}; shift ;;\n"
                        "    --isolate) dir=$2; shift ;;\n"
                        "    --platform|--metadata-caching|--metadata-fetching-mode) shift ;;\n"
                        "    install) tool=$2; version=$3; shift 2 ;;\n"
                        "  esac\n"
                        "  shift\n"
                        "done\n"
                        "bindist=$(sed -n 's|^ *dlUri: file://||p' \"$metadata\")\n"
                        "[ -f \"$bindist\" ] || { echo \"no local bindist in $metadata\" >&2; exit 5; }\n"
                        "if [ -e " (path "ghcup-fails") " ]; then mkdir -p \"$dir/bin\"; echo broken >&2; exit 6; fi\n"
                        "mkdir -p \"$dir/bin\"\n"
                        "if [ \"$tool\" = ghc ]; then\n"
                        "  printf '#!/bin/sh\\necho ghc %s\\n' \"$version\" > \"$dir/bin/ghc-$version\"\n"
                        "  chmod +x \"$dir/bin/ghc-$version\"\n"
                        "  ln -s \"ghc-$version\" \"$dir/bin/ghc\"\n"
                        "else\n"
                        "  printf '#!/bin/sh\\necho %s\\n' \"$tool\" > \"$dir/$tool\"\n"
                        "  chmod +x \"$dir/$tool\"\n"
                        "fi\n")))

(defn- entry [url hash]
  (str "              unknown_versioning:\n"
       "                dlUri: " url "\n"
       "                dlHash: " hash "\n"))

(defn- metadata
  "ghcup metadata listing GHC 9.12.2 and 9.14.1 for this architecture, a
  prerelease, and a version without a generic Linux download."
  [ghc-hash]
  (let [arch (ghcup/arch)]
    (str "ghcupDownloads:\n"
         "  GHC:\n"
         "    toolVersions:\n"
         "      9.12.2:\n"
         "        viArch:\n"
         "          " arch ":\n"
         "            Linux_UnknownLinux:\n"
         (entry "https://downloads.haskell.org/~ghc/9.12.2/ghc-9.12.2-x86_64-linux.tar.xz" (apply str (repeat 64 "1")))
         "      9.14.1:\n"
         "        viTags:\n"
         "        - Latest\n"
         "        viArch:\n"
         "          " arch ":\n"
         "            Linux_UnknownLinux:\n"
         (entry "https://downloads.haskell.org/~ghc/9.14.1/ghc-9.14.1-x86_64-linux.tar.xz" ghc-hash)
         "            Linux_Fedora:\n"
         (entry "https://downloads.haskell.org/~ghc/9.14.1/ghc-9.14.1-x86_64-fedora.tar.xz" (apply str (repeat 64 "2")))
         "      9.16.0.20261001:\n"
         "        viTags:\n"
         "        - Prerelease\n"
         "        viArch:\n"
         "          " arch ":\n"
         "            Linux_UnknownLinux:\n"
         (entry "https://downloads.haskell.org/~ghc/9.16.0/ghc-9.16.0-x86_64-linux.tar.xz" (apply str (repeat 64 "3")))
         "      9.18.1:\n"
         "        viArch:\n"
         "          " arch ":\n"
         "            Linux_Fedora:\n"
         (entry "https://downloads.haskell.org/~ghc/9.18.1/ghc-9.18.1-x86_64-fedora.tar.xz" (apply str (repeat 64 "4")))
         "  Cabal:\n"
         "    toolVersions:\n"
         "      3.16.1.0:\n"
         "        viArch:\n"
         "          " arch ":\n"
         "            Linux_UnknownLinux:\n"
         (entry "https://downloads.haskell.org/~ghcup/cabal-3.16.1.0.tar.xz" (apply str (repeat 64 "5"))))))

(defn- world
  ([] (world nil))
  ([ghc-hash]
   (let [bindist (s/file! "ghc-9.14.1-x86_64-linux.tar.xz" "a GHC bindist")]
     (s/define! "ghcup" {:source {:github "haskell/ghcup-hs"} :asset "ghcup" :verify false})
     (s/define! "ghc" {:source {:ghcup "ghc"}})
     (s/fake-io {"https://github.com/haskell/ghcup-hs/releases/download/v1.0.0/ghcup" (fake-ghcup)
                 "https://downloads.haskell.org/~ghc/9.14.1/ghc-9.14.1-x86_64-linux.tar.xz" bindist}
                {"https://github.com/haskell/ghcup-hs/releases/latest" "{\"tag_name\":\"v1.0.0\"}"
                 ghcup/metadata-url (metadata (or ghc-hash (s/sha256 bindist)))}
                (atom [])))))

(defn- log-lines [] (str/split-lines (slurp (path "ghcup.log"))))

(deftest ghcup-installs-the-verified-generic-bindist-into-the-version-directory
  (let [io (world)
        {:keys [code out]} (s/breto! {} ["install" "ghc" "ghcup"] {:io io})
        dir (store/version-dir {:store (path "store")} "ghc" "9.14.1")]
    (is (= 0 code) out)
    (testing "ghcup is installed before what it installs"
      (is (< (str/index-of out "ghcup 1.0.0") (str/index-of out "ghc 9.14.1"))))
    (testing "every command in bin is linked"
      (is (= "#!/bin/sh\necho ghc 9.14.1\n" (slurp (path "bin" "ghc"))))
      (is (fs/exists? (path "bin" "ghc-9.14.1"))))
    (let [[line] (log-lines)]
      (is (str/includes? line (str " install ghc 9.14.1 --isolate " dir " ")))
      (is (re-find #"--url-source file://\S+/ghcup-metadata.yaml" line))
      (is (re-find #"--platform \S+-unknown-linux" line))
      (testing "ghcup keeps its own files in the work directory"
        (is (re-find #"\| \S+/\.work-[^/]+/ghcup/home$" line))))
    (is (sealed/sealed? dir))
    (let [meta (store/read-meta {:store (path "store")} "ghc" "9.14.1")]
      (is (:verified meta))
      (is (= (s/sha256 (path "files" "ghc-9.14.1-x86_64-linux.tar.xz")) (:sha256 meta))))))

(deftest a-bindist-that-does-not-match-the-metadata-is-not-installed
  (let [io (world (apply str (repeat 64 "0")))
        {:keys [code out]} (s/breto! {} ["install" "ghcup" "ghc"] {:io io})]
    (is (= 1 code))
    (is (str/includes? out "[FAIL] ghc"))
    (is (not (fs/exists? (path "ghcup.log"))))
    (is (not (fs/exists? (path "store" "ghc" "9.14.1"))))))

(deftest ghcup-tools-need-ghcup
  (let [io (world)
        {:keys [code out]} (s/breto! {} ["install" "ghc"] {:io io})]
    (is (= 1 code))
    (is (str/includes? out "no ghcup is installed; install ghcup first"))))

(deftest a-failed-ghcup-install-leaves-no-version-behind
  (let [io (world)]
    (s/define! "ghc" {:source {:ghcup "ghc"} :bins {"ghc" "bin/ghc"}})
    (spit (path "ghcup-fails") "")
    (let [{:keys [code out]} (s/breto! {} ["install" "ghcup" "ghc"] {:io io})]
      (is (= 1 code))
      (is (str/includes? out "ghcup install ghc 9.14.1 failed: broken"))
      (is (not (fs/exists? (path "store" "ghc" "9.14.1")))))))

(deftest ghcup-releases-are-the-generic-final-ones-of-the-metadata
  (let [io (s/fake-io {} {ghcup/metadata-url (metadata (apply str (repeat 64 "9")))} (atom []))
        pkg (s/normalize {:name "ghc" :source {:ghcup "ghc"}})]
    (is (= "9.14.1" (:version (source/latest pkg io))))
    (testing "skipping it falls back to the one before"
      (is (= "9.12.2" (:version (source/newest (assoc pkg :skip ["9.14.1"]) io)))))))

(deftest ghcup-definitions-read-the-download-from-the-metadata
  (let [io (s/fake-io {} {ghcup/metadata-url (metadata (apply str (repeat 64 "9")))} (atom []))
        ghc (s/normalize {:name "ghc" :source {:ghcup "ghc"}})
        cabal (s/normalize {:name "cabal" :source {:ghcup "cabal"}})
        release (source/fetch-vars ghc io {:tag "9.14.1" :version "9.14.1"})
        artifact (package/artifact ghc release)]
    (is (= "https://downloads.haskell.org/~ghc/9.14.1/ghc-9.14.1-x86_64-linux.tar.xz" (:url artifact)))
    (is (= ghcup/metadata-url (:checksum-url artifact)))
    (is (= "bin" (:bin-dir ghc)))
    (is (= {"cabal" "cabal"} (:bins cabal))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names a tool ghcup installs"
                        (s/normalize {:name "x" :source {:ghcup "agda"}}))))

(deftest pinned-metadata-points-only-the-exact-download-at-the-file
  (let [url "https://example.org/ghc-1.tar.xz"
        text (str "a:\n  dlUri: " url "\n"
                  "b:\n  dlUri: \"" url "\"\n"
                  "c:\n  dlUri: " url ".sig\n")]
    (is (= (str "a:\n  dlUri: file:///w/ghc-1.tar.xz\n"
                "b:\n  dlUri: file:///w/ghc-1.tar.xz\n"
                "c:\n  dlUri: " url ".sig\n")
           (ghcup/pinned-metadata text url "/w/ghc-1.tar.xz")))
    (is (nil? (ghcup/pinned-metadata text "https://example.org/other.tar.xz" "/w/x")))))
