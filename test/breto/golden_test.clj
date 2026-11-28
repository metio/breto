;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.golden-test
  "Behaviour recorded in test/fixtures/golden/expected.edn that existing
  configurations, lock files and installations depend on: how a configuration
  file reads under a given HOME and XDG environment, how a lock file parses and
  renders, the layout an install and an env leave on disk and how breto reads
  that layout back, and the digests that name files on disk. `write!`
  regenerates the record; a change to it changes what users already have."
  (:require [babashka.fs :as fs]
            [babashka.classpath :as classpath]
            [babashka.process :as process]
            [breto.checksum :as checksum]
            [breto.env :as env]
            [breto.inputs :as inputs]
            [breto.lockfile :as lockfile]
            [breto.platform :as platform]
            [breto.store :as store]
            [breto.support :as s]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private fixtures "test/fixtures/golden")

(defn- fixture [& parts]
  (str (fs/absolutize (apply fs/path fixtures parts))))

;; Configuration

(def ^:private home "/golden/home")

(def ^:private environments
  "The variables each configuration case runs under, besides HOME and PATH."
  {:xdg-unset {}
   :xdg-set {"XDG_CONFIG_HOME" "/golden/config" "XDG_DATA_HOME" "/golden/data" "XDG_STATE_HOME" "/golden/state"}
   :xdg-blank {"XDG_CONFIG_HOME" "" "XDG_DATA_HOME" "  " "XDG_STATE_HOME" ""}})

(defn- bb-executable []
  (-> (java.lang.ProcessHandle/current) .info .command .get))

(defn- absolute-classpath
  "This process's classpath with every entry absolute, for a babashka started
  in another directory."
  []
  (->> (str/split (classpath/get-classpath) #":")
       (remove str/blank?)
       (map #(str (fs/absolutize %)))
       (str/join ":")))

(defn- configs-under
  "The configuration each case file reads as, in a babashka of its own whose
  HOME, user.home and XDG variables are fixed, so the result does not depend
  on the machine running the tests."
  [variables cases]
  (let [code (str "(require 'breto.config)"
                  "(prn (into (sorted-map) (for [[k p] '" (pr-str cases) "] [k (breto.config/load-config p)])))")
        {:keys [exit out err]} (process/shell {:out :string :err :string :continue true
                                               :dir (str (fs/temp-dir))
                                               :env (merge {"HOME" home "PATH" (System/getenv "PATH")} variables)}
                                              (bb-executable)
                                              "--classpath" (absolute-classpath)
                                              (str "-Duser.home=" home) "-Dos.name=Linux"
                                              "-e" code)]
    (when-not (zero? exit)
      (throw (ex-info (str "the configuration probe failed: " err) {:exit exit})))
    (edn/read-string out)))

(defn- observed-configs []
  (let [scratch (fs/create-temp-dir {:prefix "breto-golden-"})]
    (try
      (spit (str (fs/path scratch "empty.edn")) "")
      (spit (str (fs/path scratch "comments.edn")) ";; nothing configured yet\n\n")
      (let [cases {:missing (str (fs/path scratch "absent.edn"))
                   :empty (str (fs/path scratch "empty.edn"))
                   :comments (str (fs/path scratch "comments.edn"))
                   :full (fixture "config.edn")}]
        (into (sorted-map) (for [[k variables] environments] [k (configs-under variables cases)])))
      (finally (fs/delete-tree scratch)))))

;; Lock files

(defn- problem-of [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (select-keys (ex-data e) [:breto/problem]))))

(defn- observed-lockfile []
  (let [text (slurp (fixture "breto.lock"))
        parsed (lockfile/parse text)
        scratch (fs/create-temp-dir {:prefix "breto-golden-"})]
    (try
      (let [file (fn [name content] (let [p (str (fs/path scratch name))] (spit p content) p))
            vector-lock (file "vector.lock" "[\"alpha\"]")
            broken-lock (file "broken.lock" "{\"alpha\" {:version")
            empty-lock (file "empty.lock" "")
            ;; REUSE-IgnoreStart
            header-only (file "header.lock" ";; SPDX-License-Identifier: 0BSD\n\n")]
        ;; REUSE-IgnoreEnd
        {:parsed parsed
         :rendered (lockfile/render parsed)
         :read (lockfile/read-lock (fixture "breto.lock"))
         :missing (lockfile/read-lock (str (fs/path scratch "absent.lock")))
         :empty (lockfile/read-lock empty-lock)
         :header-only (lockfile/read-lock header-only)
         :vector-problem (problem-of #(lockfile/read-lock vector-lock))
         :broken-problem (problem-of #(lockfile/read-lock broken-lock))})
      (finally (fs/delete-tree scratch)))))

;; Installation layout

(def ^:private alpha-url "https://github.com/acme/alpha/releases/download/v1.0.0/alpha")

(defn- world []
  (let [asset (s/file! "alpha-1.0.0" "#!/bin/sh\necho alpha 1.0.0\n")]
    (s/define! "alpha" {:source {:github "acme/alpha"} :asset "alpha" :verify false})
    (fs/create-dirs (s/path "app"))
    (spit (s/path "app" ".breto.edn") (pr-str {:packages ["alpha"] :commands {"hi" {:run ["alpha"]}}}))
    (s/fake-io {alpha-url asset}
               {"https://github.com/acme/alpha/releases/latest" "{\"tag_name\":\"v1.0.0\"}"}
               (atom []))))

(defn- config []
  {:packages ["alpha"] :lockfile (s/path "breto.lock")})

(def ^:private recorded-dirs #{"bin" "breto.lock" "envs" "store" "state"})

(defn- normalize
  "`text` with the paths and names that differ from run to run replaced: the
  test root, the platform, the env directory, the stamp's markers, process
  ids and times."
  [text env-name]
  (cond-> (-> text
              (str/replace (str s/*root*) "<root>")
              (str/replace (platform/detect) "<platform>")
              (str/replace #"\"\d{4}-\d\d-\d\dT[^\"]+\"" "\"<time>\"")
              (str/replace #"stamp\.d/[0-9a-f]{16}" "stamp.d/<marker>"))
    env-name (str/replace env-name "<env>")))

(defn- tree
  "Every path below the test root that breto wrote, as relative path ->
  [:dir], [:link target] or [:file content executable?]."
  []
  (let [root s/*root*
        env-name (when (fs/directory? (s/path "envs"))
                   (some->> (fs/list-dir (s/path "envs")) first fs/file-name str))]
    (into (sorted-map)
          (for [p (fs/glob root "**" {:hidden true :follow-links false})
                :let [rel (str (fs/relativize root p))]
                :when (recorded-dirs (first (str/split rel #"/")))
                :let [rel (normalize rel env-name)]]
            [rel (cond
                   (fs/sym-link? p) [:link (normalize (str (fs/read-link p)) env-name)]
                   (fs/directory? p) [:dir]
                   (str/ends-with? rel ".lock-holder") [:file "<pid>" false]
                   ;; The stamp is a digest of input paths below the test root;
                   ;; :digests pins the digest itself.
                   (str/ends-with? rel "/stamp") [:file "<digest>" false]
                   :else [:file (normalize (slurp (str p)) env-name) (fs/executable? p)])]))))

(defn- breto! [io & args]
  (binding [*err* (java.io.StringWriter.)]
    (select-keys (s/breto! (config) args {:io io}) [:code :out])))

(defn- observed-install []
  (let [io (world)
        install (breto! io "install" "alpha")
        env-code (:code (breto! io "env" "--dir" (s/path "app")))]
    {:install install :env-code env-code :tree (tree)}))

(defn- recreate!
  "Writes the store, bin directory and lock file of a recorded `tree` below
  the test root, as an earlier breto left them."
  [recorded]
  (doseq [[rel [kind content]] recorded
          :when (#{"store" "bin" "breto.lock"} (first (str/split rel #"/")))
          :when (not (str/includes? rel ".lock"))
          :let [p (fs/path s/*root* rel)
                content (some-> content (str/replace "<root>" (str s/*root*))
                                (str/replace "<platform>" (platform/detect)))]]
    (fs/create-dirs (fs/parent p))
    (case kind
      :dir (fs/create-dirs p)
      :link (fs/create-sym-link p content)
      :file (spit (str p) content)))
  (spit (s/path "breto.lock") (-> (second (get recorded "breto.lock"))
                                  (str/replace "<platform>" (platform/detect)))))

(defn- observed-reread [recorded]
  (let [io (world)]
    (recreate! recorded)
    (let [conf {:store (s/path "store")}]
      {:list (breto! io "list")
       :current (store/current-version conf "alpha")
       :meta (store/read-meta conf "alpha" "1.0.0")
       :lock (edn/read-string (normalize (pr-str (:entries (lockfile/read-lock (s/path "breto.lock")))) nil))
       :locked-install (breto! io "install" "--locked" "alpha")
       :tree (tree)})))

;; Digests

(defn- observed-digests []
  (let [file (fixture "asset.txt")
        blank (fixture "empty.txt")]
    {:env-dirs (into (sorted-map)
                     (for [dir ["/golden/project" "/golden/ünïcödé dir" "/"]]
                       [dir (str (env/env-dir {:env-dir "/envs"} dir))]))
     :inputs-absent (inputs/digest {:present [] :absent ["/golden/project/.breto.edn" "/golden/project/.breto.local.edn"]})
     :stamp-markers (str (fs/relativize "/envs/x" (#'inputs/marker "/envs/x/stamp" "/golden/project/.breto.edn")))
     :checksums (into (sorted-map)
                      (for [algorithm ["MD5" "SHA-1" "SHA-256" "SHA-512"]]
                        [algorithm [(checksum/digest algorithm file) (checksum/digest algorithm blank)]]))}))

;; The record

(defn- observed []
  {:config (observed-configs)
   :lockfile (observed-lockfile)
   :install (let [result (atom nil)] (s/with-root #(reset! result (observed-install))) @result)
   :digests (observed-digests)})

(defn- expected []
  (edn/read-string (slurp (fixture "expected.edn"))))

(defn write!
  "Regenerates the record from the code as it is now."
  []
  (let [record (observed)
        record (assoc record :reread (let [result (atom nil)]
                                       (s/with-root #(reset! result (observed-reread (get-in record [:install :tree]))))
                                       @result))]
    ;; REUSE-IgnoreStart
    (spit (fixture "expected.edn")
          (str ";; SPDX-FileCopyrightText: The breto Authors\n;; SPDX-License-Identifier: 0BSD\n\n"
               ";; Generated by breto.golden-test/write!. The record holds lock file\n"
               ";; headers, which REUSE would read as this file's own.\n"
               ";; REUSE-IgnoreStart\n"
               (with-out-str (pprint/pprint record))
               ";; REUSE-IgnoreEnd\n"))))
    ;; REUSE-IgnoreEnd

(deftest a-configuration-reads-as-recorded
  (let [want (:config (expected))
        have (observed-configs)]
    (doseq [k (keys want)]
      (testing k (is (= (get want k) (get have k)))))))

(deftest a-lock-file-parses-renders-and-fails-as-recorded
  (let [want (:lockfile (expected))
        have (observed-lockfile)]
    (doseq [k (keys want)]
      (testing k (is (= (get want k) (get have k)))))
    (is (= (slurp (fixture "breto.lock")) (:rendered have)) "a rewritten lock file is byte for byte the one read")))

(deftest an-install-leaves-the-recorded-layout
  (s/with-root
    (fn []
      (let [want (:install (expected))
            have (observed-install)]
        (is (= (:install want) (:install have)))
        (is (= (:env-code want) (:env-code have)))
        (doseq [k (sort (into (set (keys (:tree want))) (keys (:tree have))))]
          (testing k (is (= (get-in want [:tree k]) (get-in have [:tree k])))))))))

(deftest a-recorded-layout-reads-back-as-recorded
  (s/with-root
    (fn []
      (let [record (expected)
            want (:reread record)
            have (observed-reread (get-in record [:install :tree]))]
        (doseq [k (keys (dissoc want :tree))]
          (testing k (is (= (get want k) (get have k)))))
        (doseq [k (sort (into (set (keys (:tree want))) (keys (:tree have))))]
          (testing k (is (= (get-in want [:tree k]) (get-in have [:tree k])))))))))

(deftest digests-naming-files-on-disk-are-as-recorded
  (is (= (:digests (expected)) (observed-digests))))
