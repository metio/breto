;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.uninstall-test
  (:require [babashka.fs :as fs]
            [breto.main :as main]
            [breto.report :as report]
            [breto.store :as store]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]))

(def ^:dynamic *root* nil)

(use-fixtures :each
  (fn [t]
    (let [root (fs/create-temp-dir {:prefix "breto-test-"})]
      (try (binding [*root* root report/*state-home* (str (fs/path root "state"))] (t))
           (finally (fs/delete-tree root))))))

(defn- path [& parts]
  (str (apply fs/path *root* parts)))

(defn- define! [name definition]
  (fs/create-dirs (path "packages"))
  (spit (path "packages" (str name ".edn")) (pr-str definition)))

(defn- install!
  "Lays out an installed and activated version of `name` with `bins`."
  [name version bins]
  (doseq [bin bins]
    (fs/create-dirs (path "store" name version))
    (spit (path "store" name version bin) "#!/bin/sh\n")
    (fs/create-dirs (path "bin"))
    (fs/create-sym-link (path "bin" bin) (path "store" name "current" bin)))
  (fs/create-sym-link (path "store" name "current") version))

(defn- breto! [config & args]
  (spit (path "config.edn") (pr-str (merge {:store (path "store") :bin-dir (path "bin")
                                            :registries [(path "packages")]}
                                           config)))
  (let [out (java.io.StringWriter.)
        code (binding [*out* out] (main/run (concat args ["--config" (path "config.edn")])))]
    {:code code :out (str out)}))

(deftest uninstall-removes-the-package-and-its-links
  (define! "tool" {:source {:github "acme/tool"} :asset "tool"})
  (install! "tool" "1.0.0" ["tool"])
  (spit (path "bin" "unrelated") "keep")
  (let [{:keys [code out]} (breto! {:packages []} "uninstall" "tool")]
    (is (= 0 code))
    (is (str/includes? out "[OK] tool removed with 1 links"))
    (is (not (fs/exists? (path "store" "tool"))))
    (is (not (fs/exists? (path "bin" "tool") {:nofollow-links true})))
    (is (fs/exists? (path "bin" "unrelated")))))

(deftest uninstall-removes-extra-links
  (define! "ed" {:source {:github "acme/ed"} :asset "ed.tar.gz" :layout :tree
                 :bins {"ed" "ed"} :links {(path "runtime") "runtime"}})
  (install! "ed" "1.0.0" ["ed"])
  (fs/create-dirs (path "store" "ed" "1.0.0" "runtime"))
  (fs/create-sym-link (path "runtime") (path "store" "ed" "current" "runtime"))
  (is (= 0 (:code (breto! {:packages []} "uninstall" "ed"))))
  (is (not (fs/exists? (path "runtime") {:nofollow-links true}))))

(deftest uninstall-refuses-configured-packages-without-force
  (define! "tool" {:source {:github "acme/tool"} :asset "tool"})
  (install! "tool" "1.0.0" ["tool"])
  (let [{:keys [code out]} (breto! {:packages ["tool"]} "uninstall" "tool")]
    (is (= 1 code))
    (is (str/includes? out "next breto update installs it again"))
    (is (fs/exists? (path "store" "tool"))))
  (is (= 0 (:code (breto! {:packages ["tool"]} "uninstall" "tool" "--force"))))
  (is (not (fs/exists? (path "store" "tool")))))

(deftest uninstall-works-without-a-definition
  (install! "gone" "2.0" ["gone"])
  (is (= 0 (:code (breto! {:packages []} "uninstall" "gone"))))
  (is (not (fs/exists? (path "store" "gone")))))

(deftest gc-prunes-unconfigured-packages-only-when-enabled
  (define! "kept" {:source {:github "acme/kept"} :asset "kept"})
  (define! "dropped" {:source {:github "acme/dropped"} :asset "dropped"})
  (install! "kept" "1.0" ["kept"])
  (install! "dropped" "1.0" ["dropped"])
  (breto! {:packages ["kept"]} "gc")
  (is (fs/exists? (path "store" "dropped")))
  (let [{:keys [out]} (breto! {:packages ["kept"] :prune true} "gc" "--dry-run")]
    (is (str/includes? out "dropped would be removed"))
    (is (fs/exists? (path "store" "dropped"))))
  (let [{:keys [code out]} (breto! {:packages ["kept"] :prune true} "gc")]
    (is (= 0 code))
    (is (str/includes? out "breto gc: 1 removed"))
    (is (= ["kept"] (store/installed-packages {:store (path "store")})))
    (is (fs/exists? (path "bin" "kept")))
    (is (not (fs/exists? (path "bin" "dropped") {:nofollow-links true})))))

(deftest prune-keeps-a-package-whose-links-a-configured-package-owns
  (define! "babashka" {:source {:github "babashka/babashka"} :asset "bb" :bins {"bb" "bb"}})
  (install! "old-bb" "1.0" ["bb"])
  (let [{:keys [code out]} (breto! {:packages ["babashka"] :prune true} "gc")]
    (is (= 1 code))
    (is (str/includes? out "belongs to babashka"))
    (is (fs/exists? (path "store" "old-bb")))
    (is (fs/exists? (path "bin" "bb")))))

(deftest runs-of-different-commands-are-kept-side-by-side
  (report/save! {:command "update" :results []})
  (report/save! {:command "gc" :results []})
  (report/save! {:command "update" :results [{:package "a" :status :current}]})
  (is (= ["gc" "update"] (map :command (report/load-runs))))
  (is (= [{:package "a" :status :current}] (:results (last (report/load-runs))))))

(deftest delay-follows-only-calls-that-downloaded
  (let [slept (atom [])
        downloaded (atom [true false true])
        f (main/paced 300 #(let [d (first @downloaded)] (swap! downloaded rest) d)
                      (constantly 0) (fn [s _] (swap! slept conj s)) identity)]
    (is (= ["a" "b" "c" "d"] (mapv f ["a" "b" "c" "d"])))
    (is (= [300 300] @slept))))

(deftest upstream-waits-win-over-a-shorter-delay
  (let [slept (atom [])
        upstream (atom 0)
        f (main/paced 300 (constantly true) #(deref upstream) (fn [s _] (swap! slept conj s)) identity)]
    (f "a")
    (reset! upstream 1200)
    (f "b")
    (reset! upstream 10)
    (f "c")
    (is (= [1200 300] @slept))))

(deftest upstream-waits-apply-without-any-download
  (let [slept (atom [])
        f (main/paced 300 (constantly false) (constantly 42) (fn [s _] (swap! slept conj s)) identity)]
    (f "a")
    (is (= [42] @slept))))

(deftest failed-calls-that-downloaded-still-owe-the-delay
  (let [slept (atom [])
        f (main/paced 300 (constantly true) (constantly 0) (fn [s _] (swap! slept conj s))
                      (fn [x] (if (= "a" x) (throw (ex-info "bad checksum" {})) x)))]
    (is (thrown? Exception (f "a")))
    (f "b")
    (is (= [300] @slept))))

(deftest rate-limited-calls-are-retried-once-after-the-named-wait
  (let [slept (atom [])
        calls (atom 0)
        upstream (atom 0)
        limited (ex-info "403" {:breto/problem :http-status :status 403
                                :headers {"retry-after" "60"}})
        f (main/paced 0 (constantly false) #(deref upstream) (fn [s _] (swap! slept conj s) (reset! upstream 0))
                      (fn [x]
                        (when (= 1 (swap! calls inc))
                          (reset! upstream 60)
                          (throw limited))
                        x))]
    (is (= "a" (f "a")))
    (is (= [60] @slept))
    (is (= 2 @calls))))
