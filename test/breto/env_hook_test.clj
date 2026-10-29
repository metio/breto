;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.env-hook-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- fake-breto!
  "A breto that logs each env call and prints the exports of a project bin
  directory, or fails when the project file says :fail."
  []
  (fs/create-dirs (path "fake"))
  (spit (path "fake" "breto")
        (str "#!/bin/sh\n"
             "for dir; do :; done\n"
             "echo \"$dir\" >> " (path "calls") "\n"
             "grep -q :fail \"$dir/.breto.edn\" && { echo failed >&2; exit 1; }\n"
             "bin=" (path "envs") "/$(basename \"$dir\")\n"
             "mkdir -p \"$bin\" && touch \"$bin\"\n"
             "printf 'export PATH=%s:\"$PATH\"\\nexport BRETO_PROJECT=%s\\nexport BRETO_ENV_BIN=%s\\nexport BRETO_ENV_FILES=%s/.breto.edn:%s\\n' \"$bin\" \"$dir\" \"$bin\" \"$dir\" " (path "shared" "@group.edn") "\n"))
  (fs/set-posix-file-permissions (path "fake" "breto") "rwxr-xr-x"))

(defn- project! [name content]
  (fs/create-dirs (path name "src"))
  (spit (path name ".breto.edn") content))

(defn- session
  "Runs `steps`, shell lines, in one bash that sourced the hook, calling the
  hook after each like a prompt would, and returns what the last one printed."
  [& steps]
  (let [script (str "PATH=" (path "fake") ":/usr/bin:/bin\n"
                    "source shell/env-hook.bash\n"
                    (str/join "\n" (map #(str % "\n__breto_env") steps))
                    "\necho \"PATH=$PATH|PROJECT=$BRETO_PROJECT\"\n")]
    (str/trim (:out (process/shell {:out :string :err :string} "bash" "--norc" "-c" script)))))

(defn- calls []
  (if (fs/exists? (path "calls")) (str/split-lines (slurp (path "calls"))) []))

(deftest entering-a-project-runs-env-once
  (fake-breto!)
  (project! "app" "{:packages [\"kind\"]}")
  (is (= (str "PATH=" (path "envs" "app") ":" (path "fake") ":/usr/bin:/bin|PROJECT=" (path "app"))
         (session (str "cd " (path "app" "src")) "cd .." "true")))
  (is (= [(path "app")] (calls))))

(deftest leaving-a-project-restores-path
  (fake-breto!)
  (project! "app" "{:packages [\"kind\"]}")
  (is (= (str "PATH=" (path "fake") ":/usr/bin:/bin|PROJECT=")
         (session (str "cd " (path "app")) (str "cd " (path))))))

(deftest switching-projects-replaces-the-entry
  (fake-breto!)
  (project! "one" "{:packages [\"kind\"]}")
  (project! "two" "{:packages [\"helm\"]}")
  (is (= (str "PATH=" (path "envs" "two") ":" (path "fake") ":/usr/bin:/bin|PROJECT=" (path "two"))
         (session (str "cd " (path "one")) (str "cd " (path)) (str "cd " (path "two")))))
  (is (= [(path "one") (path "two")] (calls))))

(deftest editing-the-project-file-runs-env-again
  (fake-breto!)
  (project! "app" "{:packages [\"kind\"]}")
  (session (str "cd " (path "app")) "sleep 1.1; echo '{:packages [\"helm\"]}' > .breto.edn" "true")
  (is (= [(path "app") (path "app")] (calls))))

(deftest a-failing-env-is-not-retried-on-every-prompt
  (fake-breto!)
  (project! "app" "{:fail true}")
  (testing "stays off PATH and runs once"
    (is (= (str "PATH=" (path "fake") ":/usr/bin:/bin|PROJECT=")
           (session (str "export XDG_RUNTIME_DIR=" (path)) (str "cd " (path "app")) "true" "true")))
    (is (= 1 (count (calls))))))

(deftest a-failing-env-is-retried-once-the-file-changes
  (fake-breto!)
  (project! "app" "{:fail true}")
  (is (= (str "PATH=" (path "envs" "app") ":" (path "fake") ":/usr/bin:/bin|PROJECT=" (path "app"))
         (session (str "export XDG_RUNTIME_DIR=" (path))
                  (str "cd " (path "app"))
                  "sleep 1.1; echo '{:packages [\"kind\"]}' > .breto.edn"
                  "true")))
  (is (= 2 (count (calls)))))

(deftest sourcing-twice-hooks-once
  (is (= "__breto_env;x"
         (str/trim (:out (process/shell {:out :string} "bash" "--norc" "-c"
                                        "PROMPT_COMMAND=x; source shell/env-hook.bash; source shell/env-hook.bash; echo \"$PROMPT_COMMAND\""))))))

(deftest editing-a-group-file-runs-env-again
  (fake-breto!)
  (project! "app" "{:packages [\"@group\"]}")
  (fs/create-dirs (path "shared"))
  (spit (path "shared" "@group.edn") "{:members [\"kind\"]}")
  (session (str "cd " (path "app")) (str "sleep 1.1; echo '{:members [\"helm\"]}' > " (path "shared" "@group.edn")) "true")
  (is (= [(path "app") (path "app")] (calls))))

(deftest leaving-a-project-unsets-its-files
  (fake-breto!)
  (project! "app" "{:packages [\"kind\"]}")
  (is (= "unset"
         (str/trim (:out (process/shell {:out :string :err :string} "bash" "--norc" "-c"
                                        (str "PATH=" (path "fake") ":/usr/bin:/bin\nsource shell/env-hook.bash\n"
                                             "cd " (path "app") "\n__breto_env\ncd " (path) "\n__breto_env\n"
                                             "echo \"${BRETO_ENV_FILES-unset}\"")))))))

(deftest another-shells-env-run-does-not-hide-an-edit
  (fake-breto!)
  (project! "app" "{:packages [\"kind\"]}")
  (session (str "cd " (path "app"))
           (str "sleep 1.1; echo '{:packages [\"helm\"]}' > .breto.edn; sleep 1.1; touch " (path "envs" "app"))
           "true")
  (is (= [(path "app") (path "app")] (calls))))

(deftest a-failing-env-in-the-next-project-leaves-the-previous-one
  (fake-breto!)
  (project! "one" "{:packages [\"kind\"]}")
  (project! "two" "{:fail true}")
  (is (= (str "PATH=" (path "fake") ":/usr/bin:/bin|PROJECT=")
         (session (str "export XDG_RUNTIME_DIR=" (path)) (str "cd " (path "one")) (str "cd " (path "two"))))))

(deftest a-child-shell-outside-the-project-leaves-it
  (fake-breto!)
  (project! "app" "{:packages [\"kind\"]}")
  (is (= (str "PATH=" (path "fake") ":/usr/bin:/bin|PROJECT=")
         (session (str "cd " (path "app")) (str "unset __breto_project __breto_stamp; cd " (path))))))

(deftest the-hook-runs-under-nounset
  (fake-breto!)
  (project! "app" "{:fail true}")
  (let [{:keys [err]} (process/shell {:out :string :err :string :continue true} "bash" "--norc" "-c"
                                     (str "PATH=" (path "fake") ":/usr/bin:/bin\nsource shell/env-hook.bash\nset -u\n"
                                          "__breto_env\ncd " (path "app") "\n__breto_env\n__breto_env\ncd " (path) "\n__breto_env\n"))]
    (is (not (str/includes? err "unbound variable")) err)))

(deftest the-failure-marker-is-not-at-a-predictable-path
  (fake-breto!)
  (project! "app" "{:fail true}")
  (is (= "unpredictable"
         (str/trim (:out (process/shell {:out :string :err :string} "bash" "--norc" "-c"
                                        (str "unset XDG_RUNTIME_DIR\nPATH=" (path "fake") ":/usr/bin:/bin\nsource shell/env-hook.bash\n"
                                             "cd " (path "app") "\n__breto_env\n"
                                             "[ -n \"$__breto_stamp\" ] && [ \"$__breto_stamp\" != /tmp/breto-hook-$$ ] && echo unpredictable")))))))
