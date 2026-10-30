;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.npm
  "Packages installed with npm from the npm registry into a directory of
  their own. npm checks every tarball against the integrity hash the registry
  publishes for it and records the resolution in package-lock.json; the hash
  of that file is what a lock file records. Resolving again with the same
  cut-off time, --before, and the same npm gives the same file, since the
  registry never replaces a published version. Each build starts from an
  empty cache of its own, so no earlier resolution on the host can leak into
  it."
  (:require [babashka.fs :as fs]
            [breto.proc :as proc]
            [clojure.string :as str]))

(def registry "https://registry.npmjs.org/")

(defn registry-url
  "The registry document of the package `name`, with the scope separator of a
  scoped name escaped."
  [name suffix]
  (str registry (str/replace name "/" "%2F") suffix))

(defn arguments
  "The arguments of `npm install` for `spec` into `prefix`. They come before
  any configuration of the user's own: npm reads neither the user's nor the
  global npmrc, reading the empty files userrc and globalrc in `config`
  instead, keeps its cache in `cache`, runs no install scripts, and considers
  only versions published before `before`. npm refuses to load one file as
  both."
  [spec prefix config cache before]
  ["install" "--prefix" (str prefix) "--registry" registry
   "--userconfig" (str (fs/path config "userrc")) "--globalconfig" (str (fs/path config "globalrc"))
   "--cache" (str cache)
   "--before" before "--ignore-scripts" "--save-exact" "--omit=dev"
   "--no-audit" "--no-fund" "--update-notifier=false" "--loglevel=error"
   spec])

(defn- configuration-variable?
  "Whether the environment variable `name` configures npm, which reads every
  npm_config_ variable in any case as a setting."
  [name]
  (str/starts-with? (str/lower-case name) "npm_config_"))

(defn build!
  "Installs the release of `pkg` with `npm`, the path of an npm command whose
  directory also holds `node`, into `work`/unpacked, returning {:file
  :unpacked :exclude-newer}: the lock file npm wrote, the directory to place,
  and the cut-off it was resolved with, `exclude-newer` when a lock file gave
  one."
  [io pkg release work npm exclude-newer now context]
  (when-not npm
    (throw (ex-info (str (:name pkg) " is installed with npm, and no npm is installed; install node first")
                    (assoc context :breto/problem :npm-missing))))
  (let [before (or exclude-newer now)
        unpacked (fs/path work "unpacked")
        config (fs/path work "npmrc")
        spec (str (get-in pkg [:source :npm]) "@" (:version release))
        args (arguments spec unpacked config (fs/path work "npm-cache") before)
        run (or (:run-in io) proc/run-in)
        path (str (fs/parent npm) (some->> (System/getenv "PATH") (str ":")))
        unset (filter configuration-variable? (keys (System/getenv)))]
    (fs/create-dirs unpacked)
    (fs/create-dirs config)
    (run! #(spit (str (fs/path config %)) "") ["userrc" "globalrc"])
    (let [{:keys [exit out err]} (run {:command (cons npm args) :env {"PATH" path} :unset unset :dir work})]
      (when-not (zero? exit)
        (throw (ex-info (str "npm install " spec " failed: " (str/trim (str err)))
                        (assoc context :breto/problem :npm-failed :command (vec (cons "npm" args))
                               :output (str/trim (str err out)))))))
    {:file (fs/path unpacked "package-lock.json") :unpacked unpacked :exclude-newer before}))
