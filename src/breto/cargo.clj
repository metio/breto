;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.cargo
  "Packages built with `cargo install` from a crate on crates.io. breto
  downloads the crate and checks it against the checksum the crates.io index
  lists, like any other asset, and its hash is what a lock file records. cargo
  builds it with --locked, so every dependency is the one the crate's own
  Cargo.lock pins and is checked against the index by cargo.

  A crate that is only published as a GitHub repository is built from the
  newest commit of its default branch. Its version is the commit's date
  followed by the commit, so versions order by date and each names the exact
  source it was built from."
  (:require [babashka.fs :as fs]
            [breto.proc :as proc]
            [clojure.string :as str]))

(def commit-match
  "The pattern that takes the commit out of the version of a git build."
  "\\+([0-9a-f]{40})$")

(defn head-url
  "The GitHub API document of the newest commit on the default branch of
  `repo`."
  [repo]
  (str "https://api.github.com/repos/" repo "/commits/HEAD"))

(defn archive-url
  "The source archive of `repo` at a commit, with `{commit}` left as a
  placeholder."
  [repo]
  (str "https://github.com/" repo "/archive/{commit}.tar.gz"))

(defn git-version
  "The version of the build of commit `sha`, committed at the ISO 8601 UTC
  time `date`, such as 2026.10.27.142816+<sha>."
  [sha date]
  (let [[_ y m d time] (re-matches #"(\d{4})-(\d{2})-(\d{2})T(\d{2}:\d{2}:\d{2})Z" date)]
    (when-not (and y (re-matches #"[0-9a-f]{40}" (str sha)))
      (throw (ex-info (str "no commit and UTC date in " (pr-str {:sha sha :date date}))
                      {:breto/problem :no-release :sha sha :date date})))
    (str y "." (parse-long m) "." (parse-long d) "." (str/replace time ":" "") "+" sha)))

(defn index-url
  "The file of the crate `name` in the sparse crates.io index, which lists one
  JSON document per published version."
  [name]
  (let [n (str/lower-case name)]
    (str "https://index.crates.io/"
         (case (count n)
           1 "1/"
           2 "2/"
           3 (str "3/" (subs n 0 1) "/")
           (str (subs n 0 2) "/" (subs n 2 4) "/"))
         n)))

(defn download-url
  "The download location of the crate `name`, with `{asset}` left as a
  placeholder."
  [name]
  (str "https://static.crates.io/crates/" name "/{asset}"))

(defn build!
  "Builds the crate unpacked at `source` with the cargo proxy of `rustup`, the
  path of a rustup binary, into `work`/built, whose bin directory then holds
  the crate's binaries. rustup acts as the proxy its file is called by, so it
  runs through a link named cargo, from `work`, where no rust-toolchain.toml
  can select another toolchain than the default."
  [io pkg release source work rustup context]
  (when-not rustup
    (throw (ex-info (str (:name pkg) " is built with cargo install, and no rustup is installed; install rustup first")
                    (assoc context :breto/problem :cargo-missing))))
  (let [bin (fs/path work "rustup-bin")
        cargo (fs/path bin "cargo")
        root (fs/path work "built")
        args ["install" "--path" (str source) "--locked" "--root" (str root) "--no-track"
              "--target-dir" (str (fs/path work "target")) "--quiet"]
        run (or (:run-in io) proc/run-in)]
    (fs/create-dirs bin)
    (fs/create-sym-link cargo rustup)
    (let [{:keys [exit out err]} (run {:command (cons (str cargo) args)
                                       :env {"PATH" (str bin (some->> (System/getenv "PATH") (str ":")))}
                                       :dir work})]
      (when-not (zero? exit)
        (throw (ex-info (str "cargo install of " (get-in pkg [:source :cargo]) " " (:version release) " failed: " (str/trim (str err)))
                        (assoc context :breto/problem :cargo-failed :command (vec (cons "cargo" args))
                               :output (str/trim (str err out)))))))
    root))
