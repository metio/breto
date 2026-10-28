;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.pypi-test
  (:require [babashka.fs :as fs]
            [breto.lockfile :as lockfile]
            [breto.package :as package]
            [breto.source :as source]
            [breto.store :as store]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- fake-uv
  "A uv command that resolves the input to a requirement naming the cut-off,
  creates a venv as a directory, and installs a script named after the
  requirement, logging its arguments and environment."
  []
  (s/file! "uv" (str "#!/bin/sh\n"
                     "echo \"$* | $UV_NO_CONFIG $UV_PYTHON_PREFERENCE $UV_PYTHON_DOWNLOADS $UV_PYTHON_INSTALL_DIR $UV_PYTHON_INSTALL_BIN\" >> " (path "uv.log") "\n"
                     "command=\"$1 $2\"\n"
                     "while [ $# -gt 0 ]; do\n"
                     "  case \"$1\" in\n"
                     "    --output-file) out=$2; shift ;;\n"
                     "    --exclude-newer) cutoff=$2; shift ;;\n"
                     "    --python) python=$2; shift ;;\n"
                     "    --python-version) shift ;;\n"
                     "    --requirement) requirement=$2; shift ;;\n"
                     "    -*) ;;\n"
                     "    *) last=$1 ;;\n"
                     "  esac\n"
                     "  shift\n"
                     "done\n"
                     "case \"$command\" in\n"
                     "  'pip compile') printf '%s --hash=sha256:%s\\n' \"$(cat \"$last\")\" \"$cutoff\" > \"$out\" ;;\n"
                     "  venv*) mkdir -p \"$last/bin\" ;;\n"
                     "  'pip install') name=$(sed 's/==.*//' \"$requirement\"); printf '#!/bin/sh\\necho %s\\n' \"$name\" > \"$python/bin/$name\"; chmod +x \"$python/bin/$name\" ;;\n"
                     "esac\n")))

(defn- world []
  (s/define! "uv" {:source {:github "astral-sh/uv"} :asset "uv" :verify false})
  (s/define! "reuse" {:source {:pypi "reuse"}})
  (s/define! "yamllint" {:source {:pypi "yamllint"} :python "3.12"})
  (s/fake-io {"https://github.com/astral-sh/uv/releases/download/v1.0.0/uv" (fake-uv)}
             {"https://github.com/astral-sh/uv/releases/latest" "{\"tag_name\":\"v1.0.0\"}"
              "https://pypi.org/pypi/reuse/json" "{\"info\":{\"version\":\"6.2.0\"}}"
              "https://pypi.org/pypi/yamllint/json" "{\"info\":{\"version\":\"1.38.0\"}}"}
             (atom [])))

(defn- log-lines [] (str/split-lines (slurp (path "uv.log"))))

(deftest a-pypi-package-is-installed-into-a-venv-of-its-own
  (let [io (world)
        {:keys [code out]} (s/breto! {:python-dir (path "python")} ["install" "reuse" "uv"] {:io io})]
    (is (= 0 code) out)
    (testing "uv is installed before what it installs"
      (is (< (str/index-of out "uv 1.0.0") (str/index-of out "reuse 6.2.0"))))
    (is (= "#!/bin/sh\necho reuse\n" (slurp (path "bin" "reuse"))))
    (is (fs/regular-file? (path "store" "reuse" "6.2.0" "venv" "bin" "reuse")))
    (let [meta (store/read-meta {:store (path "store")} "reuse" "6.2.0")]
      (is (re-matches #"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ" (:exclude-newer meta)))
      (is (= (s/sha256 (str (fs/path (s/file! "expected" (str "reuse==6.2.0 --hash=sha256:" (:exclude-newer meta) "\n")))))
             (:sha256 meta))))
    (testing "uv ignores the user's configuration and Pythons, and links none"
      (is (every? #(str/ends-with? % (str "| 1 only-managed automatic " (path "python") " 0")) (log-lines))))
    (testing "the venv is relocatable and built for the default Python"
      (is (some #(str/starts-with? % "venv --quiet --relocatable --python 3.13 ") (log-lines)))
      (is (some #(str/includes? % "--require-hashes --no-deps") (log-lines))))))

(deftest a-definition-chooses-its-python
  (let [io (world)]
    (is (= 0 (:code (s/breto! {} ["install" "uv" "yamllint"] {:io io}))))
    (is (some #(and (str/includes? % "pip compile") (str/includes? % "--python-version 3.12")) (log-lines)))
    (is (some #(str/starts-with? % "venv --quiet --relocatable --python 3.12 ") (log-lines)))))

(deftest a-pypi-package-needs-uv
  (let [io (world)
        {:keys [code out]} (s/breto! {} ["install" "reuse"] {:io io})]
    (is (= 1 code))
    (is (str/includes? out "no uv is installed; install uv first"))))

(deftest a-lock-reproduces-the-resolution-elsewhere
  (let [io (world)]
    (fs/create-dirs (path "shared"))
    (spit (path "shared" "@python.edn") (pr-str {:members ["reuse@6.2.0"]}))
    (let [cfg {:registries [(path "packages") (path "shared")]}]
      (is (= 0 (:code (s/breto! cfg ["install" "uv"] {:io io}))))
      (is (= 0 (:code (binding [*err* (java.io.StringWriter.)] (s/breto! cfg ["lock" (path "shared")] {:io io})))))
      (let [entry (get-in (lockfile/read-lock (path "shared" "breto.lock")) [:entries "reuse@6.2.0"])]
        (is (:sha256 entry))
        (is (:exclude-newer entry))
        (testing "a fresh store resolves with the locked cut-off and matches"
          (let [fresh (assoc cfg :store (path "store2") :bin-dir (path "bin2"))]
            (is (= 0 (:code (s/breto! fresh ["install" "uv" "reuse@6.2.0"] {:io io}))))
            (is (= (:sha256 entry) (:sha256 (store/read-meta {:store (path "store2")} "reuse" "6.2.0"))))
            (is (some #(str/includes? % (str "--exclude-newer " (:exclude-newer entry))) (log-lines)))))
        (testing "a resolution that differs from the lock fails"
          (spit (path "shared" "breto.lock")
                (pr-str {"reuse@6.2.0" (assoc entry :sha256 (apply str (repeat 64 "0")))}))
          (let [fresh (assoc cfg :store (path "store3") :bin-dir (path "bin3"))
                {:keys [code out]} (s/breto! fresh ["install" "uv" "reuse@6.2.0"] {:io io})]
            (is (= 1 code))
            (is (str/includes? out "[FAIL] reuse"))
            (is (not (fs/exists? (path "bin3" "reuse"))))))))))

(deftest pypi-releases-come-from-the-json-api
  (let [io (s/fake-io {} {"https://pypi.org/pypi/reuse/json"
                          (str "{\"info\":{\"version\":\"6.2.0\"},\"releases\":{"
                               "\"6.1.0\":[{\"yanked\":false}],\"6.2.0\":[{\"yanked\":false}],"
                               "\"6.3.0rc1\":[{\"yanked\":false}],\"6.0.0\":[{\"yanked\":true}],\"5.0.0\":[]}}")}
                      (atom []))
        pkg (package/normalize {:name "reuse" :source {:pypi "reuse"} :skip ["6.2.0"]})]
    (is (= {:tag "6.2.0" :version "6.2.0"} (select-keys (source/latest pkg io) [:tag :version])))
    (testing "skipping the latest falls back to the newest published final release"
      (is (= "6.1.0" (:version (source/newest pkg io)))))))

(deftest text-sources-can-match-the-tag
  (let [io (s/fake-io {} {"https://static.rust-lang.org/rustup/release-stable.toml" "schema-version = '1'\nversion = '1.29.2'\n"} (atom []))
        pkg (package/normalize {:name "rustup" :tag-prefix "" :asset "rustup-init" :url "https://example.org/{version}/{asset}"
                                :source {:text "https://static.rust-lang.org/rustup/release-stable.toml"
                                         :match "(?m)^version = '([^']+)'"}})]
    (is (= "1.29.2" (:version (source/latest pkg io))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is a regular expression for a :text source"
                          (package/normalize {:name "x" :source {:github "a/b" :match "x"} :asset "x"})))))

(deftest the-rustup-definition-links-every-proxy-to-one-binary
  (let [pkg (package/normalize (assoc (read-string (slurp "packages/rustup.edn")) :name "rustup"))
        paths (package/bin-paths pkg (package/artifact pkg {:tag "1.29.2" :version "1.29.2"}))]
    (is (= #{"rustup" "rustc" "cargo" "rustfmt" "rust-analyzer" "cargo-clippy" "clippy-driver"}
           (set (filter #{"rustup" "rustc" "cargo" "rustfmt" "rust-analyzer" "cargo-clippy" "clippy-driver"} (keys paths)))))
    (is (= 1 (count (distinct (vals paths)))))))
