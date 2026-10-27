;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.commands-test
  (:require [babashka.fs :as fs]
            [breto.diagnose]
            [breto.report :as report]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- release [repo _]
  (str "https://github.com/" repo "/releases/latest"))

(defn- kind-world
  "A network with kind v0.33.0 published, verified by its .sha256sum file."
  []
  (let [binary (s/file! "kind" "#!/bin/sh\necho kind\n")
        url "https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-linux-amd64"]
    (s/define! "kind" {:source {:github "kubernetes-sigs/kind"} :asset "kind-linux-amd64"
                       :checksum "{asset}.sha256sum"})
    {:files {url binary}
     :texts {(release "kubernetes-sigs/kind" nil) "{\"tag_name\":\"v0.33.0\"}"
             (str url ".sha256sum") (str (s/sha256 binary) "  kind-linux-amd64\n")}}))

(defn- io-for [{:keys [files texts]} requests]
  (s/fake-io files texts requests))

(deftest update-installs-reports-and-records
  (let [world (kind-world)
        requests (atom [])
        {:keys [code out events]} (s/breto! {:packages ["kind"]} ["update"] {:io (io-for world requests)})]
    (is (= 0 code))
    (is (str/includes? out "[OK] kind 0.33.0 installed"))
    (is (fs/exists? (path "bin" "kind")))
    (is (= [:start :stage :stage :bytes :stage :stage :stage :stage :finish :end] (map :type events)))
    (is (= [:resolve :download :verify :unpack :place :activate] (keep :stage events)))
    (let [[run] (report/load-runs)]
      (is (= "update" (:command run)))
      (is (nil? (:running run))))))

(deftest a-second-update-downloads-nothing
  (let [world (kind-world)
        requests (atom [])]
    (s/breto! {:packages ["kind"]} ["update"] {:io (io-for world requests)})
    (reset! requests [])
    (let [{:keys [out]} (s/breto! {:packages ["kind"]} ["update"] {:io (io-for world requests)})]
      (is (str/includes? out "1 up to date"))
      (is (= ["https://github.com/kubernetes-sigs/kind/releases/latest"] @requests)))))

(deftest failures-do-not-stop-the-batch-and-are-diagnosed
  (let [world (kind-world)]
    (s/define! "gone" {:source {:github "acme/gone"} :asset "gone"})
    (let [{:keys [code out events]} (s/breto! {:packages ["gone" "kind"]} ["update"] {:io (io-for world (atom []))})]
      (is (= 1 code))
      (is (str/includes? out "[FAIL] gone"))
      (is (str/includes? out "What to do:"))
      (is (str/includes? out "[OK] kind 0.33.0 installed"))
      (is (= [:failed :installed] (keep :status (filter #(= :finish (:type %)) events)))))))

(deftest delay-follows-downloads-only
  (let [world (kind-world)
        other (s/file! "tool" "tool")
        url "https://github.com/acme/tool/releases/download/v1.0.0/tool"]
    (s/define! "tool" {:source {:github "acme/tool"} :asset "tool" :checksum "{asset}.sha256"})
    (s/define! "gone" {:source {:github "acme/gone"} :asset "gone"})
    (let [world (-> world
                    (assoc-in [:files url] other)
                    (assoc-in [:texts (release "acme/tool" nil)] "{\"tag_name\":\"v1.0.0\"}")
                    (assoc-in [:texts (str url ".sha256")] (s/sha256 other))
                    (assoc-in [:texts (release "acme/gone" nil)] "{\"tag_name\":\"v1.0.0\"}"))
          {:keys [slept]} (s/breto! {:packages ["gone" "kind" "tool"] :delay 7} ["update"]
                                    {:io (io-for world (atom []))})]
      (is (= 7 slept) "only the download of kind owes a wait, before tool; the 404 of gone does not"))))

(deftest unknown-commands-print-usage
  (let [{:keys [code out]} (s/breto! {} ["frobnicate"])]
    (is (= 2 code))
    (is (str/includes? out "Usage: breto"))))

(defn- installed! [name versions active]
  (doseq [v versions]
    (fs/create-dirs (path "store" name v))
    (spit (path "store" name v name) v))
  (fs/create-sym-link (path "store" name "current") active))

(deftest use-previous-activates-the-next-older-version
  (s/define! "tool" {:source {:github "acme/tool"} :asset "tool"})
  (installed! "tool" ["1.9.0" "1.10.0" "2.0.0" "1.2.0"] "2.0.0")
  (let [{:keys [code out]} (s/breto! {:packages ["tool"]} ["use" "tool" "--previous"])]
    (is (= 0 code))
    (is (str/includes? out "[OK] tool 1.10.0 active"))
    (is (str/includes? out ":pins {\"tool\" \"1.10.0\"}"))
    (is (= "1.10.0" (str (fs/read-link (path "store" "tool" "current"))))))
  (s/breto! {:packages ["tool"]} ["use" "tool" "--previous"])
  (is (= "1.9.0" (str (fs/read-link (path "store" "tool" "current"))))))

(deftest use-previous-explains-when-nothing-is-older
  (s/define! "tool" {:source {:github "acme/tool"} :asset "tool"})
  (installed! "tool" ["2.0.0"] "2.0.0")
  (let [e (try (s/breto! {:packages []} ["use" "tool" "--previous"]) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :no-previous (:breto/problem (ex-data e))))
    (is (= ["2.0.0"] (:installed (ex-data e))))))

(deftest unconfigured-packages-get-no-pin-advice
  (s/define! "tool" {:source {:github "acme/tool"} :asset "tool"})
  (installed! "tool" ["1.0.0" "2.0.0"] "2.0.0")
  (is (not (str/includes? (:out (s/breto! {:packages []} ["use" "tool" "--previous"])) ":pins"))))

(deftest incomplete-use-commands-are-usage-errors
  (let [e (try (s/breto! {} ["use" "tool"]) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (= :usage (:breto/problem (ex-data e))))
    (is (= ["breto help lists every command with its arguments."]
           (:advice (breto.diagnose/diagnose e {}))))))
