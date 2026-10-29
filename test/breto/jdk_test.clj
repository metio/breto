;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.jdk-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.package :as package]
            [breto.support :as s :refer [*root* path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- tarball!
  "Builds `name` with an executable bin/COMMAND for each of `commands` under a
  top-level directory, each printing `label`."
  [name label commands]
  (let [src (fs/path *root* "src" "jdk")
        out (fs/path *root* "assets" name)]
    (fs/create-dirs (fs/path src "bin"))
    (spit (str (fs/path src "release")) label)
    (doseq [command commands]
      (spit (str (fs/path src "bin" command)) (str "#!/bin/sh\necho " label "\n"))
      (fs/set-posix-file-permissions (fs/path src "bin" command) "rwxr-xr-x"))
    (fs/create-dirs (fs/parent out))
    (process/shell "tar" "--create" "--gzip" "--file" (str out) "--directory" (str (fs/parent src)) "jdk")
    (fs/delete-tree (fs/path *root* "src"))
    (str out)))

(def ^:private definition
  {:source {:github "acme/jdk"}
   :tag-prefix "jdk-"
   :vars {:feature {:match "^(\\d+)"} :file-version {:replace ["+" "_"]}}
   :asset "JDK{feature}-linux_{file-version}.tar.gz"
   :strip 1
   :layout :tree
   :bin-dir "bin"
   :env {"JAVA_HOME" "{home}"}
   :verify false})

(defn- world []
  (s/define! "jdk" definition)
  (s/fake-io {"https://github.com/acme/jdk/releases/download/jdk-25.0.1+8/JDK25-linux_25.0.1_8.tar.gz"
              (tarball! "jdk25.tar.gz" "jdk25" ["java" "javac" "jrunscript"])
              "https://github.com/acme/jdk/releases/download/jdk-27.0.1+2/JDK27-linux_27.0.1_2.tar.gz"
              (tarball! "jdk27.tar.gz" "jdk27" ["java" "javac" "jwebserver"])}
             {"https://github.com/acme/jdk/releases/latest" "{\"tag_name\":\"jdk-27.0.1+2\"}"}
             (atom [])))

(deftest vars-derive-placeholders-from-the-version
  (let [pkg (package/normalize (assoc definition :name "jdk"))]
    (is (= {:feature "27" :file-version "27.0.1.0.2_1"}
           (select-keys (package/template-vars pkg {:tag "jdk-27.0.1.0.2+1" :version "27.0.1.0.2+1"})
                        [:feature :file-version])))
    (is (thrown-with-msg? Exception #"does not match the :feature pattern"
                          (package/template-vars pkg {:tag "jdk-x" :version "x"})))
    (testing "invalid definitions are refused"
      (doseq [bad [{:vars {:version {:replace ["+" "_"]}}}
                   {:vars {:feature {:match "("}}}
                   {:bin-dir "bin" :layout :binary :bins {"java" "bin/java"}}
                   {:bin-dir "../bin"}
                   {:env {"PATH" "{home}/bin"}}
                   {:env {"BRETO_PROJECT" "x"}}]]
        (is (thrown? Exception (package/normalize (merge (assoc definition :name "jdk") bad))) (pr-str bad))))))

(deftest bin-dir-links-every-command-of-the-active-version
  (let [io (world)
        bin #(path "bin" %)]
    (is (= 0 (:code (s/breto! {} ["install" "jdk@25.0.1+8"] {:io io}))))
    (is (= #{"java" "javac" "jrunscript"} (set (map (comp str fs/file-name) (fs/list-dir (path "bin"))))))
    (is (= "#!/bin/sh\necho jdk25\n" (slurp (bin "java"))))
    (testing "the next version's commands replace the previous one's"
      (is (= 0 (:code (s/breto! {} ["install" "jdk"] {:io io}))))
      (is (= #{"java" "javac" "jwebserver"} (set (map (comp str fs/file-name) (fs/list-dir (path "bin"))))))
      (is (= "#!/bin/sh\necho jdk27\n" (slurp (bin "java")))))
    (testing "use switches back, commands included"
      (is (= 0 (:code (s/breto! {} ["use" "jdk" "25.0.1+8"] {:io io}))))
      (is (fs/exists? (bin "jrunscript")))
      (is (not (fs/exists? (bin "jwebserver" ) {:nofollow-links true}))))
    (testing "links of other packages stay"
      (spit (path "foreign") "x")
      (fs/create-sym-link (bin "other") (path "foreign"))
      (is (= 0 (:code (s/breto! {} ["use" "jdk" "27.0.1+2"] {:io io}))))
      (is (fs/exists? (bin "other"))))))

(defn- env! [io dir]
  (let [err (java.io.StringWriter.)
        result (binding [*err* err]
                 (s/breto! {:env-dir (path "envs")} ["env" "--dir" (path dir)] {:io io}))]
    (assoc result :err (str err))))

(defn- project! [dir packages]
  (fs/create-dirs (path dir))
  (spit (path dir ".breto.edn") (pr-str {:packages packages})))

(defn- bash
  "Runs `script` in a bash without startup files and returns its trimmed output."
  [script]
  (str/trim (:out (process/shell {:out :string :err :string} "bash" "--norc" "-c" script))))

(deftest env-exports-a-stable-home-for-the-project
  (let [io (world)]
    (project! "app" ["jdk@25.0.1+8"])
    (let [{:keys [code out err]} (env! io "app")
          home (second (re-find #"export JAVA_HOME='([^']*)'" out))]
      (is (= 0 code) err)
      (is (str/ends-with? home "/homes/jdk"))
      (is (= "jdk25" (slurp (str (fs/path home "release")))))
      (is (str/includes? out "export BRETO_ENV_VARS='JAVA_HOME'"))
      (testing "a new version moves the link, not the variable"
        (project! "app" ["jdk@27.0.1+2"])
        (let [again (env! io "app")]
          (is (= 0 (:code again)) (:err again))
          (is (str/includes? (:out again) (str "export JAVA_HOME='" home "'")))
          (is (= "jdk27" (slurp (str (fs/path home "release")))))))
      (testing "gc keeps the version the home points to"
        (project! "app" ["jdk@27.0.1+2"])
        (is (= 0 (:code (s/breto! {:env-dir (path "envs")} ["gc" "jdk" "--keep" "0"] {:io io}))))
        (is (fs/exists? (path "store" "jdk" "27.0.1+2")))))))

(deftest env-restores-variables-on-switching-and-leaving
  (let [io (world)]
    (s/define! "plain" {:source {:github "acme/plain"} :asset "plain" :verify false})
    (let [io (assoc io :download! (let [d (:download! io)]
                                    (fn [url dest & more]
                                      (if (= url "https://github.com/acme/plain/releases/download/v1.0.0/plain")
                                        (spit (str dest) "#!/bin/sh\n")
                                        (apply d url dest more)))))
          _ (project! "java" ["jdk@25.0.1+8"])
          _ (project! "other" ["plain@1.0.0"])
          java (:out (env! io "java"))
          other (:out (env! io "other"))
          java-file (path "java.sh")
          other-file (path "other.sh")]
      (spit java-file java)
      (spit other-file other)
      (testing "a value set before the project comes back"
        (is (= "in=/opt/jdk|switched=/opt/jdk"
               (bash (str "export JAVA_HOME=/opt/jdk\n"
                          ". " java-file "\n"
                          "case $JAVA_HOME in */homes/jdk) ;; *) echo wrong; exit 1;; esac\n"
                          "echo \"in=$__breto_saved_JAVA_HOME|$(. " other-file "; echo switched=$JAVA_HOME)\"")))))
      (testing "an unset variable is unset again"
        (is (= "unset" (bash (str "unset JAVA_HOME\n. " java-file "\n. " other-file "\necho ${JAVA_HOME-unset}")))))
      (testing "entering the same project twice keeps the original value"
        (is (= "/opt/jdk" (bash (str "export JAVA_HOME=/opt/jdk\n. " java-file "\n. " java-file "\n. " other-file "\necho $JAVA_HOME")))))
      (testing "the hook restores the value when the shell leaves the project"
        (is (= "/opt/jdk|"
               (bash (str "export JAVA_HOME=/opt/jdk\n"
                          "source shell/env-hook.bash\n"
                          ". " java-file "\n"
                          "__breto_project=" (path "java") "\n"
                          "cd /\n"
                          "__breto_env\n"
                          "echo \"$JAVA_HOME|${BRETO_ENV_VARS-}\""))))))))

(deftest two-packages-setting-one-variable-fail
  (let [io (world)]
    (s/define! "jdk-again" definition)
    (let [io (assoc io :download! (let [d (:download! io)]
                                    (fn [url dest & more]
                                      (apply d (str/replace url "acme/jdk-again" "acme/jdk") dest more))))]
      (project! "app" ["jdk@25.0.1+8" "jdk-again@25.0.1+8"])
      (let [e (try (env! io "app") nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :env-conflict (:breto/problem (ex-data e))))
        (is (= "JAVA_HOME" (:variable (ex-data e))))
        (is (= ["jdk" "jdk-again"] (:packages (ex-data e))))))))

(deftest the-project-settles-a-variable-its-packages-disagree-on
  (let [io (world)]
    (s/define! "jdk-again" definition)
    (let [io (assoc io :download! (let [d (:download! io)]
                                    (fn [url dest & more]
                                      (apply d (str/replace url "acme/jdk-again" "acme/jdk") dest more))))]
      (fs/create-dirs (path "app"))
      (spit (path "app" ".breto.edn") (pr-str {:packages ["jdk@25.0.1+8" "jdk-again@25.0.1+8"]
                                               :env {"JAVA_HOME" "{homes}/jdk-again"}}))
      (let [{:keys [code out err]} (env! io "app")]
        (is (= 0 code) err)
        (is (re-find #"export JAVA_HOME='[^']*/homes/jdk-again'" out))))))

(deftest packages-agreeing-on-a-value-share-the-directory-of-homes
  (let [io (world)
        plugin (assoc definition :env {"PLUGINS" "{homes}"})]
    (s/define! "jdk" plugin)
    (s/define! "jdk-again" plugin)
    (let [io (assoc io :download! (let [d (:download! io)]
                                    (fn [url dest & more]
                                      (apply d (str/replace url "acme/jdk-again" "acme/jdk") dest more))))]
      (project! "app" ["jdk@25.0.1+8" "jdk-again@27.0.1+2"])
      (let [{:keys [code out err]} (env! io "app")
            homes (second (re-find #"export PLUGINS='([^']*)'" out))]
        (is (= 0 code) err)
        (is (str/ends-with? homes "/homes"))
        (is (= #{"jdk" "jdk-again"} (set (map (comp str fs/file-name) (fs/list-dir homes)))))
        (is (= "jdk27" (slurp (str (fs/path homes "jdk-again" "release")))))))))

(deftest doctor-reports-commands-several-packages-provide
  (let [io (world)]
    (s/define! "jdk-again" definition)
    (let [io (assoc io :download! (let [d (:download! io)]
                                    (fn [url dest & more]
                                      (apply d (str/replace url "acme/jdk-again" "acme/jdk") dest more))))
          doctor (fn [] (:out (s/breto! {:packages ["jdk" "jdk-again"]} ["doctor"] {:io io})))]
      (is (= 0 (:code (s/breto! {} ["install" "jdk@25.0.1+8" "jdk-again@27.0.1+2"] {:io io}))))
      (is (str/includes? (doctor) (str "[WARN] Several installed packages provide the same commands\n"
                                       "       jdk and jdk-again provide java, javac, linked to jdk-again\n")))
      (testing "use moves the links and the report follows"
        (is (= 0 (:code (s/breto! {} ["use" "jdk" "25.0.1+8"] {:io io}))))
        (is (str/includes? (doctor) "jdk and jdk-again provide java, javac, linked to jdk\n")))
      (testing "commands only one package provides are left out"
        (is (not (str/includes? (doctor) "jrunscript")))
        (is (not (str/includes? (doctor) "jwebserver")))))))

(deftest doctor-finds-no-shared-commands-in-one-package
  (let [io (world)]
    (is (= 0 (:code (s/breto! {} ["install" "jdk@25.0.1+8"] {:io io}))))
    (is (str/includes? (:out (s/breto! {} ["doctor"] {:io io}))
                       "[OK]   No two installed packages provide the same command"))))
