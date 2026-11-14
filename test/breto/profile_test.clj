;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.profile-test
  (:require [babashka.fs :as fs]
            [breto.env :as env]
            [breto.profile :as profile]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(def ^:private clusters
  {"kube" {"staging" {:env {"KUBECONFIG" "/kube/staging"} :description "the staging cluster"}
           "production" {:env {"KUBECONFIG" "/kube/production"} :confirm true}}
   "cloud" {"dev" {:env {"ONE_URL" "https://dev.example"}}}})

(defn- project! [data]
  (fs/create-dirs (path "app"))
  (spit (path "app" ".breto.edn") (pr-str (merge {:packages []} data))))

(defn- breto!
  "Runs breto with `config` in the test home, `io` merged into the fake
  network, and returns its exit code, stdout and stderr."
  [config io & args]
  (let [err (java.io.StringWriter.)
        result (binding [*err* err]
                 (s/breto! config args {:io (merge (s/fake-io {} {} (atom [])) io)}))]
    (assoc result :err (str err))))

(defn- selection []
  (profile/read-selection (env/selection-path {:env-dir (path "envs")} (path "app"))))

(defn- answers
  "An ask function returning `lines` in turn, and the questions it got."
  [& lines]
  (let [remaining (atom lines) asked (atom [])]
    [(fn [question] (swap! asked conj question) (let [[a & more] @remaining] (reset! remaining more) a))
     asked]))

(deftest profiles-are-checked
  (is (= [] (profile/problems clusters)))
  (is (= [":profiles maps profile names to their options"] (profile/problems ["kube"])))
  (is (= ["the profile \"a b\" is no name of letters, digits, dots, underscores and dashes"]
         (profile/problems {"a b" {"x" {}}})))
  (is (= ["the profile kube maps option names to options"] (profile/problems {"kube" {}})))
  (is (= ["option x of profile kube has unknown keys :run; an option has :env, :description and :confirm"]
         (profile/problems {"kube" {"x" {:run "make"}}})))
  (is (= ["option x of profile kube: :confirm is true or false"]
         (profile/problems {"kube" {"x" {:confirm "yes"}}})))
  (is (= ["option x of profile kube: :env maps variable names other than PATH and BRETO_* to strings"]
         (profile/problems {"kube" {"x" {:env {"BRETO_X" "1"}}}}))))

(deftest later-layers-add-options-and-replace-those-of-the-same-name
  (is (= {"kube" {"staging" {:env {"KUBECONFIG" "/mine"}}
                  "production" {:env {"KUBECONFIG" "/kube/production"} :confirm true}}
          "cloud" {"dev" {:env {"ONE_URL" "https://dev.example"}}}
          "db" {"local" {}}}
         (profile/merge-profiles clusters nil {"kube" {"staging" {:env {"KUBECONFIG" "/mine"}}} "db" {"local" {}}}))))

(deftest a-selection-of-options-no-longer-defined-is-ignored-with-a-warning
  (is (= {:selected {"kube" "staging"}
          :warnings ["the selected option gone of profile cloud is no longer defined, so its variables are not set"]}
         (profile/active clusters {"kube" "staging" "cloud" "gone"}))))

(deftest two-selected-options-may-not-set-one-variable-differently
  (let [profiles (assoc-in clusters ["cloud" "dev" :env "KUBECONFIG"] "/cloud")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cloud=dev and kube=staging all set KUBECONFIG"
                          (profile/variables profiles {"kube" "staging" "cloud" "dev"})))
    (is (= {"KUBECONFIG" "/kube/staging"} (profile/variables profiles {"kube" "staging"})))))

(deftest selections-are-stored-and-an-empty-one-keeps-the-file
  (let [file (fs/path (path "sel") "profiles.edn")]
    (is (= {} (profile/read-selection file)))
    (profile/write-selection! file {"kube" "staging"})
    (is (= {"kube" "staging"} (profile/read-selection file)))
    (profile/write-selection! file {})
    (is (fs/exists? file) "the shell hook notices a changed file, never a removed one")
    (is (= {} (profile/read-selection file)))
    (spit (str file) "[1 2]")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"holds no selection" (profile/read-selection file)))))

(deftest env-sets-the-variables-of-the-selected-options
  (project! {:profiles clusters :env {"KUBECONFIG" "/project"}})
  (testing "nothing selected leaves the project's own value"
    (let [{:keys [code out err]} (breto! {} {} "env" "--dir" (path "app"))]
      (is (= 0 code) err)
      (is (str/includes? out "export KUBECONFIG='/project'"))
      (is (not (str/includes? out "BRETO_PROFILES")))))
  (is (= 0 (:code (breto! {} {} "profile" "kube" "staging" "--dir" (path "app")))))
  (is (= 0 (:code (breto! {} {} "profile" "cloud" "dev" "--dir" (path "app")))))
  (let [{:keys [code out err]} (breto! {} {} "env" "--dir" (path "app"))]
    (is (= 0 code) err)
    (is (str/includes? out "export KUBECONFIG='/kube/staging'"))
    (is (str/includes? out "export ONE_URL='https://dev.example'"))
    (is (str/includes? out "export BRETO_PROFILES='cloud=dev kube=staging'"))
    (is (str/includes? out (str ":" (env/selection-path {:env-dir (path "envs")} (path "app")) "'"))
        "the hook runs breto env again once the selection changes")))

(deftest run-sets-the-variables-of-the-selected-options
  (project! {:profiles clusters :commands {"deploy" {:run ["deploy"]}}})
  (breto! {} {} "profile" "kube" "staging" "--dir" (path "app"))
  (let [runs (atom [])
        {:keys [code err]} (breto! {} {:exec (fn [invocation] (swap! runs conj invocation) 0)} "run" "--dir" (path "app") "deploy")]
    (is (= 0 code) err)
    (is (= "/kube/staging" (get-in (first @runs) [:env "KUBECONFIG"])))
    (is (= "kube=staging" (get-in (first @runs) [:env "BRETO_PROFILES"])))))

(deftest the-configuration-defines-profiles-every-project-can-select
  (project! {:profiles {"kube" {"staging" {:env {"KUBECONFIG" "/project/staging"}}}}})
  (let [config {:profiles clusters}]
    (is (= 0 (:code (breto! config {} "profile" "cloud" "dev" "--dir" (path "app")))))
    (is (= 0 (:code (breto! config {} "profile" "kube" "staging" "--dir" (path "app")))))
    (let [{:keys [out]} (breto! config {} "env" "--dir" (path "app"))]
      (is (str/includes? out "export KUBECONFIG='/project/staging'") "the project's option replaces the configuration's")
      (is (str/includes? out "export ONE_URL='https://dev.example'")))))

(deftest profile-prints-and-clears-the-selection
  (project! {:profiles clusters})
  (let [{:keys [out err]} (breto! {} {} "profile" "kube" "staging" "--dir" (path "app"))]
    (is (= "kube=staging\n" out))
    (is (str/includes? err "eval \"$(breto env)\"")))
  (testing "the hook's own project needs no hint"
    (is (= "" (:err (breto! {} {:getenv {"BRETO_PROJECT" (path "app")}} "profile" "cloud" "dev" "--dir" (path "app"))))))
  (is (= {"kube" "staging" "cloud" "dev"} (selection)))
  (is (= "kube=staging\n" (:out (breto! {} {} "profile" "--clear" "cloud" "--dir" (path "app")))))
  (is (= "No profile selected.\n" (:out (breto! {} {} "profile" "--clear" "--dir" (path "app")))))
  (is (= {} (selection))))

(deftest an-option-with-confirm-asks-first
  (project! {:profiles clusters})
  (testing "outside a terminal it needs --yes"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pass --yes"
                          (breto! {} {} "profile" "kube" "production" "--dir" (path "app"))))
    (breto! {} {} "profile" "kube" "production" "--yes" "--dir" (path "app"))
    (is (= {"kube" "production"} (selection))))
  (testing "in a terminal, anything but yes keeps the selection"
    (let [[ask asked] (answers "n")]
      (breto! {} {:interactive? true :ask ask} "profile" "kube" "staging" "--dir" (path "app"))
      (is (= {"kube" "staging"} (selection)))
      (breto! {} {:interactive? true :ask ask} "profile" "kube" "production" "--dir" (path "app"))
      (is (= ["Select production of profile kube? [y/N] "] @asked))
      (is (= {"kube" "staging"} (selection))))))

(deftest profile-chooses-each-option-in-the-finder
  (project! {:profiles clusters})
  (breto! {} {} "profile" "kube" "staging" "--dir" (path "app"))
  (let [calls (atom [])
        replies (atom [{:exit 0 :out "dev\t\n"} {:exit 0 :out "-\tnothing\n"}])
        io {:interactive? true :which #(when (= "fzf" %) "/usr/bin/fzf")
            :pick (fn [command input]
                    (swap! calls conj [command input])
                    (let [[reply & more] @replies] (reset! replies more) reply))}
        {:keys [code out]} (breto! {} io "profile" "--dir" (path "app"))]
    (is (= 0 code))
    (is (= "cloud=dev\n" out))
    (testing "every profile in turn, in order of name"
      (is (= ["cloud" "kube"] (map #(second (re-find #"breto profile (\S+)> " (str/join " " (first %)))) @calls)))
      (is (some #{"kube: staging"} (first (second @calls))) "the header names the selected option")
      (is (= "-\tnothing: unset the variables of the profile\nproduction\t\nstaging\tthe staging cluster (selected)\n"
             (second (second @calls)))))
    (testing "leaving the finder keeps the selection"
      (let [{:keys [out]} (breto! {} (assoc io :pick (constantly {:exit 130 :out ""})) "profile" "--dir" (path "app"))]
        (is (= "cloud=dev\n" out))))))

(deftest profile-without-a-finder-prompts-for-numbers
  (project! {:profiles clusters})
  (let [[ask asked] (answers "2")
        {:keys [code out]} (breto! {} {:interactive? true :ask ask} "profile" "kube" "--dir" (path "app"))]
    (is (= 0 code))
    (is (str/includes? out "  2) staging - the staging cluster"))
    (is (str/ends-with? out "kube=staging\n"))
    (is (= ["Option of kube (blank keeps nothing): "] @asked))))

(deftest profile-refuses-what-it-cannot-select
  (project! {:profiles (assoc-in clusters ["cloud" "dev" :env "KUBECONFIG"] "/cloud")})
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no profile db"
                        (breto! {} {} "profile" "db" "x" "--dir" (path "app"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the profile kube has no option dev"
                        (breto! {} {} "profile" "kube" "dev" "--dir" (path "app"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the profiles are cloud \(dev\); kube \(production, staging\)"
                        (breto! {} {} "profile" "--dir" (path "app"))))
  (breto! {} {} "profile" "kube" "staging" "--dir" (path "app"))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"all set KUBECONFIG"
                        (breto! {} {} "profile" "cloud" "dev" "--dir" (path "app"))))
  (is (= {"kube" "staging"} (selection)) "a refused selection is not stored"))

(deftest invalid-profiles-are-reported-with-their-file
  (project! {:profiles {"kube" {"staging" {:confirm "yes"}}}})
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":confirm is true or false"
                        (breto! {} {} "env" "--dir" (path "app"))))
  (project! {})
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"config.edn: the profile kube maps option names to options"
                        (breto! {:profiles {"kube" {}}} {} "env" "--dir" (path "app")))))
