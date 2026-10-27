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

(defn- tokens [s]
  (set (remove str/blank? (str/split (str/lower-case (str s)) #"[-_.+/]+"))))

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
      (let [names (when repo (map :name (probe probes :assets repo tag)))
            near (closest asset names 3)]
        {:summary (str "The release " tag " has no asset named " asset ".")
         :facts (cond-> [(str "GET " url " returned 404")]
                  (seq near) (conj (str "Closest assets in " tag ": " (str/join ", " near))))
         :advice (cond-> []
                   (seq near)
                   (conj (str "If upstream renamed the asset, set :asset to "
                              (pr-str (as-template (first near) {:tag tag :version version})) "."))
                   true
                   (conj "If the new name only applies from this version on, keep the old :asset for older versions in a :history entry."
                         (str "Compare against the release page: https://github.com/" (or repo "OWNER/REPO") "/releases/tag/" tag)))})

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

(defmethod explain :network [{:keys [url exception]} _]
  {:summary "The server could not be reached."
   :facts [(str "GET " url " failed with " exception)]
   :advice ["Check the network connection, DNS and any VPN that is required."
            "A scheduled run needs Wants=network-online.target and After=network-online.target in its unit."]})

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

(defmethod explain :checksum-mismatch [{:keys [asset expected actual]} _]
  {:summary (str "The download of " asset " does not match its published checksum.")
   :facts [(str "Expected " expected) (str "Got      " actual)]
   :advice ["Retry later: an interrupted download or a stale mirror produces this."
            "If it persists, upstream replaced the file after publishing the checksum; report it to the project."
            "Do not bypass this with --no-verify: a mismatch is exactly what verification exists to catch."]})

(defmethod explain :checksum-format [{:keys [checksum]} _]
  {:summary "The checksum does not look like a SHA or MD5 hash."
   :facts [(str "Got " checksum)]
   :advice ["Check that :checksum names a checksum file and not a signature or another asset."]})

(defmethod explain :unpack-failed [{:keys [file stderr]} _]
  (let [missing (second (re-find #"(?i)(\w+): Cannot exec" (str stderr)))]
    {:summary (str "tar could not unpack " file ".")
     :facts [(str/trim (str stderr))]
     :advice (if missing
               [(str "Install " missing "; tar needs it for this archive format.")]
               ["The download may not be the archive its name suggests; check the asset in the release."
                "breto doctor checks that tar and its compressors are installed."])}))

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

(defmethod explain :invalid-package [{:keys [package problems]} _]
  {:summary (str "The definition of " package " is invalid.")
   :facts (vec problems)
   :advice ["Fix the definition; breto info PKG shows it once it loads."]})

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
