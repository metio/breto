;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.registry-test
  (:require [babashka.fs :as fs]
            [breto.support :as s]
            [breto.package :as package]
            [breto.platform :as platform]
            [breto.registry :as registry]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest every-shipped-package-is-valid-on-every-platform
  (doseq [p platform/known
          :let [reg (registry/load-registry nil ["packages"] p)]]
    (is (< 60 (count reg)) p)
    (is (every? :url (remove :unsupported (vals reg))) p)))

(deftest every-shipped-package-renders-a-release
  (doseq [p platform/known
          [name pkg] (registry/load-registry nil ["packages"] p)
          :when (not (:unsupported pkg))
          :let [tag (str (:tag-prefix pkg) "1.2.3")
                pkg (package/at pkg "1.2.3")
                fetched (update-vals (package/fetched-vars pkg) #(if (:json %) "https://example.org/fetched" "20260101"))
                {:keys [url bins checksum-url]} (package/artifact pkg {:tag tag :version "1.2.3" :fetched fetched})]]
    (is (str/starts-with? url "https://") [name p])
    (is (or (nil? checksum-url) (str/starts-with? checksum-url "https://")) [name p])
    (is (or (seq bins) (= :tree (:layout pkg))) [name p])
    (is (every? #(not (or (str/starts-with? % "/") (str/includes? % ".."))) (vals bins)) [name p])))

(deftest imported-definitions-follow-upstream-naming
  (let [rg (get (registry/load-registry nil ["packages"] "linux-x86_64") "ripgrep")
        artifact (package/artifact rg {:tag "14.1.1" :version "14.1.1"})]
    (is (= "https://github.com/BurntSushi/ripgrep/releases/download/14.1.1/ripgrep-14.1.1-x86_64-unknown-linux-musl.tar.gz"
           (:url artifact)))
    (is (= {"rg" "ripgrep-14.1.1-x86_64-unknown-linux-musl/rg"} (:bins artifact)))))

(deftest overlays-merge-over-earlier-registries
  (let [overlay (fs/create-temp-dir)]
    (try
      (spit (str (fs/path overlay "kind.edn")) "{:platforms {\"linux-x86_64\" {:asset \"kind-custom\"}}}")
      (let [kind (registry/lookup (registry/load-registry nil ["packages" (str overlay)] "linux-x86_64") "kind")]
        (is (= "kind-custom" (:asset kind)))
        (is (= {:github "kubernetes-sigs/kind"} (:source kind))))
      (finally (fs/delete-tree overlay)))))

(deftest unknown-packages-are-reported
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown package nope"
                        (registry/lookup {} "nope"))))

(deftest jetbrains-idea-links-its-command-and-icon
  (let [pkg (get (s/load-registry ["packages"]) "jetbrains-idea")
        artifact (package/artifact pkg {:tag "2026.2.3" :version "2026.2.3"})]
    (is (= "https://download.jetbrains.com/idea/idea-2026.2.3.tar.gz" (:url artifact)))
    (is (= "https://download.jetbrains.com/idea/idea-2026.2.3.tar.gz.sha256" (:checksum-url artifact)))
    (is (= {"jetbrains-idea" "bin/idea"} (:bins pkg)))
    (is (= {"~/.local/bin/jetbrains-idea.svg" "bin/idea.svg"} (:links pkg)))))

(deftest broken-definitions-name-their-file
  (let [dir (fs/create-temp-dir)]
    (try
      (spit (str (fs/path dir "bad.edn")) "{:source")
      (let [data (try (s/load-registry [(str dir)]) nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :invalid-package (:breto/problem data)))
        (is (= [(str (fs/path dir "bad.edn"))] (:files data))))
      (spit (str (fs/path dir "bad.edn")) "{:source {:github \"a/b\"} :layout :weird :platforms {\"linux-x86_64\" {:asset \"b\"}}}")
      (let [data (try (registry/load-registry nil [(str dir)] "linux-x86_64") nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (some #(re-find #":layout" %) (:problems data)))
        (is (= [(str (fs/path dir "bad.edn"))] (:files data))))
      (finally (fs/delete-tree dir)))))

(deftest built-in-definitions-come-before-every-directory
  (let [overlay (fs/create-temp-dir)
        builtin {"kind.edn" (pr-str {:source {:github "kubernetes-sigs/kind"} :platforms {"linux-x86_64" {:asset "kind-linux-amd64"}}})
                 "@k8s.edn" (pr-str {:members ["kind"]})}]
    (try
      (spit (str (fs/path overlay "kind.edn")) "{:platforms {\"linux-x86_64\" {:asset \"kind-custom\"}}}")
      (is (= "kind-linux-amd64" (:asset (registry/lookup (registry/load-registry builtin [] "linux-x86_64") "kind"))))
      (is (= "kind-custom" (:asset (registry/lookup (registry/load-registry builtin [(str overlay)] "linux-x86_64") "kind"))))
      (is (= ["kind"] (get-in (registry/load-groups builtin []) ["@k8s" :members])))
      (finally (fs/delete-tree overlay)))))

(deftest a-checkout-has-no-built-in-definitions
  (is (nil? (registry/builtin))))
