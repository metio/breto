;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.signature-test
  (:require [babashka.fs :as fs]
            [breto.diagnose :as diagnose]
            [breto.signature :as signature]
            [breto.support :as s]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(def ^:private base "https://github.com/acme/tool/releases/download/v1.2.3/")

(def ^:private sig
  {:bundle "{asset}.sigstore.json"
   :identity "https://github.com/acme/tool/.github/workflows/release.yaml@refs/tags/{tag}"
   :issuer "https://token.actions.githubusercontent.com"})

(defn- world
  "acme/tool v1.2.3 as one binary asset with a GitHub digest and a bundle."
  []
  (let [binary (s/file! "tool" "#!/bin/sh\necho tool\n")
        bundle (s/file! "bundle" "{}")
        sums (s/file! "SHA256SUMS" (str (s/sha256 binary) "  tool\n"))]
    {:files {(str base "tool") binary
             (str base "tool.sigstore.json") bundle
             (str base "SHA256SUMS") sums
             (str base "SHA256SUMS.sigstore.json") bundle}
     :texts {"https://github.com/acme/tool/releases/latest" "{\"tag_name\":\"v1.2.3\"}"
             (str base "SHA256SUMS") (slurp sums)
             "https://api.github.com/repos/acme/tool/releases/tags/v1.2.3"
             (json/generate-string {:tag_name "v1.2.3"
                                    :assets [{:name "tool" :digest (str "sha256:" (s/sha256 binary))}
                                             {:name "tool.sigstore.json" :digest nil}
                                             {:name "SHA256SUMS" :digest nil}
                                             {:name "SHA256SUMS.sigstore.json" :digest nil}]})}}))

(defn- cosign
  "A fake cosign recording each command and answering with `result`."
  [calls result]
  (fn [command]
    (swap! calls conj (vec command))
    (if (fn? result) (result command) result)))

(def ^:private on-path
  "A PATH holding only cosign."
  {"cosign" "/usr/bin/cosign"})

(defn- install [definition run & [args which]]
  (let [{:keys [files texts]} (world)]
    (s/define! "tool" (merge {:source {:github "acme/tool"} :asset "tool" :bins {"tool" "tool"}} definition))
    (s/breto! {:packages ["tool"]} (concat ["install" "tool"] args)
              {:io (assoc (s/fake-io files texts (atom [])) :run run :which (or which on-path))})))

(deftest commands-name-the-expected-signer
  (is (= ["cosign" "verify-blob" "--bundle" "b.json" "--certificate-identity" "me"
          "--certificate-oidc-issuer" "https://issuer" "file"]
         (signature/command "cosign" {:identity "me" :issuer "https://issuer"} "file" "b.json")))
  (is (= ["/opt/cosign" "verify-blob" "--bundle" "b.json" "--certificate-identity-regexp" "^https://github.com/acme/"
          "--certificate-oidc-issuer" "https://issuer" "file"]
         (vec (signature/command "/opt/cosign" {:identity-regexp "^https://github.com/acme/" :issuer "https://issuer"}
                                 "file" "b.json")))))

(deftest a-verified-signature-is-reported
  (let [calls (atom [])
        {:keys [code out]} (install {:signature sig} (cosign calls {:exit 0 :out "" :err "Verified OK"}))]
    (is (= 0 code) out)
    (is (str/includes? out "[OK] tool 1.2.3 installed with a verified signature"))
    (is (= 1 (count @calls)))
    (let [cmd (first @calls)]
      (is (= ["/usr/bin/cosign" "verify-blob" "--bundle"] (take 3 cmd)))
      (is (some #{"https://github.com/acme/tool/.github/workflows/release.yaml@refs/tags/v1.2.3"} cmd))
      (is (str/ends-with? (last cmd) "/tool")))))

(deftest a-rejected-signature-installs-nothing
  (let [{:keys [code out]} (install {:signature sig}
                                    (cosign (atom []) {:exit 1 :out "" :err "none of the expected identities matched"}))]
    (is (= 1 code))
    (is (str/includes? out "does not verify"))
    (is (str/includes? out "none of the expected identities matched"))
    (is (not (fs/exists? (s/path "bin" "tool"))))))

(deftest a-missing-cosign-explains-how-to-get-one
  (let [{:keys [code out]} (install {:signature sig} (cosign (atom []) {:exit 0}) [] (constantly nil))]
    (is (= 1 code))
    (is (str/includes? out "no cosign is installed"))
    (is (str/includes? out "Add \"cosign\" to :packages"))
    (is (not (fs/exists? (s/path "bin" "tool"))))))

(deftest a-cosign-that-cannot-start-is-reported
  (let [{:keys [code out]} (install {:signature sig}
                                    (fn [_] (throw (java.io.IOException. "Cannot run program \"cosign\""))))]
    (is (= 1 code))
    (is (str/includes? out "could not run"))
    (is (str/includes? out "version yourself"))))

(deftest the-configured-cosign-is-used
  (let [calls (atom [])
        {:keys [files texts]} (world)]
    (s/define! "tool" {:source {:github "acme/tool"} :asset "tool" :bins {"tool" "tool"} :signature sig})
    (s/breto! {:packages ["tool"] :cosign "/opt/cosign"} ["install" "tool"]
              {:io (assoc (s/fake-io files texts (atom [])) :run (cosign calls {:exit 0})
                          :which {"/opt/cosign" "/opt/cosign" "cosign" "/usr/bin/cosign"})})
    (is (= "/opt/cosign" (ffirst @calls)))))

(deftest the-cosign-breto-linked-wins-over-path
  (let [linked (s/path "bin" "cosign")]
    (fs/create-dirs (s/path "bin"))
    (spit (str linked) "#!/bin/sh\n")
    (fs/set-posix-file-permissions linked "rwxr-xr-x")
    (is (= (str linked) (signature/locate {:bin-dir (str (s/path "bin"))} on-path)))
    (is (= "/usr/bin/cosign" (signature/locate {:bin-dir (str (s/path "elsewhere"))} on-path)))
    (is (nil? (signature/locate {:bin-dir (str (s/path "elsewhere"))} (constantly nil))))
    (is (nil? (signature/locate {:cosign "/opt/c" :bin-dir (str (s/path "bin"))} (constantly nil))))))

(deftest cosign-bootstraps-on-its-checksum-and-installs-first
  (let [calls (atom [])
        {:keys [files texts]} (world)]
    (s/define! "cosign" {:source {:github "acme/tool"} :asset "tool" :bins {"cosign" "tool"} :signature sig})
    (s/define! "signed" {:source {:github "acme/tool"} :asset "tool" :bins {"signed" "tool"} :signature sig})
    (let [{:keys [code out]} (s/breto! {:packages ["signed" "cosign"]} ["update"]
                                       {:io (assoc (s/fake-io files texts (atom [])) :run (cosign calls {:exit 0})
                                                   :which (constantly nil))})]
      (is (= 0 code) out)
      (is (str/includes? out "[OK] cosign 1.2.3 installed by its checksum; no cosign existed yet to check its signature"))
      (is (str/includes? out "[OK] signed 1.2.3 installed with a verified signature"))
      (is (< (str/index-of out "cosign 1.2.3") (str/index-of out "signed 1.2.3")))
      (is (= [(str (s/path "bin" "cosign"))] (map first @calls))))))

(deftest a-bootstrap-still-needs-a-checksum
  (let [{:keys [files texts]} (world)
        texts (assoc texts "https://api.github.com/repos/acme/tool/releases/tags/v1.2.3"
                     (json/generate-string {:tag_name "v1.2.3" :assets [{:name "tool" :digest nil}]}))]
    (s/define! "cosign" {:source {:github "acme/tool"} :asset "tool" :bins {"cosign" "tool"} :signature sig})
    (let [{:keys [code out]} (s/breto! {:packages ["cosign"]} ["update"]
                                       {:io (assoc (s/fake-io files texts (atom [])) :which (constantly nil))})]
      (is (= 1 code))
      (is (str/includes? out "nothing to verify")))))

(deftest signed-checksum-files-are-verified-before-their-hash-is-trusted
  (let [calls (atom [])
        {:keys [code out]} (install {:checksum "SHA256SUMS"
                                     :signature (assoc sig :of :checksum :bundle "SHA256SUMS.sigstore.json")}
                                    (cosign calls {:exit 0}))]
    (is (= 0 code) out)
    (is (str/ends-with? (last (first @calls)) "/.checksums"))
    (is (str/includes? out "with a verified signature"))))

(deftest no-verify-skips-the-signature
  (let [calls (atom [])
        {:keys [code out]} (install {:signature sig} (cosign calls {:exit 1}) ["--no-verify"])]
    (is (= 0 code) out)
    (is (empty? @calls))))

(deftest check-requires-the-bundle-in-the-release
  (let [{:keys [files texts]} (world)
        check (fn [definition]
                (s/define! "tool" (merge {:source {:github "acme/tool"} :asset "tool" :bins {"tool" "tool"}} definition))
                (s/breto! {:packages ["tool"]} ["check"] {:io (s/fake-io files texts (atom []))}))]
    (testing "present"
      (let [{:keys [code out]} (check {:signature sig})]
        (is (= 0 code) out)
        (is (str/includes? out "signature bundle present"))))
    (testing "missing"
      (let [{:keys [code out]} (check {:signature (assoc sig :bundle "{asset}.sig")})]
        (is (= 1 code))
        (is (str/includes? out "no signature bundle named tool.sig"))
        (is (str/includes? out "tool.sigstore.json"))))))

(deftest invalid-signatures-are-explained
  (let [d (diagnose/diagnose (ex-info "x" {:breto/problem :signature-invalid :signed "tool" :bundle-url "u"
                                           :identity "me" :issuer "iss" :stderr "mismatch"})
                             {})]
    (is (some #(str/includes? % "me via iss") (:facts d)))
    (is (some #(str/includes? % "cosign: mismatch") (:facts d)))))
