;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.pypi
  "Packages installed with uv from a project on PyPI into a virtual
  environment of their own. uv resolves the project and every dependency
  into a requirements file pinning each file by its hash; the hash of that
  file is what a lock file records. Resolving again with the same cut-off
  time, --exclude-newer, gives the same file, since PyPI never replaces a
  published file."
  (:require [babashka.fs :as fs]
            [breto.proc :as proc]
            [clojure.string :as str])
  (:import (java.time Instant)
           (java.time.temporal ChronoUnit)))

(defn environment
  "The variables uv runs with: no uv configuration of the user's own, and
  only Pythons uv downloads itself, kept in `python-dir` and never linked
  into the user's bin directory."
  [python-dir]
  {"UV_NO_CONFIG" "1"
   "UV_PYTHON_PREFERENCE" "only-managed"
   "UV_PYTHON_DOWNLOADS" "automatic"
   "UV_PYTHON_INSTALL_DIR" (str python-dir)
   "UV_PYTHON_INSTALL_BIN" "0"})

(defn now
  "The current time in seconds, the cut-off of a resolution no lock fixes."
  []
  (str (.truncatedTo (Instant/now) ChronoUnit/SECONDS)))

(defn- run! [io uv work env args context]
  (let [run (or (:run-in io) proc/run-in)
        {:keys [exit out err]} (run {:command (cons uv args) :env env :dir work})]
    (when-not (zero? exit)
      (throw (ex-info (str "uv " (str/join " " args) " failed: " (str/trim (str err)))
                      (assoc context :breto/problem :uv-failed :command (vec (cons "uv" args)) :output (str/trim (str err out))))))
    out))

(defn build!
  "Installs the release of `pkg` with `uv`, the path of a uv command, into a
  relocatable virtual environment at `work`/unpacked/venv for Python
  `python`, returning {:file :unpacked :exclude-newer}: the hash-pinned
  requirements, the directory to place, and the cut-off they were resolved
  with, `exclude-newer` when a lock file gave one."
  [io pkg release work uv python python-dir exclude-newer context]
  (when-not uv
    (throw (ex-info (str (:name pkg) " is installed with uv, and no uv is installed; install uv first")
                    (assoc context :breto/problem :uv-missing))))
  (let [env (environment python-dir)
        exclude-newer (or exclude-newer (now))
        input (fs/path work "input.txt")
        requirements (fs/path work "requirements.txt")
        unpacked (fs/path work "unpacked")]
    (spit (str input) (str (get-in pkg [:source :pypi]) "==" (:version release) "\n"))
    (run! io uv work env ["pip" "compile" "--quiet" "--universal" "--generate-hashes" "--no-header" "--no-annotate"
                          "--python-version" python "--exclude-newer" exclude-newer
                          (str input) "--output-file" (str requirements)] context)
    (run! io uv work env ["venv" "--quiet" "--relocatable" "--python" python (str (fs/path unpacked "venv"))] context)
    (run! io uv work env ["pip" "install" "--quiet" "--python" (str (fs/path unpacked "venv"))
                          "--require-hashes" "--no-deps" "--requirement" (str requirements)] context)
    {:file requirements :unpacked unpacked :exclude-newer exclude-newer}))
