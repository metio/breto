;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.coursier-test
  (:require [babashka.fs :as fs]
            [breto.coursier :as coursier]
            [breto.lockfile :as lockfile]
            [breto.package :as package]
            [breto.source :as source]
            [breto.store :as store]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- fake-cs
  "A cs command that logs its arguments and mirror file. fetch writes two jars
  into the cache, the second with the content of the file jar-content when
  it exists, and prints their paths; bootstrap writes a launcher script to
  its output."
  []
  (s/file! "cs" (str "#!/bin/sh\n"
                     "echo \"$* | $COURSIER_CACHE $COURSIER_MIRRORS\" >> " (path "cs.log") "\n"
                     "command=$1; shift\n"
                     "while [ $# -gt 0 ]; do\n"
                     "  case \"$1\" in\n"
                     "    --cache) cache=$2; shift ;;\n"
                     "    --output) output=$2; shift ;;\n"
                     "    --repository|--checksum|--mode|--main-class|--java-opt) shift ;;\n"
                     "    -*) ;;\n"
                     "    *) spec=$1 ;;\n"
                     "  esac\n"
                     "  shift\n"
                     "done\n"
                     "case $command in\n"
                     "  fetch)\n"
                     "    dir=$cache/https/repo1.maven.org/maven2\n"
                     "    mkdir -p \"$dir\"\n"
                     "    echo \"$spec\" > \"$dir/app.jar\"\n"
                     "    cat " (path "jar-content") " > \"$dir/lib.jar\" 2>/dev/null || echo lib > \"$dir/lib.jar\"\n"
                     "    echo \"$dir/lib.jar\"; echo \"$dir/app.jar\" ;;\n"
                     "  bootstrap)\n"
                     "    printf '#!/bin/sh\\necho %s\\n' \"$spec\" > \"$output\"; chmod +x \"$output\" ;;\n"
                     "esac\n")))

(defn- world []
  (s/define! "coursier" {:source {:github "coursier/coursier"} :asset "cs" :bins {"cs" "cs"} :verify false})
  (s/define! "metals" {:source {:coursier "org.scalameta:metals_2.13" :java-opts ["-Xss4m"]}})
  (s/fake-io {"https://github.com/coursier/coursier/releases/download/v1.0.0/cs" (fake-cs)}
             {"https://github.com/coursier/coursier/releases/latest" "{\"tag_name\":\"v1.0.0\"}"
              "https://repo1.maven.org/maven2/org/scalameta/metals_2.13/maven-metadata.xml"
              (str "<metadata><versioning><versions><version>1.6.8</version><version>1.6.9</version>"
                   "<version>2.0.0-RC1</version><version>2.0.0-M19</version></versions></versioning></metadata>")}
             (atom [])))

(defn- log-lines [] (str/split-lines (slurp (path "cs.log"))))

(deftest a-module-is-resolved-then-built-into-a-launcher-from-the-cache
  (let [io (world)
        {:keys [code out]} (s/breto! {} ["install" "metals" "coursier"] {:io io})]
    (is (= 0 code) out)
    (testing "coursier is installed before what it builds"
      (is (< (str/index-of out "coursier 1.0.0") (str/index-of out "metals 1.6.9"))))
    (is (= "#!/bin/sh\necho org.scalameta:metals_2.13:1.6.9\n" (slurp (path "bin" "metals"))))
    (let [[fetch bootstrap] (log-lines)]
      (testing "only Maven Central counts, and every file needs a checksum"
        (is (str/starts-with? fetch "fetch --cache "))
        (is (str/includes? fetch " --no-default --repository central --checksum SHA-256,SHA-1 org.scalameta:metals_2.13:1.6.9 "))
        (is (re-find #"\| (\S+)/coursier-cache \1/coursier-config/mirror.properties$" fetch)))
      (testing "the launcher embeds the fetched jars without the network"
        (is (str/includes? bootstrap " --mode offline --standalone --deterministic --output "))
        (is (str/includes? bootstrap " --java-opt -Xss4m org.scalameta:metals_2.13:1.6.9 "))))
    (testing "the listing of the resolved jars is what the store records"
      (let [listing (str (s/sha256 (s/file! "lib" "lib\n")) "  https/repo1.maven.org/maven2/lib.jar\n"
                         (s/sha256 (s/file! "app" "org.scalameta:metals_2.13:1.6.9\n")) "  https/repo1.maven.org/maven2/app.jar\n")
            sorted (str (str/join "\n" (sort (str/split-lines listing))) "\n")]
        (is (= (s/sha256 (s/file! "listing" sorted))
               (:sha256 (store/read-meta {:store (path "store")} "metals" "1.6.9"))))))))

(deftest a-module-needs-coursier
  (let [io (world)
        {:keys [code out]} (s/breto! {} ["install" "metals"] {:io io})]
    (is (= 1 code))
    (is (str/includes? out "no coursier is installed; install coursier first"))))

(deftest a-resolution-that-differs-from-the-lock-fails
  (let [io (world)]
    (fs/create-dirs (path "shared"))
    (spit (path "shared" "@scala.edn") (pr-str {:members ["metals@1.6.9"]}))
    (let [cfg {:registries [(path "packages") (path "shared")]}]
      (is (= 0 (:code (s/breto! cfg ["install" "coursier"] {:io io}))))
      (is (= 0 (:code (binding [*err* (java.io.StringWriter.)] (s/breto! cfg ["lock" (path "shared")] {:io io})))))
      (is (:sha256 (get-in (lockfile/read-lock (path "shared" "breto.lock")) [:entries "metals@1.6.9"])))
      (testing "the same resolution matches"
        (is (= 0 (:code (s/breto! (assoc cfg :store (path "store2") :bin-dir (path "bin2"))
                                  ["install" "coursier" "metals@1.6.9"] {:io io})))))
      (testing "a jar with other content does not"
        (spit (path "jar-content") "tampered\n")
        (let [{:keys [code out]} (s/breto! (assoc cfg :store (path "store3") :bin-dir (path "bin3"))
                                           ["install" "coursier" "metals@1.6.9"] {:io io})]
          (is (= 1 code))
          (is (str/includes? out "[FAIL] metals"))
          (is (not (fs/exists? (path "bin3" "metals")))))))))

(deftest maven-releases-are-the-final-versions-of-the-metadata
  (let [io (world)
        pkg (package/normalize {:name "metals" :source {:coursier "org.scalameta:metals_2.13"}})]
    (is (= "1.6.9" (:version (source/latest pkg io))))
    (is (= "1.6.8" (:version (source/newest (assoc pkg :skip ["1.6.9"]) io))))))

(deftest coursier-definitions-name-the-pom-and-the-launcher
  (let [pkg (package/normalize {:name "metals" :source {:coursier "org.scalameta:metals_2.13"}})]
    (is (= {"metals" "bin/metals"} (:bins pkg)))
    (is (= "https://repo1.maven.org/maven2/org/scalameta/metals_2.13/1.6.9/metals_2.13-1.6.9.pom"
           (:url (package/artifact pkg {:tag "1.6.9" :version "1.6.9"})))))
  (is (= ["1.0" "1.2.3"] (coursier/release-versions "<version>1.0</version><version> 1.2.3 </version><version>1.3-RC1</version>")))
  (doseq [source [{:coursier "metals"} {:coursier "a:b" :main "1x"} {:coursier "a:b" :java-opts "-Xss4m"}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names a module on Maven Central"
                          (package/normalize {:name "x" :source source}))
        (pr-str source))))
