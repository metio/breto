;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.remote-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.remote :as remote]
            [breto.sealed :as sealed]
            [breto.store :as store]
            [breto.support :as s]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each s/with-root)

(def ^:private entry {:github "acme/toolchains"})

(def ^:private registry-base "https://github.com/acme/toolchains/releases/download/2026.1.1/")

(def ^:private tool-base "https://github.com/acme/tool/releases/download/v1.2.3/")

(defn- world
  "acme/toolchains 2026.1.1 publishing the group @lint of acme/tool, and
  acme/tool v1.2.3 as one binary."
  []
  (let [tree (s/path "published")
        _ (fs/create-dirs tree)
        _ (spit (str (fs/path tree "@lint.edn")) (pr-str {:members ["tool"]}))
        _ (spit (str (fs/path tree "tool.edn"))
                (pr-str (s/everywhere {:source {:github "acme/tool"} :asset "tool" :bins {"tool" "tool"}})))
        archive (s/path "files" "toolchains-2026.1.1.tar.gz")
        _ (fs/create-dirs (s/path "files"))
        _ (process/shell {:dir tree :extra-env {"GZIP" "-n"}} "tar" "--create" "--gzip" "--mtime=@0" "--owner=0" "--group=0"
                         "--numeric-owner" "--file" archive "@lint.edn" "tool.edn")
        sums (s/file! "SHA256SUMS" (str (s/sha256 archive) "  toolchains-2026.1.1.tar.gz\n"))
        bundle (s/file! "bundle" "{}")
        binary (s/file! "tool" "#!/bin/sh\necho tool\n")]
    {:files {(str registry-base "toolchains-2026.1.1.tar.gz") archive
             (str registry-base "toolchains_2026.1.1_SHA256SUMS") sums
             (str registry-base "toolchains_2026.1.1_SHA256SUMS.bundle") bundle
             (str tool-base "tool") binary}
     :texts {"https://github.com/acme/toolchains/releases/latest" "{\"tag_name\":\"2026.1.1\"}"
             (str registry-base "toolchains_2026.1.1_SHA256SUMS") (slurp sums)
             "https://api.github.com/repos/acme/toolchains/releases/tags/2026.1.1"
             (json/generate-string {:tag_name "2026.1.1"
                                    :assets [{:name "toolchains-2026.1.1.tar.gz" :digest nil}
                                             {:name "toolchains_2026.1.1_SHA256SUMS" :digest nil}
                                             {:name "toolchains_2026.1.1_SHA256SUMS.bundle" :digest nil}]})
             "https://github.com/acme/tool/releases/latest" "{\"tag_name\":\"v1.2.3\"}"
             "https://api.github.com/repos/acme/tool/releases/tags/v1.2.3"
             (json/generate-string {:tag_name "v1.2.3" :assets [{:name "tool" :digest (str "sha256:" (s/sha256 binary))}]})}}))

(defn- breto! [config args calls]
  (let [{:keys [files texts]} (world)]
    (s/breto! (merge {:registries [entry]} config) args
              {:io (assoc (s/fake-io files texts (atom []))
                          :run (fn [command] (swap! calls conj (vec command)) {:exit 0 :out "" :err "Verified OK"})
                          :which {"cosign" "/usr/bin/cosign"})})))

(deftest the-default-definition-expects-the-release-workflow-on-main
  (let [definition (remote/definition entry)]
    (is (= "registry-acme-toolchains" (remote/package-name entry)))
    (is (= "toolchains-{version}.tar.gz" (:asset definition)))
    (is (= "toolchains_{version}_SHA256SUMS" (:checksum definition)))
    (is (= {:bundle "toolchains_{version}_SHA256SUMS.bundle"
            :identity "https://github.com/acme/toolchains/.github/workflows/release.yml@refs/heads/main"
            :issuer "https://token.actions.githubusercontent.com"
            :of :checksum}
           (:signature definition)))))

(deftest entries-with-unknown-keys-or-no-repository-are-refused
  (is (empty? (remote/problems entry)))
  (is (seq (remote/problems {:github "toolchains"})))
  (is (seq (remote/problems {:github "acme/toolchains" :url "x"})))
  (is (seq (remote/problems {:github "acme/toolchains" :signature {:identity 1}}))))

(deftest update-installs-the-registry-before-the-groups-it-publishes
  (let [calls (atom [])
        {:keys [code out]} (breto! {:packages ["@lint"]} ["update"] calls)]
    (is (= 0 code) out)
    (is (= "2026.1.1" (store/current-version {:store (s/path "store")} "registry-acme-toolchains")))
    (is (= "1.2.3" (store/current-version {:store (s/path "store")} "tool"))
        "the member of the published group is installed in the same run")
    (is (some #(and (= "verify-blob" (second %))
                    (some #{"https://github.com/acme/toolchains/.github/workflows/release.yml@refs/heads/main"} %))
              @calls)
        "the checksums of the registry release are verified with cosign")))

(deftest a-registry-not-installed-yet-is-reported-by-other-commands
  (let [err (java.io.StringWriter.)
        {:keys [code out]} (binding [*err* err] (breto! {} ["list"] (atom [])))]
    (is (= 0 code) out)
    (is (str/includes? (str err) "acme/toolchains is not installed"))))

(deftest an-invalid-entry-fails-to-load
  (let [{:keys [code]} (try (breto! {:registries [{:github "toolchains"}]} ["list"] (atom []))
                            (catch clojure.lang.ExceptionInfo e {:code (ex-message e)}))]
    (is (str/includes? (str code) "invalid registry"))))

(deftest sync-installs-the-locked-registry-before-the-packages-it-defines
  (let [config {:packages ["@lint"] :lockfile (s/path "breto.lock")}]
    (is (= 0 (:code (breto! config ["update"] (atom [])))))
    (is (str/includes? (slurp (s/path "breto.lock")) "registry-acme-toolchains"))
    (sealed/delete-tree! (s/path "store"))
    (let [{:keys [code out]} (breto! config ["sync"] (atom []))]
      (is (= 0 code) out)
      (is (= "2026.1.1" (store/current-version {:store (s/path "store")} "registry-acme-toolchains")))
      (is (= "1.2.3" (store/current-version {:store (s/path "store")} "tool"))))))

(deftest doctor-reports-whether-the-registry-is-installed
  (is (str/includes? (:out (breto! {} ["doctor"] (atom []))) "A published registry is not installed"))
  (breto! {} ["update"] (atom []))
  (is (str/includes? (:out (breto! {} ["doctor"] (atom []))) "A published registry is installed")))

(deftest update-installs-cosign-before-a-registry-when-none-is-found
  (let [defs (s/path "defs")
        cosign-base "https://github.com/acme/cosign/releases/download/v3.0.0/"
        binary (s/file! "cosign" "#!/bin/sh\n")
        {:keys [files texts]} (world)
        calls (atom [])
        _ (fs/create-dirs defs)
        _ (spit (str (fs/path defs "cosign.edn"))
                (pr-str (s/everywhere {:source {:github "acme/cosign"} :asset "cosign" :bins {"cosign" "cosign"}})))
        {:keys [code out]} (s/breto! {:registries [(str defs) entry] :packages ["@lint"]} ["update"]
                                     {:io (assoc (s/fake-io (assoc files (str cosign-base "cosign") binary)
                                                            (assoc texts
                                                                   "https://github.com/acme/cosign/releases/latest" "{\"tag_name\":\"v3.0.0\"}"
                                                                   "https://api.github.com/repos/acme/cosign/releases/tags/v3.0.0"
                                                                   (json/generate-string {:tag_name "v3.0.0" :assets [{:name "cosign" :digest (str "sha256:" (s/sha256 binary))}]}))
                                                            (atom []))
                                                 :run (fn [command] (swap! calls conj (vec command)) {:exit 0 :out "" :err "Verified OK"})
                                                 :which (constantly nil))})]
    (is (= 0 code) out)
    (is (= "3.0.0" (store/current-version {:store (s/path "store")} "cosign")))
    (is (= "2026.1.1" (store/current-version {:store (s/path "store")} "registry-acme-toolchains")))
    (is (some #(and (= "verify-blob" (second %)) (str/ends-with? (first %) "/cosign")) @calls))))

(deftest env-installs-a-missing-registry-before-the-project-packages
  (let [project (s/path "project")
        _ (fs/create-dirs project)
        _ (spit (str (fs/path project ".breto.edn")) (pr-str {:packages ["@lint"]}))
        err (java.io.StringWriter.)
        {:keys [code out]} (binding [*err* err] (breto! {} ["env" "--dir" project] (atom [])))]
    (is (= 0 code) (str out err))
    (is (= "2026.1.1" (store/current-version {:store (s/path "store")} "registry-acme-toolchains")))
    (is (fs/directory? (store/version-dir {:store (s/path "store")} "tool" "1.2.3")))
    (is (str/includes? out "export PATH="))
    (is (not (str/includes? out "[OK]")) "stdout holds only the shell code")
    (is (not (str/includes? (str err) "is not installed")))))

(deftest env-without-installing-leaves-a-missing-registry-alone
  (let [project (s/path "project")
        _ (fs/create-dirs project)
        _ (spit (str (fs/path project ".breto.edn")) (pr-str {:packages ["tool"]}))]
    (binding [*err* (java.io.StringWriter.)]
      (try (breto! {} ["env" "--no-install" "--dir" project] (atom []))
           (catch clojure.lang.ExceptionInfo _)))
    (is (nil? (store/current-version {:store (s/path "store")} "registry-acme-toolchains")))))

(deftest install-installs-a-missing-registry-before-the-packages-it-defines
  (let [{:keys [code out]} (breto! {} ["install" "tool"] (atom []))]
    (is (= 0 code) out)
    (is (= "2026.1.1" (store/current-version {:store (s/path "store")} "registry-acme-toolchains")))
    (is (= "1.2.3" (store/current-version {:store (s/path "store")} "tool")))))
