;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.install-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.checksum :as checksum]
            [breto.install :as install]
            [breto.package :as package]
            [breto.store :as store]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *root* nil)

(use-fixtures :each
  (fn [t]
    (let [root (fs/create-temp-dir {:prefix "breto-test-"})]
      (try (binding [*root* root] (t))
           (finally (fs/delete-tree root))))))

(defn- config []
  {:store (str (fs/path *root* "store")) :bin-dir (str (fs/path *root* "bin"))})

(defn- tarball!
  "Builds `name` holding `files` (path to content) under a top-level directory."
  [name files]
  (let [src (fs/path *root* "src" "release")
        out (fs/path *root* "assets" name)]
    (doseq [[path content] files]
      (fs/create-dirs (fs/parent (fs/path src path)))
      (spit (str (fs/path src path)) content))
    (fs/create-dirs (fs/parent out))
    (process/shell "tar" "--create" "--gzip" "--file" (str out)
                   "--directory" (str (fs/parent src)) "release")
    (fs/delete-tree (fs/path *root* "src"))
    out))

(defn- fake-io
  "Serves `assets` (URL to local file) and `texts` (URL to body)."
  [assets texts]
  {:download! (fn [url dest]
                (fs/copy (or (get assets url) (throw (ex-info (str "no asset " url) {})))
                         dest))
   :get-text (fn [url _]
               (or (get texts url) (throw (ex-info (str "no text " url) {}))))})

(def ^:private tool
  (package/normalize {:name "tool" :source {:github "acme/tool"}
                      :asset "tool-{version}.tar.gz" :strip 1
                      :checksum "SHA256SUMS"}))

(defn- release-url [version file]
  (str "https://github.com/acme/tool/releases/download/v" version "/" file))

(defn- serve-tool [& versions]
  (let [assets (into {} (for [v versions]
                          [(release-url v (str "tool-" v ".tar.gz"))
                           (tarball! (str "tool-" v ".tar.gz") {"tool" (str "#!/bin/sh\necho " v "\n")})]))
        sums (into {} (for [v versions]
                        [(release-url v "SHA256SUMS")
                         (str (checksum/digest "SHA-256" (get assets (release-url v (str "tool-" v ".tar.gz"))))
                              "  tool-" v ".tar.gz\n")]))]
    (fake-io assets (assoc sums "https://github.com/acme/tool/releases/latest"
                           (str "{\"tag_name\":\"v" (last versions) "\"}")))))

(deftest install-latest-places-links-and-activates
  (let [cfg (config)
        io (serve-tool "1.0.0" "1.1.0")
        result (install/install! cfg io tool {})]
    (is (= {:version "1.1.0" :installed? true :verified? true} (dissoc result :sha256)))
    (is (= "1.1.0" (store/current-version cfg "tool")))
    (is (fs/executable? (fs/path (:bin-dir cfg) "tool")))
    (is (= "#!/bin/sh\necho 1.1.0\n" (slurp (str (fs/path (:bin-dir cfg) "tool")))))))

(deftest install-is-idempotent
  (let [cfg (config)
        io (serve-tool "1.0.0")]
    (install/install! cfg io tool {})
    (is (= {:version "1.0.0" :installed? false :verified? false}
           (install/install! cfg (assoc io :download! (fn [& _] (throw (ex-info "downloaded again" {}))))
                             tool {})))))

(deftest install-rejects-a-checksum-mismatch
  (let [cfg (config)
        io (serve-tool "1.0.0")
        io (update io :get-text (fn [f] (fn [url h]
                                          (if (= url (release-url "1.0.0" "SHA256SUMS"))
                                            (str (apply str (repeat 64 "0")) "  tool-1.0.0.tar.gz")
                                            (f url h)))))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"checksum mismatch"
                          (install/install! cfg io tool {})))
    (is (empty? (store/versions cfg "tool")))))

(deftest use-switches-between-installed-versions
  (let [cfg (config)
        io (serve-tool "1.0.0" "1.1.0")]
    (install/install! cfg io tool {:version "1.0.0"})
    (install/install! cfg io tool {})
    (install/use! cfg tool "1.0.0")
    (is (= "1.0.0" (store/current-version cfg "tool")))
    (is (= "#!/bin/sh\necho 1.0.0\n" (slurp (str (fs/path (:bin-dir cfg) "tool")))))
    (testing "an unknown version is refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not installed"
                            (install/use! cfg tool "9.9.9"))))))

(deftest several-links-share-one-binary
  (let [cfg (config)
        wrapper (package/normalize {:name "wrapper" :source {:github "acme/wrapper"} :tag-prefix ""
                                    :asset "wrapper.tar.gz" :strip 1
                                    :bins {"wrapper" "wrapper" "alias" "wrapper"}})
        io (fake-io {"https://github.com/acme/wrapper/releases/download/1.0.0/wrapper.tar.gz"
                     (tarball! "wrapper.tar.gz" {"wrapper" "w"})}
                    {"https://github.com/acme/wrapper/releases/latest" "{\"tag_name\":\"1.0.0\"}"})]
    (install/install! cfg io wrapper {:verify? false})
    (is (= ["wrapper"] (map (comp str fs/file-name) (fs/list-dir (fs/path (:store cfg) "wrapper" "1.0.0")))))
    (is (= "w" (slurp (str (fs/path (:bin-dir cfg) "alias")))))
    (is (= "alias" (str (fs/file-name (fs/path (:bin-dir cfg) "alias")))))))

(deftest tree-layout-keeps-the-archive-and-extra-links
  (let [cfg (config)
        editor (package/normalize {:name "editor" :source {:github "acme/editor"}
                                   :asset "editor.tar.gz" :strip 1 :layout :tree
                                   :bins {"ed" "bin/ed"}
                                   :links {(str (fs/path *root* "runtime")) "runtime"}})
        io (fake-io {"https://github.com/acme/editor/releases/download/v2.0.0/editor.tar.gz"
                     (tarball! "editor.tar.gz" {"bin/ed" "ed" "runtime/themes/dark.toml" "dark"})}
                    {"https://github.com/acme/editor/releases/latest" "{\"tag_name\":\"v2.0.0\"}"})]
    (install/install! cfg io editor {:verify? false})
    (is (fs/executable? (fs/path (:bin-dir cfg) "ed")))
    (is (= "dark" (slurp (str (fs/path *root* "runtime" "themes" "dark.toml")))))))

(deftest gc-keeps-the-newest-and-the-active-version
  (let [versions [{:version "4"} {:version "3"} {:version "2"} {:version "1"}]]
    (is (= ["2" "1"] (store/gc-candidates versions "4" 2)))
    (is (= ["2"] (store/gc-candidates versions "1" 2)))
    (is (empty? (store/gc-candidates versions "4" 5)))))

(deftest strip-counts-a-leading-dot-directory
  (let [cfg (config)
        src (fs/path *root* "dotted")
        out (fs/path *root* "assets" "dotted.tar.gz")
        dotted (package/normalize {:name "dotted" :source {:github "acme/dotted"}
                                   :asset "dotted.tar.gz" :strip 1})]
    (fs/create-dirs src)
    (spit (str (fs/path src "dotted")) "d")
    (fs/create-dirs (fs/parent out))
    (process/shell "tar" "--create" "--gzip" "--file" (str out) "--directory" (str src) ".")
    (install/install! cfg (fake-io {"https://github.com/acme/dotted/releases/download/v1.0.0/dotted.tar.gz" out}
                                   {"https://github.com/acme/dotted/releases/latest" "{\"tag_name\":\"v1.0.0\"}"})
                      dotted {:verify? false})
    (is (= "d" (slurp (str (fs/path (:bin-dir cfg) "dotted")))))))

(defn- api-release [repo tag assets]
  [(str "https://api.github.com/repos/" repo "/releases/tags/" tag)
   (json/generate-string {:tag_name tag :assets assets})])

(def ^:private plain
  (package/normalize {:name "plain" :source {:github "acme/plain"} :asset "plain.tar.gz" :strip 1}))

(defn- serve-plain [digest]
  (let [file (tarball! "plain.tar.gz" {"plain" "p"})
        digest (if (= :actual digest) (str "sha256:" (checksum/digest "SHA-256" file)) digest)]
    (fake-io {"https://github.com/acme/plain/releases/download/v1.0.0/plain.tar.gz" file}
             (into {"https://github.com/acme/plain/releases/latest" "{\"tag_name\":\"v1.0.0\"}"}
                   [(api-release "acme/plain" "v1.0.0" [{:name "plain.tar.gz" :digest digest}])]))))

(deftest github-digests-verify-without-a-checksum-file
  (let [cfg (config)]
    (is (:installed? (install/install! cfg (serve-plain :actual) plain {})))))

(deftest github-digest-mismatches-are-rejected
  (let [cfg (config)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"checksum mismatch"
                          (install/install! cfg (serve-plain (str "sha256:" (apply str (repeat 64 "0")))) plain {})))
    (is (empty? (store/versions cfg "plain")))))

(deftest downloads-without-any-checksum-need-no-verify
  (let [cfg (config)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--no-verify"
                          (install/install! cfg (serve-plain nil) plain {})))
    (is (:installed? (install/install! cfg (serve-plain nil) plain {:verify? false})))))

(deftest github-api-failures-fail-closed-without-a-checksum-file
  (let [cfg (config)
        io (update (serve-plain :actual) :get-text
                   (fn [f] (fn [url h]
                             (if (re-find #"api.github.com" url)
                               (throw (ex-info "GET returned 403" {:status 403}))
                               (f url h)))))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"403"
                          (install/install! cfg io plain {})))))

(deftest history-selects-the-definition-of-older-versions
  (let [cfg (config)
        moved (package/normalize {:name "moved" :source {:github "new/moved"} :tag-prefix ""
                                  :asset "moved-{version}.tar.gz" :strip 1 :checksum "SUMS"
                                  :history [{:before "2.0" :source {:github "old/moved"}
                                             :asset "moved.tgz"}]})
        file (tarball! "moved.tgz" {"moved" "m"})
        io (fake-io {"https://github.com/old/moved/releases/download/1.5/moved.tgz" file}
                    {"https://github.com/old/moved/releases/download/1.5/SUMS"
                     (str (checksum/digest "SHA-256" file) "  moved.tgz")})]
    (is (= {:version "1.5" :installed? true :verified? true} (dissoc (install/install! cfg io moved {:version "1.5"}) :sha256)))
    (is (= "m" (slurp (str (fs/path (:bin-dir cfg) "moved")))))))

(deftest rolling-releases-install-the-newest-build
  (let [cfg (config)
        nightly (package/normalize {:name "nightly" :source {:github "acme/nightly" :tag "nightly"}
                                    :asset "nightly-{version}.tar.gz" :strip 1
                                    :layout :tree :bins {}})
        file (tarball! "nightly-20261027+bbb.tar.gz" {"nightly.sh" "n"})
        io (fake-io {"https://github.com/acme/nightly/releases/download/nightly/nightly-20261027+bbb.tar.gz" file}
                    (into {} [(api-release "acme/nightly" "nightly"
                                           [{:name "nightly-20261027+bbb.tar.gz" :created_at "2026-10-27T12:00:00Z"
                                             :digest (str "sha256:" (checksum/digest "SHA-256" file))}
                                            {:name "nightly-20261027+aaa.tar.gz" :created_at "2026-10-27T08:00:00Z"}
                                            {:name "nightly.tar.gz" :created_at "2026-10-27T12:00:01Z"}])]))]
    (is (= {:version "20261027+bbb" :installed? true :verified? true} (dissoc (install/install! cfg io nightly {}) :sha256)))
    (is (= "n" (slurp (str (fs/path (:store cfg) "nightly" "current" "nightly.sh")))))))

(def ^:private raw-tool
  (package/normalize {:name "kind" :source {:github "kubernetes-sigs/kind"}
                      :asset "kind-linux-amd64" :checksum "{asset}.sha256sum"}))

(defn- stored-version! [cfg name version files]
  (doseq [[file content] files]
    (let [path (fs/path (:store cfg) name version file)]
      (fs/create-dirs (fs/parent path))
      (spit (str path) content))))

(deftest present-versions-are-activated-without-a-download
  (let [cfg (config)
        io {:get-text (fn [url _]
                        (if (= "https://github.com/kubernetes-sigs/kind/releases/latest" url)
                          "{\"tag_name\":\"v0.33.0\"}"
                          (throw (ex-info (str "GET " url " returned 404") {:breto/problem :http-status :status 404}))))
            :download! (fn [& _] (throw (ex-info "downloaded" {})))}]
    (stored-version! cfg "kind" "0.33.0" {"kind" "stored"})
    (is (= {:version "0.33.0" :installed? false :verified? false} (install/install! cfg io raw-tool {})))
    (is (= "stored" (slurp (str (fs/path (:bin-dir cfg) "kind")))))))

(deftest incomplete-versions-are-reinstalled
  (let [cfg (config)
        file (fs/path *root* "kind-linux-amd64")
        _ (spit (str file) "fresh")
        url "https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-linux-amd64"
        io (fake-io {url file}
                    {"https://github.com/kubernetes-sigs/kind/releases/latest" "{\"tag_name\":\"v0.33.0\"}"
                     (str url ".sha256sum") (str (checksum/digest "SHA-256" file) "  kind-linux-amd64")})]
    (stored-version! cfg "kind" "0.33.0" {"README" "no binary"})
    (is (= {:version "0.33.0" :installed? true :verified? true} (dissoc (install/install! cfg io raw-tool {}) :sha256)))
    (is (= ["kind"] (map (comp str fs/file-name) (fs/list-dir (fs/path (:store cfg) "kind" "0.33.0")))))
    (is (= "fresh" (slurp (str (fs/path (:bin-dir cfg) "kind")))))))

(deftest compressed-binaries-are-installed-decompressed
  (let [cfg (config)
        file (fs/path *root* "taplo-linux-x86_64.gz")
        _ (with-open [out (java.util.zip.GZIPOutputStream. (java.io.FileOutputStream. (str file)))]
            (.write out (.getBytes "taplo")))
        url "https://github.com/tamasfe/taplo/releases/download/0.10.0/taplo-linux-x86_64.gz"
        pkg (package/normalize {:name "taplo" :source {:github "tamasfe/taplo"} :tag-prefix ""
                                :asset "taplo-linux-x86_64.gz" :checksum "{asset}.sha256"})
        io (fake-io {url file}
                    {"https://github.com/tamasfe/taplo/releases/latest" "{\"tag_name\":\"0.10.0\"}"
                     (str url ".sha256") (checksum/digest "SHA-256" file)})]
    (is (= {:version "0.10.0" :installed? true :verified? true} (dissoc (install/install! cfg io pkg {}) :sha256)))
    (is (= "taplo" (slurp (str (fs/path (:bin-dir cfg) "taplo")))))
    (is (fs/executable? (fs/path (:bin-dir cfg) "taplo")))))

(deftest failed-first-installs-leave-no-package-directory
  (let [cfg (config)
        io (fake-io {} {"https://github.com/kubernetes-sigs/kind/releases/latest" "{\"tag_name\":\"v0.33.0\"}"})]
    (is (thrown? Exception (install/install! cfg io raw-tool {})))
    (is (not (fs/exists? (fs/path (:store cfg) "kind"))))))

(deftest leftovers-are-only-work-directories-nobody-holds
  (let [cfg (config)
        dir (store/package-dir cfg "tool")
        held (fs/path dir ".work-held")
        killed (fs/path dir ".work-killed")
        fresh (fs/path dir ".work-fresh")
        stale (fs/path dir ".staging-1.0.0")]
    (run! fs/create-dirs [held killed fresh stale (fs/path dir "1.0.0")])
    (.close (store/hold! killed))
    (fs/set-last-modified-time stale (java.nio.file.attribute.FileTime/fromMillis 0))
    (with-open [_ (store/hold! held)]
      (is (= (map str [stale killed]) (store/leftovers cfg "tool"))))))

(deftest installs-hold-their-work-directory-and-leave-nothing-behind
  (let [cfg (config)
        served (serve-tool "1.0.0")
        io (assoc served :download! (fn [url dest]
                                      (is (empty? (store/leftovers cfg "tool")))
                                      ((:download! served) url dest)))]
    (install/install! cfg io tool {})
    (is (= [".meta-1.0.0.edn" "1.0.0" "current"] (sort (map (comp str fs/file-name) (fs/list-dir (fs/path (:store cfg) "tool"))))))))

(deftest skipped-latest-releases-fall-back-to-the-newest-other-one
  (let [pkg (assoc raw-tool :skip ["0.33.0"])
        io {:get-text (fn [url _]
                        (case url
                          "https://github.com/kubernetes-sigs/kind/releases/latest" "{\"tag_name\":\"v0.33.0\"}"
                          "https://api.github.com/repos/kubernetes-sigs/kind/releases?per_page=100"
                          (str "[{\"tag_name\":\"v0.33.0\"},{\"tag_name\":\"v0.34.0-rc.1\",\"prerelease\":true},"
                               "{\"tag_name\":\"v0.31.0\"},{\"tag_name\":\"v0.32.1\"},{\"tag_name\":\"docs-2026\"}]")))}]
    (is (= {:tag "v0.32.1" :version "0.32.1"} (install/resolve-release pkg io nil)))
    (is (= {:tag "v0.33.0" :version "0.33.0"} (install/resolve-release pkg io "0.33.0")))))

(deftest unskipped-latest-releases-need-no-listing
  (let [io {:get-text (fn [url _]
                        (is (= "https://github.com/kubernetes-sigs/kind/releases/latest" url))
                        "{\"tag_name\":\"v0.33.0\"}")}]
    (is (= "0.33.0" (:version (install/resolve-release (assoc raw-tool :skip ["0.32.0"]) io nil))))))

(deftest skipping-every-release-is-explained
  (let [io {:get-text (fn [url _]
                        (if (str/includes? url "api.github.com") "[{\"tag_name\":\"v0.33.0\"}]" "{\"tag_name\":\"v0.33.0\"}"))}]
    (is (= :all-skipped (try (install/resolve-release (assoc raw-tool :skip ["0.33.0"]) io nil)
                             (catch clojure.lang.ExceptionInfo e (:breto/problem (ex-data e))))))))

(deftest history-can-exempt-old-releases-from-verification
  (let [cfg (config)
        pkg (package/normalize (assoc raw-tool :history [{:before "0.34.0" :verify false}]))
        file (fs/path *root* "kind-linux-amd64")
        _ (spit (str file) "old")
        url "https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-linux-amd64"
        io (fake-io {url file} {"https://github.com/kubernetes-sigs/kind/releases/latest" "{\"tag_name\":\"v0.33.0\"}"})]
    (is (= {:version "0.33.0" :installed? true :verified? false} (dissoc (install/install! cfg io pkg {}) :sha256)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (install/install! cfg io pkg {:version "0.34.0"})))))

(deftest the-store-lock-makes-a-second-run-wait
  (let [cfg (config)
        ready (fs/path *root* "ready")
        holder (process/process {:err :inherit}
                                "bb" "-cp" (str (fs/absolutize "src")) "-e"
                                (str "(require '[breto.store :as s] '[babashka.fs :as fs])"
                                     "(s/with-store-lock " (pr-str cfg) " (fn [_]) "
                                     "  #(do (spit " (pr-str (str ready)) " \"\") (Thread/sleep 1500)))"))
        _ (loop [n 0] (when (and (< n 100) (not (fs/exists? ready))) (Thread/sleep 50) (recur (inc n))))
        waited (atom nil)
        started (System/currentTimeMillis)]
    (store/with-store-lock cfg #(reset! waited %) (fn []))
    (is (string? @waited) "the second run is told who holds the lock")
    (is (not= "unknown" @waited))
    (is (> (- (System/currentTimeMillis) started) 300) "it waited for the first run")
    @holder))

(deftest an-unheld-store-lock-runs-at-once
  (let [waited (atom false)]
    (is (= :done (store/with-store-lock (config) (fn [_] (reset! waited true)) (constantly :done))))
    (is (false? @waited))))

(defn- editor [extra]
  (package/normalize (merge {:name "editor" :source {:github "acme/editor"}
                             :asset "editor.tar.gz" :strip 1 :layout :tree
                             :bins {"ed" "bin/ed"}}
                            extra)))

(defn- serve-editor [archive]
  (fake-io {"https://github.com/acme/editor/releases/download/v2.0.0/editor.tar.gz" archive}
           {"https://github.com/acme/editor/releases/latest" "{\"tag_name\":\"v2.0.0\"}"}))

(deftest tree-layout-fails-on-a-missing-named-completion
  (let [data (try (install/install! (config) (serve-editor (tarball! "editor.tar.gz" {"bin/ed" "ed"}))
                                    (editor {:completions {:bash "share/ed.bash"}}) {:verify? false})
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :path-missing (:breto/problem data)))
    (is (= "share/ed.bash" (:path data)))))

(deftest tree-layout-refuses-commands-outside-the-tree
  (let [secret (fs/path *root* "secret")
        src (fs/path *root* "src" "release")
        out (fs/path *root* "assets" "editor.tar.gz")]
    (spit (str secret) "key")
    (fs/set-posix-file-permissions secret "rw-------")
    (fs/create-dirs (fs/path src "bin"))
    (fs/create-sym-link (fs/path src "bin" "ed") secret)
    (fs/create-dirs (fs/parent out))
    (process/shell "tar" "--create" "--gzip" "--file" (str out) "--directory" (str (fs/parent src)) "release")
    (let [data (try (install/install! (config) (serve-editor out) (editor {}) {:verify? false})
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :path-missing (:breto/problem data)))
      (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions secret)))))))
