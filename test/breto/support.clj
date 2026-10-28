;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.support
  "A temporary home for a test and a fake network, so whole commands run
  without touching the user's files or the internet."
  (:require [babashka.fs :as fs]
            [breto.checksum :as checksum]
            [breto.main :as main]
            [breto.report :as report]))

(def ^:dynamic *root* nil)

(defn with-root
  "A fixture binding *root* to a fresh directory, and the saved reports into it."
  [t]
  (let [root (fs/create-temp-dir {:prefix "breto-test-"})]
    (try (binding [*root* root report/*state-home* (str (fs/path root "state"))] (t))
         (finally (fs/delete-tree root)))))

(defn path [& parts]
  (str (apply fs/path *root* parts)))

(defn define!
  "Writes the definition of package `name` into the test registry."
  [name definition]
  (fs/create-dirs (path "packages"))
  (spit (path "packages" (str name ".edn")) (pr-str definition)))

(defn file!
  "Writes `content` to a file under the test root and returns its path."
  [name content]
  (fs/create-dirs (path "files"))
  (spit (path "files" name) content)
  (path "files" name))

(defn sha256 [file]
  (checksum/digest "SHA-256" file))

(defn fake-io
  "Serves `files` (URL to local file) for downloads and `texts` (URL to body,
  or a function of the headers) for everything else; any other URL is a 404.
  `requests` collects every URL asked for."
  [files texts requests]
  {:download! (fn [url dest & [on-bytes]]
                (swap! requests conj url)
                (if-let [file (get files url)]
                  (do (fs/copy file dest {:replace-existing true})
                      (when on-bytes (on-bytes (fs/size file) (fs/size file))))
                  (throw (ex-info (str "GET " url " returned 404")
                                  {:breto/problem :http-status :url url :status 404 :headers {}}))))
   :get-text (fn [url headers]
               (swap! requests conj url)
               (let [body (get texts url)]
                 (cond
                   (fn? body) (body headers)
                   body body
                   :else (throw (ex-info (str "GET " url " returned 404")
                                         {:breto/problem :http-status :url url :status 404 :headers {}})))))
   :exists? (fn [url]
              (swap! requests conj url)
              (or (contains? files url) (contains? texts url)))
   :upstream-wait (constantly 0)
   :github-token (constantly nil)
   :which (constantly nil)})

(defn breto!
  "Runs breto with `args` against the test registry, store and bin directory,
  `config` merged into the configuration and `overrides` into the context.
  Returns the exit code, printed output and progress events."
  ([config args] (breto! config args {}))
  ([config args overrides]
   (spit (path "config.edn") (pr-str (merge {:store (path "store") :bin-dir (path "bin")
                                             :registries [(path "packages")]}
                                            config)))
   (let [out (java.io.StringWriter.)
         events (atom [])
         slept (atom 0)
         code (binding [*out* out]
                (main/run (concat args ["--config" (path "config.edn")])
                          (merge {:io (fake-io {} {} (atom []))
                                  :reporter (fn [_] #(swap! events conj %))
                                  :sleep-second! #(swap! slept inc)}
                                 overrides)))]
     {:code code :out (str out) :events @events :slept @slept})))
