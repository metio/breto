;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.lockfile
  "The lock file: the active version and download hash of each configured
  package, one package per line so changes read well in version control.
  Comment lines at the top of an existing file, such as a license header,
  are kept when it is rewritten. The hash of a download that differs by
  platform is at the top level for linux-x86_64 and under :platforms for the
  others; a package built or resolved on the machine has one for all."
  (:require [babashka.fs :as fs]
            [breto.package :as package]
            [breto.platform :as platform]
            [breto.store :as store]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- split-header
  "The leading comment and blank lines of `text`, and the rest."
  [text]
  (let [lines (str/split-lines (or text ""))
        header (take-while #(or (str/blank? %) (str/starts-with? (str/triml %) ";")) lines)]
    [(str/join "\n" header) (str/join "\n" (drop (count header) lines))]))

(defn parse
  "{:header text :entries {name entry}} from the text of a lock file."
  [text]
  (let [[header body] (split-header text)
        entries (if (str/blank? body) {} (edn/read-string body))]
    (when-not (map? entries)
      (throw (ex-info "the lock file does not hold a map" {:breto/problem :lockfile-format})))
    {:header header :entries entries}))

(defn read-lock
  "The lock file at `path`; a missing file is an empty one."
  [path]
  (if (fs/exists? path)
    (try (parse (slurp (str path)))
         (catch clojure.lang.ExceptionInfo e (throw (ex-info (ex-message e) (assoc (ex-data e) :path (str path)))))
         (catch RuntimeException e
           (throw (ex-info (str "the lock file " path " is not valid EDN: " (ex-message e))
                           {:breto/problem :lockfile-format :path (str path) :error (ex-message e)}))))
    {:header "" :entries {}}))

(defn- shared? [pkg]
  (or (nil? pkg) (contains? package/neutral-kinds (package/source-kind pkg))))

(defn hashes
  "The keys of `entry` that pin the download of `pkg` on the platform it was
  resolved for, or nil."
  [entry pkg]
  (if (or (shared? pkg) (= platform/default (:platform pkg)))
    (not-empty (dissoc entry :platforms))
    (get-in entry [:platforms (:platform pkg)])))

(defn with-hashes
  "`entry` with `hashes` as the keys pinning the download of `pkg` on its
  platform, keeping those of the other platforms."
  [entry pkg hashes]
  (if (or (shared? pkg) (= platform/default (:platform pkg)))
    (merge (select-keys entry [:version :platforms]) hashes)
    (assoc-in (or entry {}) [:platforms (:platform pkg)] hashes)))

(defn- fields [{:keys [version sha256 sum asset exclude-newer]}]
  (str/join " " (remove nil? [(when version (str ":version " (pr-str version)))
                              (when sha256 (str ":sha256 " (pr-str sha256)))
                              (when sum (str ":sum " (pr-str sum)))
                              (when asset (str ":asset " (pr-str asset)))
                              (when exclude-newer (str ":exclude-newer " (pr-str exclude-newer)))])))

(defn- entry-line [name {:keys [platforms] :as entry}]
  (str (pr-str name) " {"
       (str/join " " (remove str/blank?
                             [(fields entry)
                              (when (seq platforms)
                                (str ":platforms {"
                                     (str/join " " (for [[p hashes] (sort-by key platforms)]
                                                     (str (pr-str p) " {" (fields hashes) "}")))
                                     "}"))]))
       "}"))

(defn render [{:keys [header entries]}]
  (let [lines (map (fn [[name entry]] (entry-line name entry)) (sort-by key entries))]
    (str (when-not (str/blank? header) (str header "\n"))
         "{" (str/join "\n " lines) "}\n")))

(defn snapshot
  "The entries for the configured `names` given the store: the active version
  of each with the hash, or module sum, recorded when it was installed,
  placed for the platform `registry` resolves its package for. A package not
  active here keeps its old entry, and so does the hash of a version
  installed before hashes were recorded. Another platform's hashes stay
  while the version does."
  ([config names old] (snapshot config nil names old))
  ([config registry names old]
   (into {}
         (keep (fn [name]
                 (let [active (store/current-version config name)
                       previous (get old name)
                       pkg (get registry name)]
                   (if active
                     (let [{:keys [sha256 sum asset exclude-newer]} (store/read-meta config name active)
                           kept-entry (when (= active (:version previous)) previous)
                           kept (hashes kept-entry pkg)]
                       [name (with-hashes (assoc (or kept-entry {}) :version active) pkg
                               (cond-> {}
                                 (or sha256 (:sha256 kept)) (assoc :sha256 (or sha256 (:sha256 kept)))
                                 (or sum (:sum kept)) (assoc :sum (or sum (:sum kept)))
                                 (or asset (:asset kept)) (assoc :asset (or asset (:asset kept)))
                                 (or exclude-newer (:exclude-newer kept)) (assoc :exclude-newer (or exclude-newer (:exclude-newer kept)))))])
                     (when previous [name previous])))))
         names)))

(defn write!
  "Rewrites the lock file at `path` from the store, keeping its header. The
  file is replaced in one rename, and left alone when its content would not
  change, so a command that changed nothing leaves no trace in a checkout.
  Returns whether it wrote the file."
  ([path config] (write! path config nil))
  ([path config registry]
   (let [{:keys [header entries]} (read-lock path)
         text (render {:header header :entries (snapshot config registry (:packages config) entries)})
         staged (str path ".tmp")]
     (when-not (and (fs/exists? path) (= text (slurp (str path))))
       (fs/create-dirs (fs/parent (fs/absolutize path)))
       (spit staged text)
       (fs/move staged path {:replace-existing true :atomic-move true})
       true))))

(defn locked-entry
  "The entry of `name` for an install from the lock file at `path`."
  [path name]
  (when-not (fs/exists? path)
    (throw (ex-info (str "there is no lock file at " path)
                    {:breto/problem :no-lockfile :path (str path)})))
  (or (get (:entries (read-lock path)) name)
      (throw (ex-info (str name " is not in the lock file " path)
                      {:breto/problem :not-locked :package name :path (str path)}))))
