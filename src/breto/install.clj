;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.install
  (:require [babashka.fs :as fs]
            [breto.archive :as archive]
            [breto.checksum :as checksum]
            [breto.github :as github]
            [breto.log :as log]
            [breto.package :as package]
            [breto.share :as share]
            [breto.signature :as signature]
            [breto.source :as source]
            [breto.store :as store]
            [clojure.string :as str]))

(defn resolve-release
  "The tag and version to install: the requested `version`, or the newest one
  `:skip` does not name."
  [pkg io version]
  (if version
    {:tag (package/tag-of-version (package/at pkg version) version) :version version}
    (source/newest pkg io)))

(defn github-asset?
  "Whether `artifact` is an asset of a GitHub release of `pkg`, so the
  GitHub API knows its digest."
  [pkg artifact]
  (when-let [repo (get-in pkg [:source :github])]
    (str/starts-with? (:url artifact) (str "https://github.com/" repo "/releases/download/"))))

(defn expected-hash
  "The hash a download must match: the digest GitHub recorded for a release
  asset, else the entry in the package's checksum file, else nil. A failed
  GitHub lookup only falls through when a checksum file can stand in."
  [pkg io release artifact]
  (or (when (github-asset? pkg artifact)
        (try
          (github/asset-digest (github/release io (get-in pkg [:source :github]) (:tag release))
                               (:asset artifact))
          (catch clojure.lang.ExceptionInfo e
            (when-not (:checksum-url artifact)
              (throw e)))))
      (when-let [url (:checksum-url artifact)]
        (let [text ((:get-text io) url {})]
          (or (checksum/expected text (:asset artifact))
              (throw (ex-info (str "no entry for " (:asset artifact) " in " url)
                              {:breto/problem :checksum-entry-missing :url url :asset (:asset artifact)
                               :entries (checksum/listed-names text)})))))))

(defn with-stage
  "Runs `f`, adding `stage` and `context` to anything it throws. Data from the
  failure itself wins, so the innermost stage is the one reported."
  [stage context f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (ex-message e) (merge context {:stage stage} (ex-data e)) e)))
    (catch java.io.IOException e
      (throw (ex-info (str (or (ex-message e) (.getName (class e))))
                      (merge context {:stage stage :breto/problem :filesystem
                                      :exception (.getName (class e)) :path (ex-message e)})
                      e)))))

(defn- staged
  "Reports `stage` to the progress callback of `io`, if any, then runs `f`
  within it."
  [io stage context f]
  (when-let [progress (:progress io)]
    (progress {:type :stage :stage stage}))
  (with-stage stage context f))

(defn- signed-checksum!
  "Downloads the checksum file a signature covers into `work`, so the bytes
  cosign checks are the ones the expected hash is read from."
  [io artifact work context]
  (let [file (fs/path work ".checksums")]
    (staged io :verify context #((:download! io) (:checksum-url artifact) file))
    file))

(defn- which [io]
  (or (:which io) #(some-> (fs/which %) str)))

(defn- verify-signature!
  "Checks the release's sigstore bundle for `target` with cosign, returning
  :checked. Without any cosign, a package that provides cosign itself is
  installed on its checksum alone and :bootstrap is returned; any other
  signed package fails."
  [config io pkg artifact target work context]
  (let [sig (:signature artifact)
        bundle (fs/path work ".bundle.sigstore.json")
        cosign (signature/locate config (which io))]
    (staged io :signature context
      #(cond
         cosign
         (do ((:download! io) (:bundle-url sig) bundle)
             (signature/verify! (:run io) cosign sig target bundle
                                (assoc context :signed (str (fs/file-name target)) :bundle-url (:bundle-url sig)))
             :checked)

         (signature/provides-cosign? artifact)
         :bootstrap

         :else
         (throw (ex-info (str (or (:cosign config) "cosign") " is not installed, so the signature of " (:name pkg)
                              " cannot be checked")
                         (assoc context :breto/problem :signature-tool :cosign (or (:cosign config) "cosign")
                                :missing true :bin-dir (:bin-dir config))))))))

(defn- fetch!
  "Downloads and verifies the artifact into `work`, returning the file and
  whether a signature was verified."
  [config pkg io release artifact work verify? context]
  (let [file (fs/path work (:asset artifact))
        sig (:signature artifact)
        outcome (volatile! nil)]
    (staged io :download context #((:download! io) (:url artifact) file))
    (when verify?
      (let [checksums (when (= :checksum (:of sig)) (signed-checksum! io artifact work context))
            signature (when sig
                        (verify-signature! config io pkg artifact (or checksums file) work
                                           (assoc context :signed-what (if checksums :checksum :asset))))]
        (vreset! outcome signature)
        (staged io :verify context
          #(if-let [want (if checksums
                           (or (checksum/expected (slurp (str checksums)) (:asset artifact))
                               (throw (ex-info (str "no entry for " (:asset artifact) " in " (:checksum-url artifact))
                                               {:breto/problem :checksum-entry-missing :url (:checksum-url artifact)
                                                :asset (:asset artifact)
                                                :entries (checksum/listed-names (slurp (str checksums)))})))
                           (expected-hash pkg io release artifact))]
             (checksum/verify! file (:asset artifact) want)
             (throw (ex-info (str (:name pkg) ": nothing to verify " (:asset artifact)
                                  " against; GitHub has no digest for it and the package names no :checksum."
                                  " Mark the release {:verify false} in :history, or use --no-verify once.")
                             {:breto/problem :no-checksum :package (:name pkg) :asset (:asset artifact)}))))))
    {:file file :signed? (= :checked @outcome) :bootstrap? (= :bootstrap @outcome)}))

(defn- record-upstream-hash!
  "Records the hash upstream publishes for an installed version that has none
  yet, which is the hash its verified download had to match. Returns it, or
  nil when there is none or the lookup fails, so a later run tries again."
  [config pkg io release artifact]
  (let [meta (store/read-meta config (:name pkg) (:version release))]
    (when-not (:sha256 meta)
      (when-let [sha256 (try (expected-hash pkg io release artifact)
                             (catch Exception e
                               (log/debug "no upstream hash for" (:name pkg) (:version release) (ex-message e))
                               nil))]
        (store/write-meta! config (:name pkg) (:version release)
                           (merge meta {:sha256 sha256 :asset (:asset artifact) :sha256-source :upstream}))
        sha256))))

(defn- keep-share-files!
  "Copies the man pages and shipped completions of `pkg` from `unpacked` to
  the same paths under `dest`. A completion file the definition names must
  exist; a man page glob may match nothing."
  [pkg artifact unpacked dest]
  (doseq [path (filter string? (vals (select-keys (:completions pkg) share/shells)))]
    (when-not (fs/regular-file? (fs/path unpacked path))
      (throw (ex-info (str (:name pkg) ": " path " is not in " (:asset artifact))
                      {:breto/problem :path-missing :package (:name pkg) :path path
                       :asset (:asset artifact) :strip (:strip pkg)
                       :listing (archive/listing unpacked 200)}))))
  (doseq [pattern (share/kept-files pkg)
          file (fs/glob unpacked pattern)
          :when (fs/regular-file? file)
          :let [target (fs/path dest (fs/relativize unpacked file))]]
    (fs/create-dirs (fs/parent target))
    (fs/copy file target {:replace-existing true})))

(defn- share!
  "Generates the completions of the active `version` that a command prints,
  then links its man pages and completions. Returns the warnings of both."
  [config io pkg version]
  (let [command (share/command pkg)
        program (when command (fs/path (:bin-dir config) command))
        generated (when (and (:run-hook io) program (fs/executable? program))
                    (share/generate! config pkg program (store/version-dir config (:name pkg) version) (:run-hook io)))]
    (concat generated (store/link-share! config pkg version))))

(defn- place!
  "Copies what `pkg` keeps from the unpacked archive into `dest`."
  [{:keys [layout] :as pkg} artifact unpacked dest]
  (if (= :tree layout)
    (fs/move unpacked dest)
    (do (fs/create-dirs dest)
        (doseq [[path stored] (package/stored-files pkg artifact)]
          (let [src (fs/path unpacked path)]
            (when-not (fs/regular-file? src)
              (throw (ex-info (str (:name pkg) ": " path " is not in " (:asset artifact))
                              {:breto/problem :path-missing :package (:name pkg) :path path
                               :asset (:asset artifact) :strip (:strip pkg)
                               :listing (archive/listing unpacked 200)})))
            (fs/copy src (fs/path dest stored))))
        (keep-share-files! pkg artifact unpacked dest)))
  (doseq [path (distinct (vals (package/bin-paths pkg artifact)))]
    (fs/set-posix-file-permissions (fs/path dest path) "rwxr-xr-x")))

(defn install!
  "Installs `pkg` at `version` (latest when nil) and activates it unless
  `:activate?` is false. The download is verified unless `verify?` is false
  or the definition for that version says `:verify false`; `expect-sha256`,
  from a lock file, must match it in any case. Returns {:version v
  :installed? bool :verified? bool :sha256 hex}, the hash only for a fresh
  install, :signed? when a signature was checked, :bootstrap? when cosign
  itself was installed before any cosign could check it, and :hash-recorded?
  when an installed version without a recorded hash got upstream's. Failures carry the stage and release they happened in."
  [config io pkg {:keys [version activate? verify? expect-sha256] :or {activate? true verify? true}}]
  (let [context {:package (:name pkg) :repo (package/repo pkg) :source (:source pkg)}
        release (staged io :resolve context #(resolve-release pkg io version))
        version (:version release)
        pkg (package/at pkg version)
        verify? (and verify? (:verify pkg true))
        artifact (package/artifact pkg release)
        context (assoc context :version version :tag (:tag release) :asset (:asset artifact)
                       :url (:url artifact) :checksum-url (:checksum-url artifact)
                       :repo (package/repo pkg) :source (:source pkg))
        package-dir (store/package-dir config (:name pkg))
        bin-paths (package/bin-paths pkg artifact)
        fresh? (not (store/installed? config (:name pkg) version (vals bin-paths)))
        {:keys [sha256 signed? bootstrap?]} (when fresh?
                 (with-stage :place context #(fs/create-dirs package-dir))
                 (let [work (fs/create-temp-dir {:dir package-dir :prefix store/work-prefix})
                       lock (store/hold! work)]
                   (try
                     (let [{:keys [file signed? bootstrap?]} (fetch! config pkg io release artifact work verify? context)
                           sha256 (checksum/digest "SHA-256" file)
                           unpacked (fs/path work "unpacked")
                           staging (fs/path work "staged")]
                       (when expect-sha256
                         (staged io :verify context
                           #(checksum/verify! file (:asset artifact) expect-sha256 :lockfile)))
                       (staged io :unpack context
                         #(archive/unpack! file (:archive artifact) (:asset artifact) unpacked (:strip pkg)))
                       (staged io :place context
                         #(do (place! pkg artifact unpacked staging)
                              (fs/delete-tree (store/version-dir config (:name pkg) version))
                              (fs/move staging (store/version-dir config (:name pkg) version) {:atomic-move true})
                              (store/write-meta! config (:name pkg) version
                                                 (cond-> {:sha256 sha256 :asset (:asset artifact) :verified verify? :signed signed?}
                                                   bootstrap? (assoc :signature-bootstrap true)))))
                       {:sha256 sha256 :signed? signed? :bootstrap? bootstrap?})
                     (finally
                       (fs/delete-tree work)
                       (.close lock)
                       (when (empty? (fs/list-dir package-dir))
                         (fs/delete package-dir))))))]
    (let [warnings (when activate?
                     (staged io :activate context
                       #(do (store/activate! config pkg bin-paths version)
                            (vec (share! config io pkg version)))))
          recorded (when (and (not fresh?) verify?)
                     (record-upstream-hash! config pkg io release artifact))]
      (cond-> {:version version :installed? fresh? :verified? (and fresh? verify?)}
        sha256 (assoc :sha256 sha256)
        signed? (assoc :signed? true)
        bootstrap? (assoc :bootstrap? true)
        recorded (assoc :hash-recorded? true)
        (seq warnings) (assoc :warnings (vec (distinct warnings)))))))

(defn bin-targets
  "Command name -> the file in the store that `version` of `pkg` runs, or nil
  when that version is not installed completely."
  [config pkg version]
  (let [pkg (package/at pkg version)
        artifact (package/artifact pkg {:tag (package/tag-of-version pkg version) :version version})
        bin-paths (package/bin-paths pkg artifact)
        dir (store/version-dir config (:name pkg) version)]
    (when (store/installed? config (:name pkg) version (vals bin-paths))
      (update-vals bin-paths #(str (fs/path dir %))))))

(defn use!
  "Activates an already installed version."
  [config pkg version]
  (let [pkg (package/at pkg version)
        artifact (package/artifact pkg {:tag (package/tag-of-version pkg version) :version version})
        bin-paths (package/bin-paths pkg artifact)]
    (when-not (store/installed? config (:name pkg) version (vals bin-paths))
      (throw (ex-info (str (:name pkg) " " version " is not installed")
                      {:breto/problem :not-installed :package (:name pkg) :version version
                       :installed (mapv :version (store/versions config (:name pkg)))})))
    (store/activate! config pkg bin-paths version)))
