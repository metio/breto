;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.registry-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [breto.support :as s]
            [breto.package :as package]
            [breto.platform :as platform]
            [breto.registry :as registry]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest every-built-in-package-is-valid-on-every-platform
  (doseq [p platform/known
          :let [reg (registry/load-registry nil ["builtin"] p)]]
    (is (= #{"breto" "cosign"} (set (keys reg))) p)
    (is (every? :url (vals reg)) p)))

(deftest every-built-in-package-renders-a-release
  (is (= [] (registry/validate "builtin"))))

(deftest validate-reports-each-problem-of-a-registry-once
  (let [dir (fs/create-temp-dir {:prefix "breto-validate"})]
    (spit (str (fs/path dir "broken.edn")) "{:source")
    (spit (str (fs/path dir "bad.edn")) "{:source {:pypi \"../x\"}}")
    (spit (str (fs/path dir "plain.edn"))
          "{:source {:github \"a/b\"} :asset \"b\" :url \"http://example.org/{asset}\" :platforms {\"linux-x86_64\" {}}}")
    (spit (str (fs/path dir "@empty.edn")) "{:members []}")
    (let [problems (registry/validate (str dir))]
      (is (= 4 (count problems)) problems)
      (is (some #(str/includes? % "broken.edn is not valid EDN") problems))
      (is (some #(and (str/includes? % "names a project on PyPI") (not (str/includes? % "(on "))) problems))
      (is (some #(and (str/includes? % "plain: the download http://example.org/b is not fetched over https")
                      (str/ends-with? % "(on linux-x86_64)"))
                problems))
      (is (some #(str/includes? % "invalid group @empty") problems)))))

(deftest overlays-merge-over-earlier-registries
  (let [base (fs/create-temp-dir)
        overlay (fs/create-temp-dir)]
    (try
      (spit (str (fs/path base "kind.edn")) (pr-str {:source {:github "kubernetes-sigs/kind"} :platforms {"linux-x86_64" {:asset "kind-linux-amd64"}}}))
      (spit (str (fs/path overlay "kind.edn")) "{:platforms {\"linux-x86_64\" {:asset \"kind-custom\"}}}")
      (let [kind (registry/lookup (registry/load-registry nil [(str base) (str overlay)] "linux-x86_64") "kind")]
        (is (= "kind-custom" (:asset kind)))
        (is (= {:github "kubernetes-sigs/kind"} (:source kind))))
      (finally (fs/delete-tree base) (fs/delete-tree overlay)))))

(deftest unknown-packages-are-reported
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown package nope"
                        (registry/lookup {} "nope"))))

(deftest a-definition-links-its-command-and-icon
  (let [pkg (s/normalize {:name "jetbrains-idea"
                          :source {:json "https://data.services.jetbrains.com/products/releases?code=IIU&latest=true&type=release"
                                   :version [:IIU 0 :version]}
                          :tag-prefix ""
                          :url "https://download.jetbrains.com/idea/{asset}"
                          :checksum "{asset}.sha256"
                          :strip 1
                          :layout :tree
                          :bins {"jetbrains-idea" "bin/idea"}
                          :links {"~/.local/bin/jetbrains-idea.svg" "bin/idea.svg"}
                          :platforms {"linux-x86_64" {:asset "idea-{version}.tar.gz"}}})
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

(deftest shared-keys-of-built-in-definitions-name-no-platform
  (let [token #"(?i)linux|darwin|macos|apple|windows|x86_64|x86-64|amd64|aarch64|arm64|x64|musl|gnu"
        strings (fn strings [v] (cond (string? v) [v] (map? v) (mapcat strings (concat (keys v) (vals v))) (coll? v) (mapcat strings v)))]
    (doseq [file (fs/glob "builtin" "*.edn")
            :let [definition (edn/read-string (slurp (str file)))]
            [k v] (dissoc definition :platforms :description :source)
            s (strings v)]
      (is (not (re-find token s)) (str (fs/file-name file) " " k " " s)))))
