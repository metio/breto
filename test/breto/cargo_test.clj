;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.cargo-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.cargo :as cargo]
            [breto.package :as package]
            [breto.source :as source]
            [breto.store :as store]
            [breto.support :as s :refer [path]]
            [breto.version :as version]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- fake-rustup
  "A rustup binary that, called as cargo, installs a script named after the
  `bin` file of the crate into the root's bin directory, logging the name it
  was called by and its arguments. Called by any other name it fails, as the
  installer would."
  []
  (s/file! "rustup-init" (str "#!/bin/sh\n"
                              "echo \"$(basename \"$0\") $*\" >> " (path "cargo.log") "\n"
                              "[ \"$(basename \"$0\")\" = cargo ] || exit 3\n"
                              "while [ $# -gt 0 ]; do\n"
                              "  case \"$1\" in\n"
                              "    --path) source=$2; shift ;;\n"
                              "    --root) root=$2; shift ;;\n"
                              "  esac\n"
                              "  shift\n"
                              "done\n"
                              "[ -f \"$source/Cargo.lock\" ] || exit 4\n"
                              "bin=$(cat \"$source/bin\")\n"
                              "mkdir -p \"$root/bin\"\n"
                              "printf '#!/bin/sh\\necho %s\\n' \"$bin\" > \"$root/bin/$bin\"\n"
                              "chmod +x \"$root/bin/$bin\"\n")))

(defn- crate!
  "A .crate file of `name` at `version` whose binary is `bin`."
  [name version bin]
  (let [dir (path "crates" (str name "-" version))
        file (str (path (str name "-" version ".crate")))]
    (fs/create-dirs dir)
    (spit (str (fs/path dir "Cargo.toml")) (str "[package]\nname = \"" name "\"\n"))
    (spit (str (fs/path dir "Cargo.lock")) "version = 4\n")
    (spit (str (fs/path dir "bin")) bin)
    (process/shell "tar" "--create" "--gzip" "--file" file "--directory" (str (path "crates")) (str name "-" version))
    (fs/path file)))

(defn- index [& entries]
  (str/join "\n" (map json/generate-string entries)))

(defn- world [cksum]
  (let [crate (crate! "ripgrep" "15.2.0" "rg")]
    (s/define! "rustup" {:source {:github "rust-lang/rustup"} :asset "rustup-init"
                         :bins {"rustup" "rustup-init" "cargo" "rustup-init"} :verify false})
    (s/define! "ripgrep" {:source {:cargo "ripgrep"} :bins {"rg" "bin/rg"}})
    (s/fake-io {"https://github.com/rust-lang/rustup/releases/download/v1.0.0/rustup-init" (fake-rustup)
                "https://static.crates.io/crates/ripgrep/ripgrep-15.2.0.crate" crate}
               {"https://github.com/rust-lang/rustup/releases/latest" "{\"tag_name\":\"v1.0.0\"}"
                "https://index.crates.io/ri/pg/ripgrep"
                (index {:name "ripgrep" :vers "15.1.0" :cksum (apply str (repeat 64 "1")) :yanked false}
                       {:name "ripgrep" :vers "15.2.0" :cksum (or cksum (s/sha256 crate)) :yanked false}
                       {:name "ripgrep" :vers "16.0.0-rc.1" :cksum (apply str (repeat 64 "2")) :yanked false}
                       {:name "ripgrep" :vers "16.0.0" :cksum (apply str (repeat 64 "3")) :yanked true})}
               (atom []))))

(defn- log-lines [] (str/split-lines (slurp (path "cargo.log"))))

(deftest a-crate-is-verified-and-built-with-cargo
  (let [io (world nil)
        {:keys [code out]} (s/breto! {} ["install" "ripgrep" "rustup"] {:io io})]
    (is (= 0 code) out)
    (testing "rustup is installed before what its cargo builds"
      (is (< (str/index-of out "rustup 1.0.0") (str/index-of out "ripgrep 15.2.0"))))
    (is (= "#!/bin/sh\necho rg\n" (slurp (path "bin" "rg"))))
    (testing "only the binary is kept, under the package name"
      (is (= ["ripgrep"] (map (comp str fs/file-name) (fs/list-dir (path "store" "ripgrep" "15.2.0"))))))
    (let [[line] (log-lines)]
      (is (str/starts-with? line "cargo install --path "))
      (is (str/includes? line " --locked ")))
    (let [meta (store/read-meta {:store (path "store")} "ripgrep" "15.2.0")]
      (is (:verified meta))
      (is (= (s/sha256 (path "ripgrep-15.2.0.crate")) (:sha256 meta))))))

(deftest a-crate-that-does-not-match-the-index-is-not-built
  (let [io (world (apply str (repeat 64 "0")))
        {:keys [code out]} (s/breto! {} ["install" "rustup" "ripgrep"] {:io io})]
    (is (= 1 code))
    (is (str/includes? out "[FAIL] ripgrep"))
    (is (not (fs/exists? (path "cargo.log"))))
    (is (not (fs/exists? (path "bin" "rg"))))))

(deftest a-crate-needs-rustup
  (let [io (world nil)
        {:keys [code out]} (s/breto! {} ["install" "ripgrep"] {:io io})]
    (is (= 1 code))
    (is (str/includes? out "no rustup is installed; install rustup first"))))

(deftest cargo-releases-come-from-the-sparse-index
  (let [io (world nil)
        pkg (s/normalize {:name "ripgrep" :source {:cargo "ripgrep"}})]
    (testing "the newest version that is neither yanked nor a pre-release"
      (is (= {:tag "15.2.0" :version "15.2.0"} (select-keys (source/latest pkg io) [:tag :version]))))
    (testing "skipping it falls back to the one before"
      (is (= "15.1.0" (:version (source/newest (assoc pkg :skip ["15.2.0"]) io)))))))

(deftest the-index-file-follows-the-name-length
  (is (= "https://index.crates.io/1/a" (cargo/index-url "a")))
  (is (= "https://index.crates.io/2/ab" (cargo/index-url "ab")))
  (is (= "https://index.crates.io/3/b/bat" (cargo/index-url "bat")))
  (is (= "https://index.crates.io/ri/pg/ripgrep" (cargo/index-url "ripgrep")))
  (is (= "https://index.crates.io/se/rd/serde_json" (cargo/index-url "Serde_JSON"))))

(deftest cargo-definitions-fill-in-the-crate
  (let [pkg (s/normalize {:name "bat" :source {:cargo "bat"}})
        artifact (package/artifact pkg {:tag "0.26.1" :version "0.26.1"})]
    (is (= {"bat" "bin/bat"} (:bins pkg)))
    (is (= "bat-0.26.1.crate" (:asset artifact)))
    (is (= :tar (:archive artifact)))
    (is (= "https://static.crates.io/crates/bat/bat-0.26.1.crate" (:url artifact)))
    (is (= "https://index.crates.io/3/b/bat" (:checksum-url artifact)))
    (is (= [{"vers" "0.26.1"} "cksum"] (:checksum-path artifact))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names a crate on crates.io"
                        (s/normalize {:name "x" :source {:cargo "../x"}}))))

(def ^:private sha "0123456789abcdef0123456789abcdef01234567")

(defn- git-world []
  (let [archive (crate! "himalaya-tui" sha "himalaya-tui")]
    (s/define! "rustup" {:source {:github "rust-lang/rustup"} :asset "rustup-init"
                         :bins {"rustup" "rustup-init" "cargo" "rustup-init"} :verify false})
    (s/define! "himalaya-tui" {:source {:cargo "himalaya-tui" :git "pimalaya/himalaya-tui"}})
    (s/fake-io {"https://github.com/rust-lang/rustup/releases/download/v1.0.0/rustup-init" (fake-rustup)
                (str "https://github.com/pimalaya/himalaya-tui/archive/" sha ".tar.gz") archive}
               {"https://github.com/rust-lang/rustup/releases/latest" "{\"tag_name\":\"v1.0.0\"}"
                "https://api.github.com/repos/pimalaya/himalaya-tui/commits/HEAD"
                (json/generate-string {:sha sha :commit {:committer {:date "2026-10-27T09:05:03Z"}}})}
               (atom []))))

(deftest a-git-crate-is-built-from-the-newest-commit
  (let [io (git-world)
        version (str "2026.10.27.090503+" sha)
        {:keys [code out]} (s/breto! {} ["install" "rustup" "himalaya-tui"] {:io io})]
    (is (= 0 code) out)
    (is (= "#!/bin/sh\necho himalaya-tui\n" (slurp (path "bin" "himalaya-tui"))))
    (is (str/starts-with? (first (log-lines)) "cargo install --path "))
    (testing "the lock-worthy hash is that of the commit's archive"
      (is (= (s/sha256 (path (str "himalaya-tui-" sha ".crate")))
             (:sha256 (store/read-meta {:store (path "store")} "himalaya-tui" version)))))))

(deftest git-crate-definitions-fill-in-the-commit-archive
  (let [pkg (s/normalize {:name "himalaya-tui" :source {:cargo "himalaya-tui" :git "pimalaya/himalaya-tui"}})
        artifact (package/artifact pkg {:tag (str "2026.10.27.090503+" sha) :version (str "2026.10.27.090503+" sha)})]
    (is (= {"himalaya-tui" "bin/himalaya-tui"} (:bins pkg)))
    (is (false? (:verify pkg)))
    (is (= (str "himalaya-tui-" sha ".tar.gz") (:asset artifact)))
    (is (= (str "https://github.com/pimalaya/himalaya-tui/archive/" sha ".tar.gz") (:url artifact)))
    (is (nil? (:checksum-url artifact))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"OWNER/REPO"
                        (s/normalize {:name "x" :source {:cargo "x" :git "not a repo"}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"OWNER/REPO"
                        (s/normalize {:name "x" :source {:pypi "x" :git "o/x"}}))))

(deftest git-versions-order-by-commit-date
  (is (= (str "2026.1.2.030405+" sha) (cargo/git-version sha "2026-01-02T03:04:05Z")))
  (is (thrown? clojure.lang.ExceptionInfo (cargo/git-version "main" "2026-01-02T03:04:05Z")))
  (is (version/older? (cargo/git-version sha "2026-01-02T23:59:59Z")
                      (cargo/git-version (apply str (repeat 40 "0")) "2026-01-10T00:00:00Z"))))
