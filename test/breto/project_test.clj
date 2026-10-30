;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.project-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.project :as project]
            [breto.run :as run]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- world
  "The package alpha with release 1.0.0, linking the command alpha."
  []
  (s/define! "alpha" {:source {:github "acme/alpha"} :asset "alpha" :verify false})
  (s/fake-io {"https://github.com/acme/alpha/releases/download/v1.0.0/alpha"
              (s/file! "alpha-1.0.0" "#!/bin/sh\necho alpha 1.0.0\n")}
             {"https://github.com/acme/alpha/releases/latest" "{\"tag_name\":\"v1.0.0\"}"}
             (atom [])))

(defn- write! [dir file data]
  (fs/create-dirs (path dir))
  (spit (path dir file) (pr-str data)))

(defn- group! [name data]
  (fs/create-dirs (path "shared"))
  (spit (path "shared" (str name ".edn")) (pr-str data)))

(defn- breto!
  "Runs breto with the test registries, the shared group directory and the
  environments under envs, `exec` recording the invocations of breto run."
  ([io args] (breto! io args (atom [])))
  ([io args runs] (breto! io args runs 0))
  ([io args runs exit]
   (let [err (java.io.StringWriter.)
         result (binding [*err* err]
                  (s/breto! {:registries [(path "packages") (path "shared")] :env-dir (path "envs")}
                            args
                            {:io (assoc io :exec (fn [invocation] (swap! runs conj invocation) exit))}))]
     (assoc result :err (str err)))))

(deftest local-specs-take-the-place-of-shared-ones
  (is (= ["alpha@2.0" "@go" "beta" "gamma@1"]
         (project/merge-packages ["alpha@1.0" "@go" "beta"] ["gamma@1" "alpha@2.0"])))
  (is (= ["@go" "x"] (project/merge-packages ["@go"] ["@go" "x"]))))

(deftest the-local-file-is-read-over-the-shared-one-and-the-groups
  (group! "@tools" {:members ["alpha"] :env {"A" "group" "B" "group"}
                    :commands {"lint" {:run ["group-lint"]} "test" {:run ["group-test"]}}})
  (write! "app" ".breto.edn" {:packages ["@tools"] :env {"B" "shared" "C" "shared"}
                              :commands {"test" {:run ["shared-test"]}}})
  (write! "app" ".breto.local.edn" {:env {"C" "local"} :commands {"dev" {:run "make dev"}} :menu false})
  (let [p (project/load-project {"@tools" {:members ["alpha"] :env {"A" "group" "B" "group"}
                                           :commands {"lint" {:run ["group-lint"]} "test" {:run ["group-test"]}}}}
                                (path "app") (constantly true))]
    (is (= {"A" "group" "B" "shared" "C" "local"} (:env p)))
    (is (= {"lint" {:run ["group-lint"]} "test" {:run ["shared-test"]} "dev" {:run "make dev"}} (:commands p)))
    (is (= ["alpha"] (:specs p)))
    (is (false? (:menu p)))
    (is (= [(path "app" ".breto.edn") (path "app" ".breto.local.edn")] (take 2 (:files p))))
    (is (empty? (:warnings p)))))

(deftest a-local-file-alone-makes-a-project
  (write! "app" ".breto.local.edn" {:packages ["alpha"]})
  (fs/create-dirs (path "app" "src"))
  (is (= (path "app") (project/find-dir (path "app" "src"))))
  (is (= ["alpha"] (:specs (project/load-project {} (path "app") (constantly nil))))))

(deftest groups-giving-one-command-different-runs-conflict
  (let [groups {"@a" {:members ["alpha"] :commands {"lint" {:run ["a"]}}}
                "@b" {:members ["alpha"] :commands {"lint" {:run ["b"]}}}}]
    (write! "app" ".breto.edn" {:packages ["@a" "@b"]})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the groups @a and @b define the command lint differently"
                          (project/load-project groups (path "app") (constantly nil))))))

(deftest invalid-project-files-are-refused
  (doseq [data [{:packages "alpha"}
                {:unknown 1}
                {:env {"PATH" "/x"}}
                {:commands {"lint" {}}}
                {:commands {"lint" {:run 1}}}
                {:commands {"lint" {:run ["x"] :depends "y"}}}
                {:commands {"bad name" {:run ["x"]}}}
                {:commands {"lint" {:run ["x"] :platforms {"windows-x86_64" {:run ["x.exe"]}}}}}
                {:commands {"lint" {:run ["x"] :dir "/abs"}}}]]
    (write! "app" ".breto.edn" data)
    (is (thrown? clojure.lang.ExceptionInfo (project/read-file (path "app" ".breto.edn"))) (pr-str data))))

(deftest a-local-file-git-does-not-ignore-is-warned-about
  (when (fs/which "git")
    (write! "app" ".breto.edn" {:packages ["alpha"]})
    (write! "app" ".breto.local.edn" {:env {"A" "1"}})
    (process/shell {:dir (path "app") :out :string :err :string} "git" "init" "--quiet")
    (is (= 1 (count (:warnings (project/load-project {} (path "app"))))))
    (is (str/includes? (first (:warnings (project/load-project {} (path "app")))) "add .breto.local.edn to .gitignore"))
    (spit (path "app" ".gitignore") ".breto.local.edn\n")
    (is (empty? (:warnings (project/load-project {} (path "app")))))
    (testing "outside a work tree nothing is said"
      (fs/delete-tree (path "app" ".git"))
      (is (empty? (:warnings (project/load-project {} (path "app"))))))))

(deftest dependencies-run-first-and-once
  (let [p {:commands {"check" {:depends ["lint" "test"]}
                      "lint" {:run ["lint"] :depends ["fmt"]}
                      "test" {:run ["test"] :depends ["fmt"]}
                      "fmt" {:run ["fmt"] :platforms {"darwin-aarch64" {:run ["gfmt"]}}}
                      "loop" {:run ["x"] :depends ["loop"]}}}]
    (is (= ["fmt" "lint" "test" "check"] (map first (project/run-order p "check" "linux-x86_64"))))
    (is (= ["gfmt"] (:run (second (first (project/run-order p "check" "darwin-aarch64"))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"depends on itself" (project/run-order p "loop" "linux-x86_64")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no command nope" (project/run-order p "nope" "linux-x86_64")))))

(deftest commands-render-their-placeholders-and-take-arguments
  (let [vars (run/template-vars "/p" "/p/bin" "/h" "linux-x86_64")]
    (is (= ["lint" "/p/src" "--fix"] (run/invocation "l" {:run ["lint" "{project}/src"]} ["--fix"] vars)))
    (is (= ["sh" "-c" "echo ${HOME} {nope} linux" "l" "a"]
           (run/invocation "l" {:run "echo ${HOME} {nope} {os}"} ["a"] vars)))))

(deftest env-links-wrappers-sets-variables-and-greets-on-entering
  (let [io (world)]
    (write! "app" ".breto.edn" {:packages ["alpha"] :env {"ROOT" "{project}/out"}
                                :commands {"hello" {:run ["alpha"] :description "Say hello"}}
                                :on-enter "hello"})
    (let [{:keys [code out err]} (breto! io ["env" "--dir" (path "app")])
          bin (second (re-find #"export BRETO_ENV_BIN='([^']*)'" out))]
      (is (= 0 code) err)
      (is (str/includes? out (str "export ROOT='" (path "app") "/out'")))
      (is (str/includes? (slurp (str (fs/path bin "hello"))) (str "breto run --dir '" (path "app") "' 'hello'")))
      (is (fs/executable? (fs/path bin "hello")))
      (is (not (str/includes? err "commands of")))
      (is (not (str/includes? out "breto run --dir"))))
    (testing "--enter prints the menu and runs :on-enter"
      (let [{:keys [out err]} (breto! io ["env" "--enter" "--dir" (path "app")])]
        (is (str/includes? err "hello Say hello"))
        (is (str/ends-with? (str/trim out) (str "breto run --dir '" (path "app") "' 'hello'")))))
    (testing "a command named like a package command is refused"
      (write! "app" ".breto.edn" {:packages ["alpha"] :commands {"alpha" {:run ["x"]}}})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"commands alpha have the names of commands its packages link"
                            (breto! io ["env" "--dir" (path "app")]))))))

(deftest run-runs-the-dependencies-first-with-the-project-tools
  (let [io (world)
        runs (atom [])]
    (write! "app" ".breto.edn" {:packages ["alpha"]
                                :commands {"fmt" {:run ["alpha" "fmt"]}
                                           "lint" {:run "alpha lint \"$@\"" :depends ["fmt"] :env {"LEVEL" "{os}"}}}})
    (let [{:keys [code err]} (breto! io ["run" "--dir" (path "app") "lint" "--fix" "--dir" "x"] runs)
          [fmt lint] @runs]
      (is (= 0 code) err)
      (is (= ["alpha" "fmt"] (:command fmt)))
      (is (= ["sh" "-c" "alpha lint \"$@\"" "lint" "--fix" "--dir" "x"] (:command lint)))
      (is (= (path "app") (str (:dir lint))))
      (is (= "linux" (get-in lint [:env "LEVEL"])))
      (is (str/starts-with? (get-in lint [:env "PATH"]) (path "envs"))))
    (testing "a failing dependency stops the run with its code"
      (let [failing (atom [])
            {:keys [code]} (breto! io ["run" "--dir" (path "app") "lint"] failing 3)]
        (is (= 3 code))
        (is (= 1 (count @failing)))))
    (testing "an unknown command names the project's commands"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no command nope"
                            (breto! io ["run" "--dir" (path "app") "nope"]))))))

(deftest packages-without-a-release-for-the-platform-are-left-out-with-a-warning
  (let [io (world)]
    (s/define! "beta" {:source {:github "acme/beta"} :verify false :platforms {"darwin-x86_64" {:asset "beta"}}})
    (group! "@tools" {:members ["alpha" "beta"]})
    (write! "app" ".breto.edn" {:packages ["@tools"]})
    (testing "a project gets the tools there are, and a warning about the others"
      (let [{:keys [code out err]} (breto! io ["env" "--dir" (path "app") "--platform" "linux-aarch64"])
            bin (second (re-find #"export BRETO_ENV_BIN='([^']*)'" out))]
        (is (= 0 code) err)
        (is (str/includes? err "[WARN] beta has no release for linux-aarch64, so it is skipped; it has releases for darwin-x86_64"))
        (is (fs/exists? (fs/path bin "alpha")))
        (is (not (fs/exists? (fs/path bin "beta"))))))
    (testing "a group named on the command line skips them too"
      (let [{:keys [code err]} (breto! io ["install" "@tools" "--platform" "linux-aarch64"])]
        (is (= 0 code) err)
        (is (str/includes? err "[WARN] beta has no release for linux-aarch64"))))
    (testing "a package named on its own fails"
      (let [{:keys [code out]} (breto! io ["install" "beta" "--platform" "linux-aarch64"])]
        (is (= 1 code))
        (is (str/includes? out "beta has no release for linux-aarch64"))))))
