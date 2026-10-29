;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.golang
  "Packages built with `go install` from a module of the Go module proxy. The
  go command checks the module against the checksum database, sum.golang.org
  by default, and its h1: hash is what a lock file records, since the binary
  depends on the Go version that built it."
  (:require [babashka.fs :as fs]
            [breto.package :as package]
            [breto.proc :as proc]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn environment
  "The variables `go` runs with: binaries go to `gobin`, the toolchain is the
  one breto installed, and nothing needs a C compiler. GOFLAGS is emptied so
  flags of the user's own shell cannot change the build."
  [gobin]
  {"GOBIN" (str gobin) "GOTOOLCHAIN" "local" "CGO_ENABLED" "0" "GOFLAGS" ""})

(defn- run! [io go work env args context]
  (let [run (or (:run-in io) proc/run-in)
        {:keys [exit out err]} (run {:command (cons go args) :env env :dir work})]
    (when-not (zero? exit)
      (throw (ex-info (str "go " (str/join " " args) " failed: " (str/trim (str err)))
                      (assoc context :breto/problem :go-failed :command (vec (cons "go" args)) :output (str/trim (str err out))))))
    out))

(defn build!
  "Builds the release of `pkg` with `go`, the path of a go command, into
  `work`, returning {:file :sum} with the binary named as the asset and the
  module's h1: hash. With `expect-sum` from a lock file, a module whose hash
  differs is refused before anything is built."
  [io pkg release artifact work go expect-sum context]
  (when-not go
    (throw (ex-info (str (:name pkg) " is built with go install, and no go is installed; install go first")
                    (assoc context :breto/problem :go-missing))))
  (let [module (get-in pkg [:source :go])
        gobin (fs/path work "gobin")
        env (environment gobin)
        target (str module "@" (:tag release))
        sum (get (json/parse-string (run! io go work env ["mod" "download" "-json" target] context)) "Sum")]
    (when (and expect-sum (not= expect-sum sum))
      (throw (ex-info (str (:name pkg) " " (:version release) ": the module hash " sum " differs from " expect-sum " in the lock file")
                      (assoc context :breto/problem :checksum-mismatch :expected expect-sum :actual sum :against :lockfile))))
    (run! io go work env ["install" "-trimpath" (str (package/go-package pkg) "@" (:tag release))] context)
    (let [built (fs/path gobin (package/go-binary (package/go-package pkg)))
          file (fs/path work (:asset artifact))]
      (when-not (fs/regular-file? built)
        (throw (ex-info (str "go install left no " (fs/file-name built) " in " gobin)
                        (assoc context :breto/problem :path-missing :path (str built)))))
      (fs/move built file)
      {:file file :sum sum})))
