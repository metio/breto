;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.env-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.diagnose :as diagnose]
            [breto.env :as env]
            [breto.project :as project]
            [breto.store :as store]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- world
  "Packages alpha and beta, each with releases 1.0.0 (the latest) and 0.9.0."
  []
  (doseq [name ["alpha" "beta"]]
    (s/define! name {:source {:github (str "acme/" name)} :asset name :verify false}))
  (s/fake-io (into {} (for [name ["alpha" "beta"] version ["0.9.0" "1.0.0"]]
                        [(str "https://github.com/acme/" name "/releases/download/v" version "/" name)
                         (s/file! (str name "-" version) (str "#!/bin/sh\necho " name " " version "\n"))]))
             (into {} (for [name ["alpha" "beta"]]
                        [(str "https://github.com/acme/" name "/releases/latest") "{\"tag_name\":\"v1.0.0\"}"]))
             (atom [])))

(defn- project! [dir packages]
  (fs/create-dirs (path dir))
  (spit (path dir ".breto.edn") (pr-str {:packages packages})))

(def ^:private config {:packages ["alpha"] :env-dir nil})

(defn- env! [io dir & args]
  (let [err (java.io.StringWriter.)
        result (binding [*err* err]
                 (s/breto! (assoc config :env-dir (path "envs")) (concat ["env" "--dir" (path dir)] args) {:io io}))]
    (assoc result :err (str err))))

(defn- exported-path [out]
  (second (re-find #"export PATH='([^']*)'" out)))

(deftest projects-get-their-versions-first-on-path
  (let [io (world)]
    (s/breto! (assoc config :env-dir (path "envs")) ["update"] {:io io})
    (project! "app" ["alpha@0.9.0" "beta"])
    (let [{:keys [code out err]} (env! io "app/src")]
      (is (= 0 code) err)
      (is (str/includes? err "2 installed"))
      (let [bin (first (str/split (exported-path out) #":"))]
        (is (str/starts-with? bin (path "envs")))
        (is (= "#!/bin/sh\necho alpha 0.9.0\n" (slurp (str (fs/path bin "alpha")))))
        (is (= "#!/bin/sh\necho beta 1.0.0\n" (slurp (str (fs/path bin "beta")))))
        (is (str/includes? out (str "export BRETO_PROJECT='" (path "app") "'")))))
    (testing "the global versions stay as they were"
      (is (= "#!/bin/sh\necho alpha 1.0.0\n" (slurp (path "bin" "alpha"))))
      (is (not (fs/exists? (path "bin" "beta")))))
    (testing "a second run installs nothing"
      (let [{:keys [code err]} (env! io "app")]
        (is (= 0 code))
        (is (not (str/includes? err "installed")))))))

(deftest unversioned-packages-use-the-active-version
  (let [io (world)]
    (s/breto! (assoc config :env-dir (path "envs")) ["install" "alpha@0.9.0"] {:io io})
    (project! "app" ["alpha"])
    (let [{:keys [out]} (env! io "app")
          bin (first (str/split (exported-path out) #":"))]
      (is (= "#!/bin/sh\necho alpha 0.9.0\n" (slurp (str (fs/path bin "alpha"))))))))

(deftest the-bin-dir-holds-only-what-the-project-lists
  (let [io (world)]
    (project! "app" ["alpha" "beta"])
    (let [bin (first (str/split (exported-path (:out (env! io "app"))) #":"))]
      (project! "app" ["beta"])
      (env! io "app")
      (is (= ["beta"] (map (comp str fs/file-name) (fs/list-dir bin)))))))

(deftest path-entries-of-other-projects-are-replaced
  (let [cfg {:env-dir (path "envs")}]
    (is (= (str (path "envs" "b" "bin") ":/usr/bin:/bin")
           (env/path-value cfg (path "envs" "b" "bin") (str (path "envs" "a" "bin") ":/usr/bin::/bin:/usr/bin"))))))

(deftest exports-for-github-actions-leave-path-to-github-path
  (let [lines (str/split-lines (env/exports :github "/b:/usr/bin" "/p" "/b" ["/p/.breto.edn"]
                                            {"A" "it's x" "M" "one\nBRETO_EOF_0\ntwo"}))]
    (is (= ["A=it's x" "M<<BRETO_EOF_1" "one" "BRETO_EOF_0" "two" "BRETO_EOF_1"
            "BRETO_ENV_VARS=A M" "BRETO_PROJECT=/p" "BRETO_ENV_BIN=/b" "BRETO_ENV_FILES=/p/.breto.edn"]
           lines))))

(deftest exports-quote-for-the-shell
  (let [code (env/exports :sh "/a b:/it's" "/p" "/a b" ["/p/.breto.edn" "/r/@go.edn"])]
    (is (= "/a b:/it's|/p|/a b|/p/.breto.edn:/r/@go.edn"
           (str/trim (:out (process/shell {:out :string} "sh" "-c"
                                          (str code "\nprintf '%s|%s|%s|%s' \"$PATH\" \"$BRETO_PROJECT\" \"$BRETO_ENV_BIN\" \"$BRETO_ENV_FILES\"")))))))
  (testing "variables are quoted too"
    (is (= "/it's x"
           (str/trim (:out (process/shell {:out :string} "sh" "-c"
                                          (str (env/exports :sh "/a" "/p" "/a" [] {"JAVA_HOME" "/it's x"}) "\nprintf '%s' \"$JAVA_HOME\"")))))))
  (let [fish (env/exports :fish "/a:/b" "/p" "/a" ["/p/.breto.edn"] {"JAVA_HOME" "/it's"})]
    (is (str/ends-with? fish "set --global --export PATH '/a' '/b';\nset --global --export BRETO_PROJECT '/p';\nset --global --export BRETO_ENV_BIN '/a';\nset --global --export BRETO_ENV_FILES '/p/.breto.edn';"))
    (is (str/includes? fish "set --global --export JAVA_HOME '/it'\\''s';\nset --global --export BRETO_ENV_VARS 'JAVA_HOME';"))))

(deftest gc-keeps-what-projects-use-and-forgets-removed-projects
  (let [io (world)
        cfg (assoc config :env-dir (path "envs") :keep 1 :prune true)]
    (s/breto! cfg ["install" "alpha@0.9.0" "beta"] {:io io})
    (s/breto! cfg ["install" "alpha"] {:io io})
    (project! "app" ["alpha@0.9.0" "beta"])
    (env! io "app")
    (let [{:keys [out]} (s/breto! cfg ["gc"] {:io io})]
      (is (not (str/includes? out "alpha 0.9.0")) out)
      (is (not (str/includes? out "beta removed")) out))
    (fs/delete (path "app" ".breto.edn"))
    (let [{:keys [out]} (s/breto! cfg ["gc"] {:io io})]
      (is (str/includes? out (str "removed the links of " (path "app"))))
      (is (str/includes? out "[OK] removed alpha 0.9.0") out)
      (is (str/includes? out "[OK] beta removed") out))
    (is (empty? (fs/list-dir (path "envs"))))))

(deftest projects-are-found-upwards-and-validated
  (project! "app" ["alpha"])
  (fs/create-dirs (path "app" "a" "b"))
  (is (= (path "app") (project/find-dir (path "app" "a" "b"))))
  (is (nil? (project/find-dir (fs/temp-dir))))
  (spit (path "app" ".breto.edn") "{:packages [1]}")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":packages must be a vector"
                        (project/read-file (path "app" ".breto.edn"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no \.breto\.edn"
                        (s/breto! {} ["env" "--dir" (str (fs/temp-dir))]))))

(deftest env-waits-for-the-store-only-to-install
  (let [io (world)
        cfg (assoc config :env-dir (path "envs"))]
    (s/breto! cfg ["install" "alpha"] {:io io})
    (project! "app" ["alpha"])
    (fs/create-dirs (path "store"))
    (let [held (java.util.concurrent.CountDownLatch. 1)
          release (promise)
          holder (future (store/with-store-lock {:store (path "store")} (fn [_]) #(do (.countDown held) @release)))]
      (.await held)
      (try
        (testing "installed versions link while another run holds the store"
          (let [{:keys [code out]} (env! io "app" "--no-wait")]
            (is (= 0 code))
            (is (str/includes? out "export BRETO_PROJECT"))))
        (testing "a missing version fails at once with --no-wait"
          (project! "app" ["alpha@0.9.0"])
          (let [e (try (env! io "app" "--no-wait") nil (catch clojure.lang.ExceptionInfo e e))]
            (is (= :store-busy (:breto/problem (ex-data e))))
            (is (str/includes? (:summary (diagnose/diagnose e {})) "another breto run"))))
        (finally
          (deliver release nil)
          @holder)))))

(deftest no-install-links-what-is-installed-and-names-the-rest
  (let [io (world)
        cfg (assoc config :env-dir (path "envs"))]
    (s/breto! cfg ["install" "alpha"] {:io io})
    (project! "app" ["alpha"])
    (is (= 0 (:code (env! io "app" "--no-install"))))
    (project! "app" ["alpha" "beta@0.9.0"])
    (let [e (try (env! io "app" "--no-install") nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :env-missing (:breto/problem (ex-data e))))
      (is (= ["beta@0.9.0"] (:missing (ex-data e))))
      (is (str/includes? (first (:advice (diagnose/diagnose e {}))) "eval \"$(breto env)\"")))
    (is (not (fs/exists? (path "store" "beta"))))))

(deftest installing-a-superseded-package-warns
  (let [io (world)]
    (s/define! "alpha" {:source {:github "acme/alpha"} :asset "alpha" :verify false :superseded-by "beta"})
    (let [{:keys [code out]} (s/breto! (assoc config :env-dir (path "envs")) ["install" "alpha"] {:io io})]
      (is (= 0 code))
      (is (str/includes? out "[WARN] alpha: superseded by beta")))))

(deftest a-shared-group-pins-the-versions-of-projects
  (let [io (world)
        shared (path "shared")]
    (fs/create-dirs shared)
    (spit (str (fs/path shared "@toolchain.edn")) (pr-str {:members ["alpha@0.9.0" "beta@1.0.0"]}))
    (project! "app" ["@toolchain"])
    (let [env! (fn [] (let [err (java.io.StringWriter.)
                            result (binding [*err* err]
                                     (s/breto! (assoc config :env-dir (path "envs") :registries [(path "packages") shared])
                                               ["env" "--dir" (path "app")] {:io io}))]
                        (assoc result :err (str err))))
          bin #(first (str/split (exported-path (:out %)) #":"))
          first-run (env!)]
      (is (= 0 (:code first-run)) (:err first-run))
      (is (= "#!/bin/sh\necho alpha 0.9.0\n" (slurp (str (fs/path (bin first-run) "alpha")))))
      (testing "a new version in the shared group reaches the project on its next env"
        (spit (str (fs/path shared "@toolchain.edn")) (pr-str {:members ["alpha@1.0.0" "beta@1.0.0"]}))
        (let [second-run (env!)]
          (is (= 0 (:code second-run)) (:err second-run))
          (is (= "#!/bin/sh\necho alpha 1.0.0\n" (slurp (str (fs/path (bin second-run) "alpha"))))))))))

(defn- shared-world
  "world with a registry directory `shared` holding @toolchain, pinning
  alpha to `version`, and a project app using it."
  [version]
  (let [io (world)]
    (fs/create-dirs (path "shared"))
    (spit (path "shared" "@toolchain.edn") (pr-str {:members [(str "alpha@" version)]}))
    (project! "app" ["@toolchain"])
    io))

(def ^:private shared-config
  (delay {:packages ["beta"] :env-dir nil}))

(defn- shared! [io & args]
  (s/breto! (assoc @shared-config :env-dir (path "envs") :registries [(path "packages") (path "shared")]) args {:io io}))

(defn- linked-alpha []
  (let [bin (:bin (first (filter #(= (path "app") (:project %)) (env/environments {:env-dir (path "envs")}))))]
    (slurp (str (fs/path bin "alpha")))))

(deftest env-exports-the-files-its-packages-come-from
  (let [io (shared-world "0.9.0")
        {:keys [out]} (binding [*err* (java.io.StringWriter.)] (shared! io "env" "--dir" (path "app")))]
    (is (str/includes? out (str "export BRETO_ENV_FILES='" (path "app" ".breto.edn") ":" (path "shared" "@toolchain.edn") "'")))))

(deftest update-moves-projects-to-the-versions-their-groups-name
  (let [io (shared-world "0.9.0")]
    (binding [*err* (java.io.StringWriter.)] (shared! io "env" "--dir" (path "app")))
    (is (= "#!/bin/sh\necho alpha 0.9.0\n" (linked-alpha)))
    (spit (path "shared" "@toolchain.edn") (pr-str {:members ["alpha@1.0.0"]}))
    (let [{:keys [code out]} (shared! io "update")]
      (is (= 0 code) out)
      (is (= "#!/bin/sh\necho alpha 1.0.0\n" (linked-alpha))))
    (testing "the project's version is installed beside, not activated"
      (is (not (fs/exists? (path "bin" "alpha"))))
      (is (= "#!/bin/sh\necho beta 1.0.0\n" (slurp (path "bin" "beta")))))
    (testing "the old version is free for gc"
      (s/breto! (assoc @shared-config :env-dir (path "envs") :registries [(path "packages") (path "shared")] :prune true :keep 1) ["gc"] {:io io})
      (is (= ["1.0.0"] (map :version (store/versions {:store (path "store")} "alpha")))))))

(deftest update-of-named-packages-leaves-projects-alone
  (let [io (shared-world "0.9.0")]
    (binding [*err* (java.io.StringWriter.)] (shared! io "env" "--dir" (path "app")))
    (spit (path "shared" "@toolchain.edn") (pr-str {:members ["alpha@1.0.0"]}))
    (shared! io "update" "beta")
    (is (= "#!/bin/sh\necho alpha 0.9.0\n" (linked-alpha)))))

(deftest a-broken-project-keeps-its-links
  (let [io (shared-world "0.9.0")]
    (binding [*err* (java.io.StringWriter.)] (shared! io "env" "--dir" (path "app")))
    (spit (path "app" ".breto.edn") "{:packages [\"@nothing\"]}")
    (let [{:keys [code out]} (shared! io "update")]
      (is (= 0 code))
      (is (str/includes? out (str "[WARN] project " (path "app") " keeps its links: unknown group @nothing")))
      (is (= "#!/bin/sh\necho alpha 0.9.0\n" (linked-alpha))))))

(deftest a-child-shell-can-restore-the-replaced-values
  (is (= "/original x"
         (:out (process/shell {:out :string} "bash" "--norc" "-c"
                              (str "export JAVA_HOME='/original x'\n"
                                   (env/exports :sh "/a" "/p" "/a" [] {"JAVA_HOME" "/project"})
                                   "\n" (fs/which "bash") " --norc -c 'printf %s \"$__breto_saved_JAVA_HOME\"'"))))))

(deftest projects-are-named-without-dots-and-by-the-logical-directory
  (fs/create-dirs (path "real" "app"))
  (spit (path "real" "app" ".breto.edn") "{:packages [\"a\"]}")
  (fs/create-sym-link (path "link") (path "real"))
  (is (= (path "real" "app") (project/find-dir (str (path "real" "app") "/."))))
  (is (= (path "link" "app") (project/find-dir "." {"PWD" (path "link" "app")} (path "real" "app"))))
  (testing "a PWD naming another directory is ignored"
    (is (= (path "real" "app") (project/find-dir "." {"PWD" (path)} (path "real" "app"))))))
