;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.inputs-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.env :as env]
            [breto.inputs :as inputs]
            [breto.project :as project]
            [breto.run :as run]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(def ^:private config {:env-dir nil})

(defn- cfg [] (assoc config :env-dir (path "envs")))

(defn- write! [dir file text]
  (fs/create-dirs (path dir))
  (spit (path dir file) text))

(defn- age!
  "Gives `file` a modification time `seconds` from now."
  [file seconds]
  (fs/set-last-modified-time file (java.nio.file.attribute.FileTime/from
                                   (.plusSeconds (java.time.Instant/now) seconds))))

(defn- current
  "The inputs of the project in `dir`, with `group-files` as the group files it
  draws on."
  [dir & group-files]
  (inputs/of-project {:dir (path dir) :files (into (project/files (path dir)) group-files)}))

(defn- stale?
  "Whether the sh condition of `recorded`, run by a real shell, finds the
  project changed."
  [recorded stamp]
  (= 0 (:exit @(process/process {:out :string :err :string}
                                "sh" "-c" (str "if " (inputs/stale-test recorded stamp) "; then exit 0; else exit 1; fi")))))

(deftest both-project-files-are-inputs-whether-they-exist-or-not
  (testing "only the shared file"
    (write! "a" ".breto.edn" "{}")
    (is (= {:present [(path "a" ".breto.edn")] :absent [(path "a" ".breto.local.edn")]} (current "a"))))
  (testing "only the local file"
    (write! "b" ".breto.local.edn" "{}")
    (is (= {:present [(path "b" ".breto.local.edn")] :absent [(path "b" ".breto.edn")]} (current "b"))))
  (testing "both, shared first"
    (write! "c" ".breto.edn" "{}")
    (write! "c" ".breto.local.edn" "{}")
    (is (= {:present [(path "c" ".breto.edn") (path "c" ".breto.local.edn")] :absent []} (current "c"))))
  (testing "group files follow, once each"
    (write! "c" "group.edn" "{}")
    (is (= [(path "c" ".breto.edn") (path "c" ".breto.local.edn") (path "c" "group.edn")]
           (:present (current "c" (path "c" "group.edn") (path "c" "group.edn")))))))

(deftest the-digest-follows-every-input
  (write! "app" ".breto.edn" "{:packages [\"a\"]}")
  (write! "app" "group.edn" "{:members []}")
  (let [recorded (current "app" (path "app" "group.edn"))
        before (inputs/digest recorded)
        unchanged? #(= before (inputs/digest recorded))]
    (testing "rewriting the same content changes nothing"
      (write! "app" ".breto.edn" "{:packages [\"a\"]}")
      (is (unchanged?)))
    (testing "the shared file"
      (write! "app" ".breto.edn" "{:packages [\"b\"]}")
      (is (not (unchanged?)))
      (write! "app" ".breto.edn" "{:packages [\"a\"]}")
      (is (unchanged?)))
    (testing "the local file appearing and going again"
      (write! "app" ".breto.local.edn" "{}")
      (is (not (unchanged?)))
      (fs/delete (path "app" ".breto.local.edn"))
      (is (unchanged?)))
    (testing "a group file"
      (write! "app" "group.edn" "{:members [\"x\"]}")
      (is (not (unchanged?)))
      (write! "app" "group.edn" "{:members []}")
      (is (unchanged?)))
    (testing "the shared file going"
      (fs/delete (path "app" ".breto.edn"))
      (is (not (unchanged?))))))

(deftest the-digest-tells-the-two-project-files-apart
  (write! "one" ".breto.edn" "{}")
  (write! "two" ".breto.local.edn" "{}")
  (is (not= (inputs/digest (current "one")) (inputs/digest (current "two")))))

(deftest the-stamp-records-the-inputs
  (write! "app" ".breto.edn" "{}")
  (let [recorded (current "app")
        c (cfg)]
    (testing "no stamp is not fresh"
      (is (not (inputs/fresh? c (path "app") recorded))))
    (inputs/stamp! c (path "app") recorded)
    (is (inputs/fresh? c (path "app") recorded))
    (testing "no staged file stays behind"
      (is (not (fs/exists? (fs/path (env/env-dir c (path "app")) ".stamp.breto")))))
    (doseq [[what change undo] [["the shared file" #(write! "app" ".breto.edn" "{:x 1}") #(write! "app" ".breto.edn" "{}")]
                                ["the local file" #(write! "app" ".breto.local.edn" "{}") #(fs/delete (path "app" ".breto.local.edn"))]]]
      (testing what
        (change)
        (is (not (inputs/fresh? c (path "app") recorded)))
        (undo)
        (is (inputs/fresh? c (path "app") recorded))))
    (testing "touching renews the time and keeps the digest"
      (age! (inputs/stamp-path c (path "app")) -100)
      (inputs/touch! c (path "app"))
      (is (inputs/fresh? c (path "app") recorded))
      (is (< (- (System/currentTimeMillis)
                (.toMillis (fs/last-modified-time (inputs/stamp-path c (path "app")))))
             60000)))))

(deftest the-shell-test-treats-both-project-files-alike
  (doseq [[recorded-file other-file] [[".breto.edn" ".breto.local.edn"] [".breto.local.edn" ".breto.edn"]]]
    (testing (str "linked from " recorded-file)
      (let [dir (str "p" (hash recorded-file))
            _ (write! dir recorded-file "{}")
            recorded (current dir)
            stamp (path dir "stamp")
            settled! (fn [] (spit stamp "x") (age! stamp 0) (age! (path dir recorded-file) -100))]
        (settled!)
        (testing "nothing happened"
          (is (not (stale? recorded stamp))))
        (testing "it was edited"
          (age! (path dir recorded-file) 100)
          (is (stale? recorded stamp))
          (settled!))
        (testing "it went away"
          (fs/delete (path dir recorded-file))
          (is (stale? recorded stamp))
          (write! dir recorded-file "{}")
          (settled!))
        (testing "the other file appeared, however old"
          (write! dir other-file "{}")
          (age! (path dir other-file) -100)
          (is (stale? recorded stamp))
          (fs/delete (path dir other-file)))
        (testing "the stamp is missing"
          (fs/delete stamp)
          (is (stale? recorded stamp)))))))

(deftest the-shell-test-watches-group-files
  (write! "app" ".breto.edn" "{}")
  (write! "shared" "@g.edn" "{}")
  (let [recorded (current "app" (path "shared" "@g.edn"))
        stamp (path "app" "stamp")]
    (spit stamp "x")
    (age! (path "app" ".breto.edn") -100)
    (age! (path "shared" "@g.edn") -100)
    (is (not (stale? recorded stamp)))
    (age! (path "shared" "@g.edn") 100)
    (is (stale? recorded stamp))
    (age! (path "shared" "@g.edn") -100)
    (fs/delete (path "shared" "@g.edn"))
    (is (stale? recorded stamp))))

(deftest the-shell-test-reads-awkward-paths-literally
  (let [dir "it's $HOME `x` a b"]
    (write! dir ".breto.edn" "{}")
    (let [recorded (current dir)
          stamp (path dir "stamp")]
      (spit stamp "x")
      (age! (path dir ".breto.edn") -100)
      (is (not (stale? recorded stamp)))
      (age! (path dir ".breto.edn") 100)
      (is (stale? recorded stamp)))))

(defn- wrapper-world
  "A tool script for project `app` beside a fake breto recording its arguments,
  with the test condition `test`. Returns a function running the script."
  [test]
  (write! "app" ".breto.edn" "{}")
  (write! "fake" "breto" (str "#!/bin/sh\nfor a in \"$@\"; do echo \"breto:$a\"; done\nexit 9\n"))
  (fs/set-posix-file-permissions (path "fake" "breto") "rwxr-xr-x")
  (write! "t" "tool" "#!/bin/sh\necho \"linked=[${BRETO_LINKED-unset}]\"\nfor a in \"$@\"; do echo \"tool:$a\"; done\nexit 4\n")
  (fs/set-posix-file-permissions (path "t" "tool") "rwxr-xr-x")
  (write! "b" "tool" (run/tool-wrapper (path "app") "tool" (path "t" "tool") test))
  (fs/set-posix-file-permissions (path "b" "tool") "rwxr-xr-x")
  (fn [env & args]
    @(apply process/process {:out :string :err :string
                             :extra-env (merge {"PATH" (str (path "fake") ":/usr/bin:/bin")} env)}
            (path "b" "tool") args)))

(deftest a-tool-script-runs-the-tool-directly-while-nothing-changed
  (let [run! (wrapper-world "false")
        {:keys [out exit]} (run! {} "--flag" "two words")]
    (is (= 4 exit))
    (is (= ["linked=[unset]" "tool:--flag" "tool:two words"] (str/split-lines out)))))

(deftest a-tool-script-hands-over-to-breto-when-something-changed
  (let [run! (wrapper-world "true")
        {:keys [out exit]} (run! {} "--flag" "two words")]
    (is (= 9 exit))
    (is (= ["breto:exec" "breto:--dir" (str "breto:" (path "app")) "breto:tool" "breto:--flag" "breto:two words"]
           (str/split-lines out)))))

(deftest a-tool-script-started-by-breto-does-not-ask-again
  (let [run! (wrapper-world "true")
        {:keys [out exit]} (run! {"BRETO_LINKED" "1"} "x")]
    (is (= 4 exit))
    (testing "and the tool does not inherit the marker"
      (is (= ["linked=[unset]" "tool:x"] (str/split-lines out))))))
