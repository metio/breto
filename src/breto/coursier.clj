;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.coursier
  "JVM applications from Maven Central, built into a standalone launcher with
  coursier. coursier resolves the module from Maven Central alone, checking
  every file against the checksum Central publishes beside it, into a cache of
  the install's own. The list of every resolved jar with its hash is what a
  lock file records, so a resolution elsewhere that differs in any jar fails.
  The launcher is then built from that cache without the network and embeds
  all jars, so it needs only a java to run."
  (:require [babashka.fs :as fs]
            [breto.checksum :as checksum]
            [breto.proc :as proc]
            [clojure.string :as str]))

(def repository "https://repo1.maven.org/maven2/")

(defn module-path
  "The directory of the module `group:artifact` on Maven Central."
  [module]
  (let [[group artifact] (str/split module #":")]
    (str repository (str/replace group "." "/") "/" artifact "/")))

(defn metadata-url [module]
  (str (module-path module) "maven-metadata.xml"))

(defn pom-url
  "The POM of `module` with `{version}` left as a placeholder."
  [module]
  (str (module-path module) "{version}/" (second (str/split module #":")) "-{version}.pom"))

(defn release-versions
  "The final releases the Maven metadata `xml` lists: none with a qualifier
  such as -RC1, -M2 or +snapshot."
  [xml]
  (->> (re-seq #"<version>\s*([^<\s]+)\s*</version>" xml)
       (map second)
       (filter #(re-matches #"\d+(\.\d+)*" %))))

(defn- resolution-options [cache]
  ["--cache" (str cache) "--no-default" "--repository" "central" "--checksum" "SHA-256,SHA-1"])

(defn fetch-arguments [spec cache]
  (into ["fetch"] (concat (resolution-options cache) [spec])))

(defn bootstrap-arguments [spec cache output {:keys [main java-opts]}]
  (-> ["bootstrap"]
      (into (resolution-options cache))
      (into ["--mode" "offline" "--standalone" "--deterministic" "--output" (str output)])
      (into (when main ["--main-class" main]))
      (into (mapcat #(vector "--java-opt" %) java-opts))
      (conj spec)))

(defn environment
  "Settings under `work` that keep the user's coursier, Maven and Scala CLI
  configuration, and the mirrors in it, out of the resolution."
  [work]
  {"COURSIER_CONFIG_DIR" (str (fs/path work "coursier-config"))
   "COURSIER_MIRRORS" (str (fs/path work "coursier-config" "mirror.properties"))
   "COURSIER_MAVEN_SETTINGS" "false"
   "SCALA_CLI_CONFIG" (str (fs/path work "coursier-config" "scala-cli.json"))})

(defn jar-listing
  "One line per jar in `jars`, the paths cs fetch printed, with its hash and
  its path inside `cache`, sorted, so the same resolution always gives the
  same text."
  [cache jars]
  (->> jars
       (map (fn [jar]
              (str (checksum/digest "SHA-256" jar) "  " (fs/relativize cache (fs/path jar)))))
       sort
       (map #(str % "\n"))
       (apply str)))

(defn build!
  "Resolves the release of `pkg` with `cs`, the path of a coursier command,
  and builds its launcher into `work`/built/bin, returning {:file :unpacked}:
  the jar listing and the directory to place."
  [io pkg release work cs context]
  (when-not cs
    (throw (ex-info (str (:name pkg) " is built with coursier, and no coursier is installed; install coursier first")
                    (assoc context :breto/problem :coursier-missing))))
  (let [source (:source pkg)
        spec (str (:coursier source) ":" (:version release))
        cache (fs/path work "coursier-cache")
        built (fs/path work "built")
        launcher (fs/path built "bin" (:name pkg))
        listing (fs/path work "jars.txt")
        env (environment work)
        run (or (:run-in io) proc/run-in)
        cs! (fn [args]
              (let [{:keys [exit out err]} (run {:command (cons cs args) :env env :dir work})]
                (when-not (zero? exit)
                  (throw (ex-info (str "cs " (first args) " " spec " failed: " (str/trim (str (if (str/blank? err) out err))))
                                  (assoc context :breto/problem :coursier-failed :command (vec (cons "cs" args))
                                         :output (str/trim (str err out))))))
                out))]
    (fs/create-dirs (fs/path work "coursier-config"))
    (fs/create-dirs (fs/parent launcher))
    (let [jars (remove str/blank? (map str/trim (str/split-lines (cs! (fetch-arguments spec cache)))))]
      (spit (str listing) (jar-listing cache jars)))
    (cs! (bootstrap-arguments spec cache launcher source))
    {:file listing :unpacked built}))
