;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.diagnose-test
  (:require [breto.diagnose :as diagnose]
            [breto.report :as report]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- advice [data probes]
  (str/join "\n" (:advice (diagnose/diagnose (ex-info "failed" data) probes))))

(deftest a-missing-asset-names-the-closest-one-as-a-template
  (let [probes {:assets (fn [repo tag]
                          (is (= ["ulyssa/iamb" "v0.13.1"] [repo tag]))
                          [{:name "iamb-0.13.1-x86_64-unknown-linux-musl.tar.gz"}
                           {:name "iamb-0.13.1-aarch64-unknown-linux-gnu.tar.gz"}
                           {:name "SHA256SUMS"}])}
        d (diagnose/diagnose (ex-info "GET … returned 404"
                                      {:breto/problem :http-status :status 404 :stage :download
                                       :url "https://github.com/ulyssa/iamb/releases/download/v0.13.1/iamb-x86_64-unknown-linux-musl.tgz"
                                       :source {:github "ulyssa/iamb"} :tag "v0.13.1" :version "0.13.1"
                                       :asset "iamb-x86_64-unknown-linux-musl.tgz"})
                             probes)]
    (is (= :download (:stage d)))
    (is (str/includes? (str/join (:facts d)) "iamb-0.13.1-x86_64-unknown-linux-musl.tar.gz"))
    (is (str/includes? (str/join (:advice d)) "\"iamb-{version}-x86_64-unknown-linux-musl.tar.gz\""))
    (is (str/includes? (str/join (:advice d)) ":history"))))

(deftest a-moved-repository-is-detected
  (is (str/includes? (advice {:breto/problem :http-status :status 406 :stage :resolve
                              :url "https://github.com/ellie/atuin/releases/latest"
                              :source {:github "ellie/atuin"}}
                             {:repo (fn [_] {:full_name "atuinsh/atuin"})})
                     "{:github \"atuinsh/atuin\"}")))

(deftest a-vanished-repository-is-detected
  (is (str/includes? (:summary (diagnose/diagnose (ex-info "x" {:breto/problem :http-status :status 404 :stage :resolve
                                                               :url "u" :source {:github "gone/away"}})
                                                  {:repo (fn [_] (throw (ex-info "404" {})))}))
                     "does not exist")))

(deftest rate-limits-recommend-a-token
  (let [d (diagnose/diagnose (ex-info "x" {:breto/problem :http-status :status 403 :stage :verify
                                           :url "https://api.github.com/repos/a/b/releases/tags/v1"
                                           :headers {"x-ratelimit-remaining" "0" "x-ratelimit-reset" "1793145600"}})
                             {})]
    (is (str/includes? (:summary d) "rate limit"))
    (is (some #(str/includes? % "2026-") (:facts d)))
    (is (str/includes? (str/join (:advice d)) "GITHUB_TOKEN"))))

(deftest tag-prefix-mismatches-suggest-a-prefix
  (is (= "mimir-" (diagnose/suggest-tag-prefix "mimir-3.2.3")))
  (is (= "kustomize/v" (diagnose/suggest-tag-prefix "kustomize/v5.8.3")))
  (is (str/includes? (advice {:breto/problem :tag-prefix :tag "mimir-3.2.3" :tag-prefix "v"} {})
                     ":tag-prefix \"mimir-\"")))

(deftest missing-binaries-suggest-strip-or-bins
  (testing "the binary sits one directory deeper"
    (is (= 1 (diagnose/suggest-strip ["eza-0.23/eza" "eza-0.23/LICENSE"] "eza" 0)))
    (is (str/includes? (advice {:breto/problem :path-missing :path "eza" :strip 0 :asset "eza.tar.gz"
                                :listing ["eza-0.23/eza" "eza-0.23/LICENSE"]} {})
                       ":strip 1")))
  (testing "the binary has another name"
    (is (str/includes? (advice {:breto/problem :path-missing :path "yq" :strip 0 :asset "yq.tar.gz"
                                :listing ["yq.1" "yq_linux_amd64" "install-man-page.sh"]} {})
                       "{\"yq\" \"yq_linux_amd64\"}"))))

(deftest missing-checksums-point-at-checksum-like-assets
  (is (str/includes? (advice {:breto/problem :no-checksum :source {:github "a/tool"} :repo "a/tool"
                              :tag "v1.0.0" :version "1.0.0" :asset "tool_linux.tar.gz"}
                             {:assets (fn [_ _] [{:name "tool_linux.tar.gz"} {:name "tool_1.0.0_checksums.txt"}])})
                     "Set :checksum to \"tool_{version}_checksums.txt\"")))

(deftest checksum-mismatches-warn-against-bypassing
  (is (str/includes? (advice {:breto/problem :checksum-mismatch :asset "a" :expected "0" :actual "1"} {})
                     "Do not bypass")))

(deftest missing-compressors-are-named
  (is (str/includes? (advice {:breto/problem :unpack-failed :file "x.tar.xz"
                              :stderr "tar (child): xz: Cannot exec: No such file or directory"} {})
                     "Install xz")))

(deftest unknown-packages-suggest-similar-names
  (is (str/includes? (str/join (:facts (diagnose/diagnose (ex-info "x" {:breto/problem :unknown-package :package "kubectl-netshot"
                                                                         :known ["kubectl" "kubectl-netshoot" "helm"]})
                                                          {})))
                     "kubectl-netshoot")))

(deftest failing-probes-never-hide-the-diagnosis
  (let [d (diagnose/diagnose (ex-info "GET x returned 404" {:breto/problem :http-status :status 404 :stage :download
                                                            :url "x" :source {:github "a/b"} :tag "v1" :asset "b"})
                             {:assets (fn [_ _] (throw (Exception. "offline")))})]
    (is (str/includes? (:summary d) "no asset named b"))))

(deftest unexpected-exceptions-still-get-advice
  (let [d (diagnose/diagnose (NullPointerException. "boom") {})]
    (is (= "boom" (:message d)))
    (is (str/includes? (str/join (:advice d)) "--debug"))))

(deftest reports-summarise-and-explain-failures
  (let [text (report/render {:command "update"
                             :results [{:package "kind" :version "0.33.0" :status :current}
                                       {:package "helm" :version "4.3.2" :status :installed}
                                       {:package "iamb" :version "0.13.1" :status :failed
                                        :diagnosis {:message "GET u returned 404" :stage :download
                                                    :summary "The release has no asset named a."
                                                    :facts ["Closest assets: b"] :advice ["Set :asset to \"b\"."]}}]})]
    (is (str/includes? text "[OK] helm 4.3.2 installed"))
    (is (str/includes? text "[FAIL] iamb 0.13.1 (download)"))
    (is (str/includes? text "  - Set :asset to \"b\"."))
    (is (str/ends-with? text "breto update: 1 up to date, 1 installed, 1 failed"))))
