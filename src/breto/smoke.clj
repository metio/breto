;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.smoke
  "Runs the :tests of a definition against an installed version: commands
  that must exit 0, and optionally print a given text, to show that the
  release works on this platform."
  (:require [babashka.fs :as fs]
            [breto.install :as install]
            [breto.package :as package]
            [breto.template :as template]
            [clojure.string :as str]))

(def default-timeout
  "Seconds a test may run before it is stopped."
  60)

(defn normalize
  "A test as {:args [...] :timeout N :env {...} :expect TEXT}; a bare vector is
  its arguments."
  [test]
  (if (map? test) test {:args test}))

(defn- command-line
  "The arguments of `test` with the placeholders filled, the first one
  replaced by the file of the installed version when it names one of the
  package's commands, so the test runs that version and nothing on PATH."
  [targets values {:keys [args]}]
  (let [[program & more] (map #(template/render % values) args)]
    (into [(get targets program program)] more)))

(defn- failed! [pkg version test argv {:keys [exit out err timed-out]} reason]
  (throw (ex-info (str (:name pkg) " " version ": " (str/join " " (:args test)) " " reason)
                  {:breto/problem :test-failed :stage :test :package (:name pkg) :version version
                   :args (:args test) :command argv :exit exit :timed-out timed-out
                   :expect (:expect test) :output (str out err)})))

(defn run-tests
  "Runs every test of `pkg` at the installed `version` in a fresh directory,
  with `bin-dir` first on PATH, and returns how many ran. The first failing
  test throws. `run` runs {:command :env :timeout :dir} and returns {:exit :out
  :err :timed-out}."
  [config pkg version run]
  (let [pkg (package/at pkg version)
        tests (map normalize (:tests pkg []))
        targets (install/bin-targets config pkg version)
        values (package/template-vars pkg {:tag (package/tag-of-version pkg version) :version version})
        path (str (:bin-dir config) java.io.File/pathSeparator (System/getenv "PATH"))
        dir (fs/create-temp-dir {:prefix "breto-test-"})]
    (try
      (doseq [test tests]
        (let [argv (command-line targets values test)
              result (try (run {:command argv
                                :env (merge (update-vals (:env test {}) #(template/render % values)) {"PATH" path})
                                :timeout (:timeout test default-timeout)
                                :dir (str dir)})
                          (catch java.io.IOException e
                            (failed! pkg version test argv {} (str "could not start: " (ex-message e)))))
              output (str (:out result) (:err result))
              expect (some-> (:expect test) (template/render values))]
          (cond
            (:timed-out result) (failed! pkg version test argv result (str "ran longer than " (:timeout test default-timeout) " seconds"))
            (not= 0 (:exit result)) (failed! pkg version test argv result (str "exited with " (:exit result)))
            (and expect (not (str/includes? output expect))) (failed! pkg version test argv result (str "did not print " (pr-str expect))))))
      (count tests)
      (finally (fs/delete-tree dir)))))
