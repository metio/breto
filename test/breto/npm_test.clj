;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.npm-test
  (:require [babashka.fs :as fs]
            [breto.lockfile :as lockfile]
            [breto.package :as package]
            [breto.source :as source]
            [breto.store :as store]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- fake-npm
  "An npm command that writes a package-lock.json naming the spec and the
  cut-off, and a script linked into node_modules/.bin under the unscoped
  package name, logging its arguments and the first entry of PATH."
  []
  (s/file! "npm" (str "#!/bin/sh\n"
                      "echo \"$* | ${PATH%%:*}\" >> " (path "npm.log") "\n"
                      "while [ $# -gt 0 ]; do\n"
                      "  case \"$1\" in\n"
                      "    --prefix) prefix=$2; shift ;;\n"
                      "    --before) before=$2; shift ;;\n"
                      "    --registry|--userconfig|--globalconfig) shift ;;\n"
                      "    -*) ;;\n"
                      "    *) spec=$1 ;;\n"
                      "  esac\n"
                      "  shift\n"
                      "done\n"
                      "name=${spec%@*}; bin=${name##*/}\n"
                      "printf '%s %s\\n' \"$spec\" \"$before\" > \"$prefix/package-lock.json\"\n"
                      "mkdir -p \"$prefix/node_modules/$name\" \"$prefix/node_modules/.bin\"\n"
                      "printf '#!/bin/sh\\necho %s\\n' \"$name\" > \"$prefix/node_modules/$name/cli.js\"\n"
                      "ln -s \"../$name/cli.js\" \"$prefix/node_modules/.bin/$bin\"\n")))

(defn- world []
  (s/define! "node" {:source {:github "nodejs/node"} :asset "npm" :bins {"npm" "npm"} :verify false})
  (s/define! "prettier" {:source {:npm "prettier"}})
  (s/define! "biome" {:source {:npm "@biomejs/biome"}})
  (s/fake-io {"https://github.com/nodejs/node/releases/download/v1.0.0/npm" (fake-npm)
              "https://github.com/nodejs/node/releases/download/v0.9.0/npm" (fake-npm)}
             {"https://github.com/nodejs/node/releases/latest" "{\"tag_name\":\"v1.0.0\"}"
              "https://registry.npmjs.org/prettier/latest" "{\"version\":\"3.9.10\"}"
              "https://registry.npmjs.org/@biomejs%2Fbiome/latest" "{\"version\":\"2.6.4\"}"}
             (atom [])))

(defn- log-lines [] (str/split-lines (slurp (path "npm.log"))))

(deftest an-npm-package-is-installed-into-a-directory-of-its-own
  (let [io (world)
        {:keys [code out]} (s/breto! {} ["install" "prettier" "node"] {:io io})]
    (is (= 0 code) out)
    (testing "node is installed before what its npm installs"
      (is (< (str/index-of out "node 1.0.0") (str/index-of out "prettier 3.9.10"))))
    (is (= "#!/bin/sh\necho prettier\n" (slurp (path "bin" "prettier"))))
    (is (fs/sym-link? (path "store" "prettier" "3.9.10" "node_modules" ".bin" "prettier")))
    (let [meta (store/read-meta {:store (path "store")} "prettier" "3.9.10")]
      (is (re-matches #"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ" (:exclude-newer meta)))
      (is (= (s/sha256 (str (fs/path (s/file! "expected" (str "prettier@3.9.10 " (:exclude-newer meta) "\n")))))
             (:sha256 meta))))
    (testing "npm ignores the user's configuration, runs no scripts and resolves before the cut-off"
      (let [[line] (log-lines)]
        (is (str/includes? line "--registry https://registry.npmjs.org/"))
        (is (re-find #"--userconfig (\S+)/npmrc/userrc --globalconfig \1/npmrc/globalrc" line))
        (is (str/includes? line "--ignore-scripts"))
        (is (re-find #"--cache \S+/npm-cache " line) "a cache of its own, not the user's")
        (is (re-find #"--before \d{4}-\d\d-\d\dT" line))))
    (testing "the directory of npm, which holds node, comes first on PATH"
      (is (str/ends-with? (first (log-lines)) (str "| " (fs/parent (fs/real-path (path "bin" "npm")))))))))

(deftest npm-builds-under-the-real-path-of-a-symlinked-store
  (let [io (world)]
    (fs/create-dirs (path "real"))
    (fs/create-sym-link (path "linked") (path "real"))
    (let [cfg {:store (str (fs/path (path "linked") "store"))}]
      (is (= 0 (:code (s/breto! cfg ["install" "node" "prettier"] {:io io}))))
      (let [[line] (log-lines)
            [_ prefix] (re-find #"--prefix (\S+)" line)]
        (is (str/starts-with? prefix (str (fs/real-path (path "real")))))
        (is (not (str/includes? (subs line 0 (str/index-of line " | ")) (str (path "linked"))))
            "no argument of npm goes through the symlink")))))

(deftest scoped-packages-link-their-unscoped-command
  (let [io (world)]
    (s/define! "biome" {:source {:npm "@biomejs/biome"} :bins {"biome" "node_modules/.bin/biome"}})
    (is (= 0 (:code (s/breto! {} ["install" "node" "biome"] {:io io}))))
    (is (str/includes? (first (log-lines)) " @biomejs/biome@2.6.4 "))
    (is (= "#!/bin/sh\necho @biomejs/biome\n" (slurp (path "bin" "biome"))))))

(deftest an-npm-package-needs-node
  (let [io (world)
        {:keys [code out]} (s/breto! {} ["install" "prettier"] {:io io})]
    (is (= 1 code))
    (is (str/includes? out "no npm is installed; install node first"))))

(deftest a-lock-reproduces-the-npm-resolution-elsewhere
  (let [io (world)]
    (fs/create-dirs (path "shared"))
    (spit (path "shared" "@web.edn") (pr-str {:members ["prettier@3.9.10"]}))
    (let [cfg {:registries [(path "packages") (path "shared")]}]
      (is (= 0 (:code (s/breto! cfg ["install" "node"] {:io io}))))
      (is (= 0 (:code (binding [*err* (java.io.StringWriter.)] (s/breto! cfg ["lock" (path "shared")] {:io io})))))
      (let [entry (get-in (lockfile/read-lock (path "shared" "breto.lock")) [:entries "prettier@3.9.10"])]
        (is (:sha256 entry))
        (is (:exclude-newer entry))
        (testing "a fresh store resolves with the locked cut-off and matches"
          (let [fresh (assoc cfg :store (path "store2") :bin-dir (path "bin2"))]
            (is (= 0 (:code (s/breto! fresh ["install" "node" "prettier@3.9.10"] {:io io}))))
            (is (= (:sha256 entry) (:sha256 (store/read-meta {:store (path "store2")} "prettier" "3.9.10"))))
            (is (some #(str/includes? % (str "--before " (:exclude-newer entry))) (log-lines)))))
        (testing "a resolution that differs from the lock fails"
          (spit (path "shared" "breto.lock")
                (pr-str {"prettier@3.9.10" (assoc entry :sha256 (apply str (repeat 64 "0")))}))
          (let [fresh (assoc cfg :store (path "store3") :bin-dir (path "bin3"))
                {:keys [code out]} (s/breto! fresh ["install" "node" "prettier@3.9.10"] {:io io})]
            (is (= 1 code))
            (is (str/includes? out "[FAIL] prettier"))
            (is (not (fs/exists? (path "bin3" "prettier"))))
            (testing "keeps the resolution it got for a comparison"
              (let [kept (path "state" "breto" "resolutions" "prettier@3.9.10" "package-lock.json")]
                (is (fs/exists? kept))
                (is (str/includes? out (str "Kept     " kept)))))))))))

(deftest a-build-uses-the-node-its-specs-pin
  (let [io (world)]
    (is (= 0 (:code (s/breto! {} ["install" "node@0.9.0"] {:io io}))))
    (is (= 0 (:code (s/breto! {} ["install" "node"] {:io io}))))
    (is (= "1.0.0" (store/current-version {:store (path "store")} "node")))
    (fs/create-dirs (path "app"))
    (spit (path "app" ".breto.edn") (pr-str {:packages ["node@0.9.0" "prettier@3.9.10"]}))
    (is (= 0 (:code (binding [*err* (java.io.StringWriter.)]
                      (s/breto! {:env-dir (path "envs")} ["env" "--dir" (path "app")] {:io io})))))
    (is (str/ends-with? (first (log-lines)) (str "| " (path "store" "node" "0.9.0")))
        "the node of the project, not the active one")))

(deftest npm-releases-come-from-the-registry
  (let [io (s/fake-io {} {"https://registry.npmjs.org/@biomejs%2Fbiome/latest" "{\"version\":\"2.6.4\"}"
                          "https://registry.npmjs.org/@biomejs%2Fbiome"
                          (str "{\"versions\":{\"2.5.0\":{},\"2.6.0\":{\"deprecated\":\"broken\"},"
                               "\"2.6.3\":{},\"2.6.4\":{},\"3.0.0-beta.1\":{}}}")}
                      (atom []))
        pkg (s/normalize {:name "biome" :source {:npm "@biomejs/biome"} :skip ["2.6.4"]})]
    (is (= {:tag "2.6.4" :version "2.6.4"} (select-keys (source/latest pkg io) [:tag :version])))
    (testing "skipping the latest falls back to the newest final release that is not deprecated"
      (is (= "2.6.3" (:version (source/newest pkg io)))))))

(deftest npm-definitions-default-to-the-command-of-the-package-name
  (let [pkg (s/normalize {:name "prettier" :source {:npm "prettier"}})]
    (is (= :tree (:layout pkg)))
    (is (= {"prettier" "node_modules/.bin/prettier"} (:bins pkg)))
    (is (= "https://registry.npmjs.org/prettier/3.9.10"
           (:url (package/artifact pkg {:tag "3.9.10" :version "3.9.10"}))))))

(deftest npm-names-are-checked
  (doseq [name ["Prettier" "@scope" "@/x" "a b" "../x"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names a package on the npm registry"
                          (s/normalize {:name "x" :source {:npm name}}))
        name)))
