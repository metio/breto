;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.config
  (:require [babashka.fs :as fs]
            [breto.edn-text :as edn-text]
            [briko.paths :as paths]
            [clojure.string :as str]))

(defn default-path
  "BRETO_CONFIG, else config.edn in breto's directory below XDG_CONFIG_HOME."
  []
  (let [explicit (System/getenv "BRETO_CONFIG")]
    (if (str/blank? explicit)
      (str (fs/path (paths/config-home) "breto" "config.edn"))
      explicit)))

(defn default-bin-dir
  "Of ~/.local/bin and ~/bin, the one `path` lists first, else ~/.local/bin:
  a directory the user owns, which no other tool fills."
  [home path]
  (let [candidates (map #(str (fs/path home %)) [".local/bin" "bin"])
        entries (map #(str/replace % #"/+$" "") (str/split (or path "") #":"))]
    (or (first (filter (set candidates) entries))
        (first candidates))))

(defn desktop-dir
  "Where desktop entries are linked on the operating system `os`, the
  os.name property: the applications directory below XDG_DATA_HOME on Linux
  and the BSDs, and none elsewhere, since macOS and Windows read no desktop
  entries."
  [os]
  (when (re-find #"(?i)linux|bsd" (str os))
    (str (fs/path (paths/data-home) "applications"))))

(defn defaults []
  {:store (str (fs/path (paths/data-home) "breto" "installations"))
   :bin-dir (default-bin-dir (paths/home) (System/getenv "PATH"))
   :registries [{:github "metio/packages"}]
   :extra-registries []
   :packages []
   :pins {}
   :keep 3
   :prune false
   :projects {}
   :token-command nil
   :lockfile nil
   :require-locked false
   :cosign nil
   :hooks {}
   :notifier :auto
   :messages {}
   :hook-timeout 30
   :finder :auto
   :env-dir (str (fs/path (paths/data-home) "breto" "envs"))
   :python "3.13"
   :python-dir (str (fs/path (paths/data-home) "breto" "python"))
   :man-dir (str (fs/path (paths/data-home) "man"))
   :completion-dirs {:bash (str (fs/path (paths/data-home) "bash-completion" "completions"))
                     :zsh (str (fs/path (paths/data-home) "zsh" "site-functions"))
                     :fish (str (fs/path (paths/config-home) "fish" "completions"))}
   :desktop-dir (desktop-dir (System/getProperty "os.name"))
   :desktop-entries true})

(defn load-config
  "Reads the EDN config at `path` over the defaults. A missing file means all
  defaults. :extra-registries are read after :registries, so a config adds
  to the default registries with them and replaces the defaults with
  :registries."
  [path]
  (let [user (when (fs/exists? path) (edn-text/read-file path))]
    (-> (merge (defaults) user)
        (as-> config (-> config
                         (update :registries #(vec (concat % (:extra-registries config))))
                         (dissoc :extra-registries)))
        (update :store paths/expand-home)
        (update :bin-dir paths/expand-home)
        (update :lockfile #(some-> % paths/expand-home))
        (update :cosign #(some-> % paths/expand-home))
        (update :env-dir paths/expand-home)
        (update :python-dir paths/expand-home)
        (update :man-dir #(some-> % paths/expand-home))
        (update :completion-dirs #(update-vals % paths/expand-home))
        (update :desktop-dir #(some-> % paths/expand-home))
        (update :registries #(mapv (fn [entry] (if (string? entry) (paths/expand-home entry) entry)) %)))))
