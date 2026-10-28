;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.package-test
  (:require [breto.package :as package]
            [breto.source :as source]
            [breto.template :as template]
            [breto.version :as version]
            [clojure.test :refer [deftest is testing]]))

(deftest render-replaces-placeholders
  (is (= "helm-v3.19.0-linux" (template/render "{name}-{tag}-linux" {:name "helm" :tag "v3.19.0"}))))

(deftest render-rejects-unknown-placeholders
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown placeholder \{os\}"
                        (template/render "x-{os}" {}))))

(deftest render-keeps-replacement-text-literal
  (is (= "a$1b" (template/render "a{tag}b" {:tag "$1"}))))

(deftest archive-type-by-suffix
  (is (= :tar (package/archive-type "eza.tar.gz")))
  (is (= :tar (package/archive-type "tcard.x86_64-linux.tgz")))
  (is (= :tar (package/archive-type "helix-25.07.1-x86_64-linux.tar.xz")))
  (is (= :zip (package/archive-type "tofu_1.10.0_linux_amd64.zip")))
  (is (= :raw (package/archive-type "kind-linux-amd64"))))

(deftest normalize-fills-github-defaults
  (let [pkg (package/normalize {:name "eza" :source {:github "eza-community/eza"}
                                :asset "eza_x86_64-unknown-linux-gnu.tar.gz"})]
    (is (= "v" (:tag-prefix pkg)))
    (is (= :binary (:layout pkg)))
    (is (= {"eza" "eza"} (:bins pkg)))
    (is (= "https://github.com/eza-community/eza/releases/download/{tag}/{asset}" (:url pkg)))))

(deftest normalize-defaults-raw-assets-to-the-downloaded-file
  (is (= {"kind" "{asset}"}
         (:bins (package/normalize {:name "kind" :source {:github "kubernetes-sigs/kind"}
                                    :asset "kind-linux-amd64"})))))

(deftest normalize-rejects-incomplete-definitions
  (testing "non-github sources need a url"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":url is required"
                          (package/normalize {:name "kubectl" :source {:text "https://dl.k8s.io/release/stable.txt"}
                                              :asset "kubectl"}))))
  (testing "exactly one source kind"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":source must have exactly one"
                          (package/normalize {:name "x" :source {:github "a/b" :text "u"} :asset "x"})))))

(deftest tag-and-version-round-trip
  (let [pkg {:name "kustomize" :tag-prefix "kustomize/"}]
    (is (= "v5.7.1" (package/version-of-tag pkg "kustomize/v5.7.1")))
    (is (= "kustomize/v5.7.1" (package/tag-of-version pkg "v5.7.1")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not start with"
                          (package/version-of-tag pkg "v5.7.1")))))

(deftest artifact-encodes-slashes-in-the-url-only
  (let [pkg (package/normalize {:name "kustomize" :source {:github "kubernetes-sigs/kustomize"}
                                :tag-prefix "kustomize/"
                                :asset "kustomize_{version}_linux_amd64.tar.gz"})
        artifact (package/artifact pkg {:tag "kustomize/v5.7.1" :version "v5.7.1"})]
    (is (= "kustomize_v5.7.1_linux_amd64.tar.gz" (:asset artifact)))
    (is (= "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2Fv5.7.1/kustomize_v5.7.1_linux_amd64.tar.gz"
           (:url artifact)))))

(deftest artifact-resolves-checksums-next-to-the-asset
  (let [pkg (package/normalize {:name "kubectl" :source {:text "https://dl.k8s.io/release/stable.txt"}
                                :asset "kubectl"
                                :url "https://dl.k8s.io/release/{tag}/bin/linux/amd64/{asset}"
                                :checksum "{asset}.sha256"})]
    (is (= "https://dl.k8s.io/release/v1.34.1/bin/linux/amd64/kubectl.sha256"
           (:checksum-url (package/artifact pkg {:tag "v1.34.1" :version "1.34.1"}))))))

(deftest artifact-passes-absolute-checksum-urls-through
  (let [pkg (package/normalize {:name "x" :source {:github "a/x"} :asset "x.tar.gz"
                                :checksum "https://sums.example/{version}/SUMS"})]
    (is (= "https://sums.example/1.0.0/SUMS"
           (:checksum-url (package/artifact pkg {:tag "v1.0.0" :version "1.0.0"}))))))

(deftest bin-paths-follow-the-layout
  (let [artifact {:bins {"hx" "hx" "kind" "kind-linux-amd64" "ansible" "bin/ansible-wrapper"}}]
    (is (= {"hx" "hx" "kind" "kind" "ansible" "ansible"}
           (package/bin-paths {:name "x" :layout :binary} artifact)))
    (is (= {"hx" "hx" "kind" "kind-linux-amd64" "ansible" "bin/ansible-wrapper"}
           (package/bin-paths {:name "x" :layout :tree} artifact)))))

(deftest a-single-binary-is-kept-under-the-package-name
  (is (= {"kubectl-oidc_login" "kubelogin"}
         (package/bin-paths {:name "kubelogin" :layout :binary} {:bins {"kubectl-oidc_login" "kubelogin"}})))
  (is (= {"kind" "kind"}
         (package/bin-paths {:name "kind" :layout :binary} {:bins {"kind" "kind-linux-amd64"}})))
  (is (= {"ansible" "ansible-wrapper" "ansible-wrapper" "ansible-wrapper"}
         (package/bin-paths {:name "ansible-wrapper" :layout :binary}
                            {:bins {"ansible" "ansible-wrapper" "ansible-wrapper" "ansible-wrapper"}}))))

(deftest versions-compare-numerically
  (is (version/older? "1.9" "1.10"))
  (is (version/older? "2025.12" "2026.9"))
  (is (version/older? "0.0.12" "0.0.13"))
  (is (version/older? "1.0" "1.0.1"))
  (is (zero? (version/compare-versions "1.2.0" "1.2.0"))))

(deftest next-versions-cover-the-given-one
  (is (= "1.2.4" (version/next-version "1.2.3")))
  (is (= "0.10" (version/next-version "0.9")))
  (is (= "2026.10.19" (version/next-version "2026.10.18")))
  (is (= "1.0.0-rc.2" (version/next-version "1.0.0-rc.1")))
  (is (= "v013" (version/next-version "v012")))
  (is (= "v100" (version/next-version "v099")))
  (is (= "nightly.1" (version/next-version "nightly")))
  (is (version/older? "v012" (version/next-version "v012"))))

(deftest matcher-captures-the-version
  (let [m (re-matcher (template/matcher "ble-{name}-{version}.tar.xz" {:name "nightly"})
                      "ble-nightly-20261027+c70c0e4.tar.xz")]
    (is (.matches m))
    (is (= "20261027+c70c0e4" (.group m "version"))))
  (is (not (re-matches (template/matcher "ble-nightly-{version}.tar.xz" {}) "ble-nightly.tar.xz"))))

(def ^:private iamb
  (package/normalize {:name "iamb" :source {:github "ulyssa/iamb"}
                      :asset "iamb-{version}-x86_64-unknown-linux-musl.tar.gz" :strip 1
                      :history [{:before "0.0.12" :asset "iamb-x86_64-unknown-linux-musl.tgz"}
                                {:before "0.0.13" :asset "iamb-x86_64-unknown-linux-musl-binary.zip" :strip 0}]}))

(deftest history-applies-every-boundary-above-a-version
  (is (= "iamb-0.13.1-x86_64-unknown-linux-musl.tar.gz"
         (:asset (package/artifact (package/at iamb "0.13.1") {:tag "v0.13.1" :version "0.13.1"}))))
  (is (= ["iamb-x86_64-unknown-linux-musl-binary.zip" 0]
         ((juxt :asset :strip) (package/at iamb "0.0.12"))))
  (testing "an older version inherits keys from later boundaries and overrides them with its own"
    (is (= ["iamb-x86_64-unknown-linux-musl.tgz" 0]
           ((juxt :asset :strip) (package/at iamb "0.0.11")))))
  (is (= iamb (package/at iamb nil))))

(deftest history-recomputes-defaults-per-version
  (let [pkg (package/normalize {:name "x" :source {:github "new/x"} :asset "x.tar.gz"
                                :history [{:before "2.0" :source {:github "old/x"}}]})]
    (is (= "https://github.com/old/x/releases/download/{tag}/{asset}" (:url (package/at pkg "1.0"))))
    (is (= "https://github.com/new/x/releases/download/{tag}/{asset}" (:url (package/at pkg "2.0"))))))

(deftest history-entries-are-validated
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a :before"
                        (package/normalize {:name "x" :source {:github "a/x"} :asset "x" :history [{:asset "y"}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":url is required"
                        (package/normalize {:name "x" :source {:github "a/x"} :asset "x"
                                            :history [{:before "1.0" :source {:text "https://t"}}]}))))

(deftest rolling-releases-use-their-fixed-tag
  (let [pkg (package/normalize {:name "blesh" :source {:github "akinomyoga/ble.sh" :tag "nightly"}
                                :asset "ble-nightly-{version}.tar.xz" :layout :tree :bins {}})]
    (is (= "nightly" (package/tag-of-version pkg "20261027+c70c0e4")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must contain \{version\}"
                          (package/normalize (assoc pkg :asset "ble-nightly.tar.xz" :history []))))))

(deftest gitlab-sources-read-the-latest-release-permalink
  (let [pkg (package/normalize {:name "glab" :source {:gitlab "gitlab.com/gitlab-org/cli"}
                                :asset "glab_{version}_linux_amd64.tar.gz"
                                :url "https://gitlab.com/gitlab-org/cli/-/releases/{tag}/downloads/{asset}"})
        io {:get-text (fn [url _]
                        (is (= "https://gitlab.com/api/v4/projects/gitlab-org%2Fcli/releases/permalink/latest" url))
                        "{\"tag_name\":\"v1.125.0\"}")}]
    (is (= {:tag "v1.125.0" :version "1.125.0"} (source/latest pkg io)))))

(deftest several-binaries-are-kept-under-command-names
  (is (= {"bin/tool-linux" "a-tool" "bin/helper" "helper"}
         (package/stored-files {:name "tool"} {:bins {"z-tool" "bin/tool-linux" "a-tool" "bin/tool-linux"
                                                      "helper" "bin/helper"}}))))

(deftest skip-lists-must-hold-version-strings
  (is (thrown? clojure.lang.ExceptionInfo
               (package/normalize {:name "x" :source {:github "a/x"} :asset "x" :skip "0.5.1"}))))

(deftest bins-stay-inside-the-release-and-the-bin-directory
  (doseq [bins [{"../evil" "tool"} {"sub/tool" "tool"} {".hidden" "tool"} {"tool" "/etc/passwd"} {"tool" "a/../../x"}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":bins must map plain command names"
                          (package/normalize {:name "t" :source {:github "a/t"} :asset "t" :bins bins}))
        (pr-str bins)))
  (is (package/normalize {:name "t" :source {:github "a/t"} :asset "t.tar.gz" :bins {"t" "t-1.0/bin/t"}})))

(deftest pre-releases-precede-their-release
  (is (version/older? "1.0.0-rc1" "1.0.0"))
  (is (version/older? "2.0.0-beta.2" "2.0.0"))
  (is (version/older? "1.0.0" "1.0.1-rc1"))
  (is (version/older? "1.0.0-rc1" "1.0.0-rc2"))
  (is (version/older? "1.1.1" "1.1.1a"))
  (is (version/older? "1.0" "1.0.1")))
