;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.diagnose
  "Turns the data a failure carries into an explanation and what to do about
  it. Follow-up lookups come in as `probes`, so a diagnosis can be computed
  offline; a probe that fails is ignored rather than hiding the original error."
  (:require [breto.github :as github]
            [breto.package :as package]
            [clojure.string :as str])
  (:import (java.time Instant)))

(defn github-probes [io]
  {:repo (fn [repo] (github/repo-info io repo))
   :assets (fn [repo tag] (:assets (github/release io repo tag)))})

(defn- probe [probes k & args]
  (when-let [f (get probes k)]
    (try (apply f args) (catch Exception _ nil))))

(def ^:private platform-aliases
  "Spellings of one platform, so x86_64 and amd64 count as the same token."
  [[#"x86[-_]64|x64" "amd64"] [#"aarch64" "arm64"] [#"i[36]86|x86(?![-_]?64)" "386"]
   [#"macos|osx|apple-darwin" "darwin"]])

(defn- tokens [s]
  (let [canonical (reduce (fn [t [re to]] (str/replace t re to)) (str/lower-case (str s)) platform-aliases)]
    (set (remove str/blank? (str/split canonical #"[-_.+/]+")))))

(defn similarity [a b]
  (let [x (tokens a) y (tokens b)
        union (count (into x y))]
    (if (zero? union) 0 (/ (count (filter x y)) union))))

(defn closest
  "Up to `n` of `candidates` that look most like `target`."
  [target candidates n]
  (->> candidates
       (map (fn [c] [c (similarity target c)]))
       (filter (comp pos? second))
       (sort-by (comp - second))
       (take n)
       (map first)))

(defn as-template
  "Writes `asset-name` as an :asset template by putting placeholders where the
  tag or version appear."
  [asset-name {:keys [tag version]}]
  (cond-> asset-name
    (seq tag) (str/replace tag "{tag}")
    (and (seq version) (not (str/includes? asset-name "{tag}"))) (str/replace version "{version}")))

(def ^:private checksum-like #"(?i)(sha256|sha512|checksum|sums?$|\.sha\d*$|\.sha\d*sum$)")

(defn suggest-tag-prefix
  "The text in front of the first digit of `tag`."
  [tag]
  (second (re-matches #"(.*?)(\d.*)" tag)))

(defn suggest-strip
  "A :strip value under which a file named like `path` would sit where :bins
  expects it, given the archive `listing` after the current `strip`."
  [listing path strip]
  (let [wanted (package/file-name path)
        depth (count (str/split path #"/"))]
    (some (fn [entry]
            (let [parts (str/split entry #"/")]
              (when (and (= wanted (last parts)) (> (count parts) depth))
                (+ (or strip 0) (- (count parts) depth)))))
          listing)))

(defn- executables-like [listing]
  (->> listing
       (remove #(re-find #"(?i)\.(md|txt|1|html|json|yaml|yml|toml|sh|bash|zsh|fish|ps1|png|svg)$|LICENSE|README|CHANGELOG|completions?" %))
       (take 8)))

(defn- epoch->instant [s]
  (try (str (Instant/ofEpochSecond (parse-long s))) (catch Exception _ nil)))

(defn- rate-limited? [{:keys [status headers url]}]
  (and (#{403 429} status)
       (or (= "0" (get headers "x-ratelimit-remaining"))
           (some? (get headers "retry-after"))
           (str/includes? (str url) "api.github.com"))))

(defn- github-repo [{:keys [source]}]
  (:github source))

(defmulti explain
  "A map of :summary, :facts and :advice for one failure."
  (fn [data _] (:breto/problem data)))

(defn- missing-asset
  "The explanation for a release without the expected asset, given the names
  of the assets it has."
  [{:keys [asset tag version repo names fact]}]
  (let [near (closest asset names 3)]
    {:summary (str "The release " tag " has no asset named " asset ".")
     :facts (cond-> [fact]
              (seq near) (conj (str "Closest assets in " tag ": " (str/join ", " near))))
     :advice (cond-> []
               (seq near)
               (conj (str "If upstream renamed the asset, set :asset to "
                          (pr-str (as-template (first near) {:tag tag :version version})) "."))
               (and (seq names) (empty? near))
               (conj (str "None of its " (count names) " assets looks similar; upstream may have stopped building for this platform."
                          " :skip [" (pr-str version) "] installs the newest release before it instead."))
               true
               (conj "If the new name only applies from this version on, keep the old :asset for older versions in a :history entry."
                     (str "Compare against the release page: https://github.com/" (or repo "OWNER/REPO") "/releases/tag/" tag)))}))

(defn- http-explanation [{:keys [status url stage asset tag version] :as data} probes]
  (let [repo (github-repo data)]
    (cond
      (rate-limited? data)
      {:summary "GitHub's API refused the request because the rate limit is used up."
       :facts (cond-> [(str "GET " url " returned " status)]
                (get-in data [:headers "x-ratelimit-reset"])
                (conj (str "The limit resets at " (epoch->instant (get-in data [:headers "x-ratelimit-reset"])))))
       :advice ["Set :token-command in the config, e.g. [\"gh\" \"auth\" \"token\"]; authenticated requests get 5000 an hour instead of 60."
                "Or export GITHUB_TOKEN or GH_TOKEN, e.g. with an EnvironmentFile= for the systemd service."
                "Otherwise the next run after the reset time succeeds without changes."]}

      (>= (or status 0) 500)
      {:summary "The server answered with an error of its own; this is usually temporary."
       :facts [(str "GET " url " returned " status)]
       :advice ["Nothing to change locally. The next scheduled run retries."
                "If it persists for days, check the project's status page or issue tracker."]}

      (and (= :resolve stage) repo (#{404 406} status))
      (let [info (probe probes :repo repo)]
        (cond
          (nil? info)
          {:summary (str "The repository " repo " does not exist on GitHub or is private.")
           :facts [(str "GET " url " returned " status)]
           :advice ["Check whether the project moved to another owner or another host such as GitLab."
                    "Point :source at its new home; a :history entry keeps older versions installable from the old one."]}

          (not= (str/lower-case repo) (str/lower-case (str (:full_name info))))
          {:summary (str "The repository moved from " repo " to " (:full_name info) ".")
           :facts [(str "GET " url " returned " status)
                   "GitHub does not redirect releases/latest for a renamed repository."]
           :advice [(str "Change :source to {:github \"" (:full_name info) "\"} in the package definition.")]}

          :else
          {:summary (str repo " has no release marked as latest.")
           :facts [(str "GET " url " returned " status)]
           :advice ["Projects that only publish pre-releases have no latest release."
                    "Install a specific version with breto install PKG@VERSION, or pin one under :pins."]}))

      (and (= :download stage) (= 404 status))
      (missing-asset {:asset asset :tag tag :version version :repo repo
                      :names (when repo (map :name (probe probes :assets repo tag)))
                      :fact (str "GET " url " returned 404")})

      (and (= :verify stage) (= 404 status))
      (let [assets (when repo (probe probes :assets repo tag))
            digest? (some #(and (= asset (:name %)) (:digest %)) assets)
            sums (filter #(re-find checksum-like %) (map :name assets))]
        {:summary "The checksum file the package names does not exist for this release."
         :facts (cond-> [(str "GET " url " returned 404")]
                  (seq sums) (conj (str "Checksum-like assets in " tag ": " (str/join ", " sums))))
         :advice (cond-> []
                   digest? (conj "GitHub records a digest for this asset, so removing :checksum from the definition is enough.")
                   (seq sums) (conj (str "Otherwise set :checksum to " (pr-str (as-template (first sums) {:tag tag :version version})) "."))
                   (and (not digest?) (empty? sums)) (conj "Upstream stopped publishing checksums; removing :checksum makes breto refuse unverifiable downloads instead."))})

      :else
      {:summary (str "The request was refused with status " status ".")
       :facts [(str "GET " url " returned " status)]
       :advice ["Open the URL in a browser to see what the server says."
                "Rerun with --debug to see every request breto makes."]})))

(defmethod explain :http-status [data probes]
  (http-explanation data probes))

(defmethod explain :network [{:keys [url exception received expected]} _]
  (if received
    {:summary (str "The download broke off after " received (when expected (str " of " expected)) " bytes.")
     :facts [(str "GET " url " failed with " exception)]
     :advice ["Run the command again; nothing was installed from the partial download."
              "If it keeps breaking off, check for a proxy or a flaky link, or raise :delay to spread the traffic."]}
    {:summary "The server could not be reached."
     :facts [(str "GET " url " failed with " exception)]
     :advice ["Check the network connection, DNS and any VPN that is required."
              "A scheduled run needs Wants=network-online.target and After=network-online.target in its unit."]}))

(defmethod explain :tag-prefix [{:keys [tag tag-prefix]} _]
  (let [suggested (suggest-tag-prefix tag)]
    {:summary (str "The release tag " tag " does not start with the :tag-prefix " (pr-str tag-prefix) ".")
     :facts [(str "breto reads the version as the tag minus :tag-prefix.")]
     :advice [(if suggested
                (str "Set :tag-prefix " (pr-str suggested) " in the package definition.")
                "Set :tag-prefix to the text that precedes the version in the tag.")
              "If only older releases used the old prefix, keep it for them in a :history entry."]}))

(defmethod explain :no-release [{:keys [url]} _]
  {:summary "The release lookup answered without a tag."
   :facts [(str "GET " url " returned no tag_name")]
   :advice ["Open the URL to see what the server returns; the project may have changed how it publishes releases."]})

(defmethod explain :rolling-no-match [{:keys [repo tag asset]} probes]
  (let [names (map :name (probe probes :assets repo tag))]
    {:summary (str "No asset in the rolling release " tag " matches " asset ".")
     :facts (cond-> [] (seq names) (conj (str "Assets include: " (str/join ", " (take 5 (closest asset names 5))))))
     :advice ["Adjust :asset so that {version} stands where the build identifier appears in the asset names."]}))

(defmethod explain :no-checksum [{:keys [repo tag asset] :as data} probes]
  (let [sums (when (github-repo data)
               (filter #(re-find checksum-like %) (map :name (probe probes :assets repo tag))))]
    {:summary (str "There is nothing to verify " asset " against.")
     :facts (cond-> ["GitHub recorded no digest for this asset, and the package names no :checksum file."]
              (seq sums) (conj (str "Checksum-like assets in " tag ": " (str/join ", " sums))))
     :advice (cond-> []
               (seq sums) (conj (str "Set :checksum to " (pr-str (as-template (first sums) {:tag tag :version (:version data)})) "."))
               true (conj "If upstream publishes no checksums at all, breto install --no-verify installs it while accepting the risk."))}))

(defmethod explain :checksum-entry-missing [{:keys [asset url entries]} _]
  {:summary (str "The checksum file does not list " asset ".")
   :facts (cond-> [(str "Checked " url)]
            (seq entries) (conj (str "It lists: " (str/join ", " (closest asset entries 5)))))
   :advice ["If the file lists the asset under another name, the asset itself may have been renamed; check :asset."
            "If the checksum file covers other assets only, point :checksum at the right one."]})

(defmethod explain :checksum-mismatch [{:keys [asset expected actual origin]} _]
  (if (= :lockfile origin)
    {:summary (str "The download of " asset " differs from the one the lock file recorded.")
     :facts [(str "Locked   " expected) (str "Got      " actual)]
     :advice ["Upstream replaced the asset after it was locked, or the download was corrupted; retry first."
              "If upstream confirms the new file, breto update without --locked installs it and records its hash."
              "Do not delete the entry from the lock file to make this go away: this is what it is there to catch."]}
  {:summary (str "The download of " asset " does not match its published checksum.")
   :facts [(str "Expected " expected) (str "Got      " actual)]
   :advice ["Retry later: an interrupted download or a stale mirror produces this."
            "If it persists, upstream replaced the file after publishing the checksum; report it to the project."
            "Do not bypass this with --no-verify: a mismatch is exactly what verification exists to catch."]}))

(defmethod explain :checksum-format [{:keys [checksum]} _]
  {:summary "The checksum does not look like a SHA or MD5 hash."
   :facts [(str "Got " checksum)]
   :advice ["Check that :checksum names a checksum file and not a signature or another asset."]})

(defmethod explain :unpack-failed [{:keys [file stderr escaping]} _]
  (if (seq escaping)
    {:summary (str file " holds entries that would unpack outside of its directory, so nothing was unpacked.")
     :facts [(str "Entries: " (str/join ", " escaping))]
     :advice ["Do not install this archive; an entry like ../file can overwrite files anywhere you can write."
              "Report it upstream, and compare the download with the release page."]}
  (let [missing (second (re-find #"(?i)(\w+): Cannot exec" (str stderr)))]
    {:summary (str "Could not unpack " file ".")
     :facts [(str/trim (str stderr))]
     :advice (if missing
               [(str "Install " missing "; tar needs it for this archive format.")]
               ["The download may not be the archive its name suggests; check the asset in the release."
                "breto doctor checks that tar and its compressors are installed."])})))

(defmethod explain :path-missing [{:keys [path listing strip asset]} _]
  (let [strip-hint (suggest-strip listing path strip)
        exes (executables-like listing)]
    {:summary (str asset " does not contain " path " where :bins expects it.")
     :facts (cond-> [(str "Unpacked with :strip " (or strip 0) ", the archive holds " (count listing) " files.")]
              (seq exes) (conj (str "Likely binaries: " (str/join ", " exes))))
     :advice (cond-> []
               strip-hint (conj (str "Set :strip " strip-hint "; the file sits deeper in the archive."))
               (and (not strip-hint) (seq exes)) (conj (str "Point :bins at the binary, e.g. {\"" (package/file-name path) "\" " (pr-str (first exes)) "}."))
               true (conj "If the layout changed with this release, keep the old values for older versions in a :history entry."))}))

(defmethod explain :filesystem [{:keys [exception path]} _]
  {:summary "A file operation failed."
   :facts [(str exception ": " path)]
   :advice [(cond
              (re-find #"AccessDenied" (str exception)) "Check that the store and bin directories belong to the user running breto."
              (re-find #"FileSystemException|IOException" (str exception)) "Check free space with df on the store's filesystem."
              :else "Check the store and bin directories.")
            "breto doctor checks both directories."]})

(defmethod explain :unknown-package [{:keys [package known]} _]
  (let [near (closest package known 3)]
    {:summary (str "No registry defines a package called " package ".")
     :facts (cond-> [] (seq near) (conj (str "Similar names: " (str/join ", " near))))
     :advice ["Fix the name in the command or in :packages of the config."
              "Or add a definition for it to one of the :registries directories."]}))

(defmethod explain :invalid-package [{:keys [package problems files]} _]
  {:summary (str "The definition of " package " is invalid.")
   :facts (cond-> (vec problems)
            (seq files) (conj (str "Defined in: " (str/join ", " files))))
   :advice ["Fix the definition; breto info PKG shows it once it loads."
            "Every command loads all definitions, so one broken file stops them all; breto report and breto doctor still run."]})

(defmethod explain :not-installed [{:keys [package version installed]} _]
  {:summary (str package " " version " is not installed.")
   :facts [(str "Installed versions: " (if (seq installed) (str/join ", " installed) "none"))]
   :advice [(str "breto install " package "@" version " installs it first.")]})

(defmethod explain :not-in-store [{:keys [package store installed]} _]
  (let [near (closest package installed 3)]
    {:summary (str "The store at " store " has no package called " package ".")
     :facts (cond-> [] (seq near) (conj (str "Similar installed packages: " (str/join ", " near))))
     :advice ["breto list shows what is installed."]}))

(defmethod explain :still-configured [{:keys [package]} _]
  {:summary (str package " is listed under :packages, so the next breto update installs it again.")
   :facts []
   :advice [(str "Remove " package " from :packages in the config; with :prune true, breto gc then removes it.")
            (str "Or run breto uninstall --force " package " to remove it until the next update.")]})

(defmethod explain :prune-blocked [{:keys [package blocking]} _]
  {:summary (str package " was kept: configured packages still run commands from it.")
   :facts (vec (for [[link owner] blocking]
                 (str link " points into " package " but belongs to " owner)))
   :advice [(str "breto update installs " (str/join ", " (distinct (map second blocking)))
                 " and takes the links over; the next breto gc then removes " package ".")
            "If that update fails, its own diagnosis says why."]})

(defmethod explain :all-skipped [{:keys [package latest skip source]} _]
  {:summary (str "The latest release of " package ", " latest ", is listed in :skip, and no older release is left to install.")
   :facts [(str ":skip " (pr-str skip)) (str "Source: " (pr-str source))]
   :advice [(if (or (:github source) (:gitlab source))
              "Remove a version from :skip, or pin a version under :pins in the config."
              "This source lists no older releases; pin a version under :pins in the config.")]})

(defmethod explain :token-command [{:keys [command exit stderr error empty]} _]
  {:summary "The :token-command that provides the GitHub token failed, so no GitHub API request was made."
   :facts (cond-> [(str "Command: " (str/join " " command))]
            exit (conj (str "Exit code: " exit))
            (not (str/blank? stderr)) (conj (str "It reported: " stderr))
            error (conj (str "It could not start: " error))
            empty (conj "It printed nothing."))
   :advice [(str "Run " (str/join " " command) " yourself; it must print the token on its first line and exit with 0.")
            "Under systemd the command runs without your interactive session; a password manager may need its agent or an unlocked store."
            "Remove :token-command to fall back to 60 unauthenticated requests an hour."]})

(defmethod explain :asset-missing [{:keys [asset tag version repo assets]} _]
  (missing-asset {:asset asset :tag tag :version version :repo repo :names assets
                  :fact (str "The release lists " (count assets) " assets, none named " asset)}))

(defmethod explain :unverifiable [{:keys [package version asset tag assets suggested-before]} _]
  (let [sums (filter #(re-find checksum-like %) assets)]
    {:summary (str "Nothing can verify " asset " of " package " " version ", so breto update refuses to install it.")
     :facts (cond-> ["GitHub records no digest for it, and the definition names no :checksum."]
              (seq sums) (conj (str "Checksum-like assets in " tag ": " (str/join ", " sums))))
     :advice (cond-> []
               (seq sums) (conj (str "Set :checksum to " (pr-str (as-template (first sums) {:tag tag :version version})) "."))
               true (conj (str "If upstream publishes nothing to verify against, add {:before " (pr-str suggested-before)
                               " :verify false} to :history; newer releases stay verified.")
                          "GitHub only records digests for assets uploaded since mid-2025, so a new release usually fixes this."))}))

(defmethod explain :json-path [{:keys [url path found keys]} _]
  {:summary (str "The JSON document at " url " has no version at " (pr-str path) ".")
   :facts [(str "The path exists as far as " (pr-str found))
           (str "Keys there: " (if (seq keys) (str/join ", " keys) "none"))]
   :advice ["Fix :version under :source to the path of the version in the document."
            (str "Inspect the document with: curl --silent '" url "' | jq .")]})

(defmethod explain :no-lockfile [{:keys [path]} _]
  {:summary (if path (str "--locked found no lock file at " path ".") "--locked needs a lock file, and the config names none.")
   :facts []
   :advice ["Set :lockfile in the config to the file's path."
            "breto writes it after every install, update, use, gc and uninstall; run one without --locked to create it."]})

(defmethod explain :not-locked [{:keys [package path]} _]
  {:summary (str package " has no entry in the lock file " path ".")
   :facts []
   :advice [(str "breto update " package " without --locked installs its latest release and adds it to the lock file.")
            "Then commit the lock file so other machines install the same version."]})

(defmethod explain :lockfile-format [{:keys [path error]} _]
  {:summary (str "The lock file " path " cannot be read.")
   :facts (cond-> [] error (conj error))
   :advice ["It must hold one EDN map of package names to {:version … :sha256 …}, after optional ; comment lines."
            "Restore it from version control; breto rewrites it after the next change to the store."]})

(defmethod explain :signature-tool [{:keys [cosign error package]} _]
  {:summary (str "The definition of " package " requires a sigstore signature, and " cosign " could not run to check it.")
   :facts [(str "Error: " error)]
   :advice [(if (= "cosign" package)
              "To bootstrap cosign itself, install it once with breto install cosign --no-verify, or from the system package manager."
              "Install cosign, e.g. by adding \"cosign\" to :packages; breto installs packages in order, so list it first.")
            "Set :cosign in the config to its path if it is not on PATH."]})

(defmethod explain :bundle-missing [{:keys [package version bundle assets]} _]
  {:summary (str "Release " version " of " package " has no signature bundle named " bundle ".")
   :facts (cond-> [] (seq assets) (conj (str "Signature-like assets in the release: "
                                             (str/join ", " (or (seq (filter #(re-find #"(?i)sig|bundle|\\.pem$" %) assets)) ["none"])))))
   :advice ["Correct :bundle in the definition if upstream renamed it."
            "If upstream stopped signing, or started later, scope :signature with a :history entry."]})

(defmethod explain :signature-invalid [{:keys [signed bundle-url identity issuer stderr]} _]
  {:summary (str "The sigstore signature of " signed " does not verify.")
   :facts (cond-> [(str "Bundle: " bundle-url) (str "Expected signer: " identity " via " issuer)]
            (not (str/blank? stderr)) (conj (str "cosign: " stderr)))
   :advice ["If cosign names another signer, upstream changed its release workflow; compare with the project's documentation before changing :identity."
            "A signature that verifies for nobody means the download or the bundle was altered: do not install it."
            "Do not bypass this with --no-verify unless upstream confirms the change."]})

(defmethod explain :unsafe-name [{:keys [kind value]} _]
  {:summary (str "The " (name kind) " " (pr-str value) " cannot name a directory in the store.")
   :facts ["Names and versions must be one path segment: not empty, no slash or control character, no leading dot or surrounding space."]
   :advice (if (= :version kind)
             ["Check the version the source reports, e.g. with breto info PKG and the :source URL; adjust :tag-prefix or the :json :version path."]
             ["Name the package as breto list shows it."])})

(defmethod explain :usage [_ _]
  {:summary "The command line is incomplete."
   :facts []
   :advice ["breto help lists every command with its arguments."]})

(defmethod explain :no-previous [{:keys [package active installed]} _]
  {:summary (str "No installed version of " package " is older than the active " (or active "one") ".")
   :facts [(str "Installed versions: " (if (seq installed) (str/join ", " installed) "none"))]
   :advice [(str "breto install " package "@VERSION installs an older release, which then becomes active.")
            "breto gc keeps only :keep versions; raise :keep to keep more to roll back to."]})

(defmethod explain :default [_ _]
  {:summary "breto failed in a way it has no explanation for."
   :facts []
   :advice ["Rerun with --debug for every request and the full stack trace."
            "Please report it with that output."]})

(defn diagnose
  "The explanation for a failure, for `ex-info` data or any exception."
  [e probes]
  (let [data (if (instance? clojure.lang.ExceptionInfo e) (ex-data e) {})
        explanation (try (explain data probes)
                         (catch Exception _ (explain {} probes)))]
    (assoc explanation
           :message (ex-message e)
           :problem (:breto/problem data)
           :stage (:stage data))))
