;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.doctor
  "Checks of the environment, configuration and store, each with what to do
  when it fails."
  (:require [babashka.fs :as fs]
            [breto.github :as github]
            [breto.hooks :as hooks]
            [breto.report :as report]
            [breto.signature :as signature]
            [breto.store :as store]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- check [level title detail & advice]
  {:level level :title title :detail detail :advice (vec advice)})

(defn tools-check []
  (for [tool ["tar" "gzip" "xz" "bzip2"]]
    (if (fs/which tool)
      (check :ok (str tool " is installed") (str (fs/which tool)))
      (check (if (= "tar" tool) :fail :warn) (str tool " is missing")
             (str "Archives that need " tool " cannot be unpacked.")
             (str "Install " tool " with the system package manager.")))))

(defn- writable-dir-check [label dir]
  (cond
    (not (fs/exists? dir))
    (check :warn (str label " does not exist yet") dir
           "breto creates it on the first install; nothing to do unless that fails.")

    (not (fs/writable? dir))
    (check :fail (str label " is not writable") dir
           (str "Fix its ownership or permissions, e.g. chown -R \"$USER\" " dir))

    :else (check :ok (str label " is writable") dir)))

(defn dirs-check [{:keys [store bin-dir]}]
  (let [path-dirs (set (map #(str (fs/normalize (fs/absolutize %)))
                            (str/split (or (System/getenv "PATH") "") #":")))]
    [(writable-dir-check "The store" store)
     (writable-dir-check "The bin directory" bin-dir)
     (if (contains? path-dirs (str (fs/normalize (fs/absolutize bin-dir))))
       (check :ok "The bin directory is on PATH" bin-dir)
       (check :warn "The bin directory is not on PATH" bin-dir
              (str "Add it to PATH in the shell profile: export PATH=\"" bin-dir ":$PATH\"")))]))

(defn config-check [config-path {:keys [registries packages]} registry]
  (concat
   [(if (fs/exists? config-path)
      (check :ok "The configuration file exists" config-path)
      (check :warn "There is no configuration file" config-path
             "Without one, breto has no registries and no package list; see the README for its keys."))]
   (for [dir registries]
     (if (fs/directory? dir)
       (check :ok "A registry directory exists" dir)
       (check :fail "A registry directory is missing" dir
              "Fix the path under :registries, or clone the registry there.")))
   (when-let [unknown (seq (remove registry packages))]
     [(check :fail "Configured packages have no definition" (str/join ", " unknown)
             "Fix the names under :packages, or add definitions to a registry.")])))

(defn- dangling-links [dir]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (filter fs/sym-link?)
         (remove fs/exists?)
         (map str))
    []))

(defn store-check [{:keys [store bin-dir] :as config} registry]
  (let [installed (store/installed-packages config)
        orphans (remove registry installed)
        leftovers (for [name installed
                        entry (fs/list-dir (store/package-dir config name))
                        :when (re-find #"^\.(work|staging)-" (str (fs/file-name entry)))]
                    (str entry))
        dangling (filter #(str/starts-with? (str (fs/read-link %)) (str store))
                         (dangling-links bin-dir))]
    [(if (seq dangling)
       (check :fail "Links in the bin directory point at missing files" (str/join ", " dangling)
              "breto update reinstalls what the configuration lists; delete links for packages you no longer use.")
       (check :ok "Every link into the store resolves" bin-dir))
     (if (seq leftovers)
       (check :warn "Interrupted installs left work directories behind" (str/join ", " leftovers)
              "breto gc removes the ones no running install holds.")
       (check :ok "No interrupted installs" store))
     (if (seq orphans)
       (check :warn "The store holds packages no registry defines" (str/join ", " orphans)
              "These are usually renamed or dropped packages. Delete their directories once nothing links into them.")
       (check :ok "Every package in the store has a definition" store))]))

(defn unverified-check
  "Configured packages whose definition installs some versions without
  verification, with the boundary below which it does."
  [{:keys [packages]} registry]
  (let [exempt (for [name packages
                     :let [pkg (get registry name)]
                     :when pkg
                     :let [scope (if (false? (:verify pkg))
                                   "every version"
                                   (some #(when (false? (get-in % [:package :verify]))
                                            (str "versions before " (:before %)))
                                         (:history pkg)))]
                     :when scope]
                 (str name " (" scope ")"))]
    (if (seq exempt)
      (check :warn "Some packages install without verification" (str/join ", " exempt)
             "Their upstreams publish neither GitHub digests nor checksums for these versions."
             "Remove :verify false from a definition once upstream publishes either.")
      (check :ok "Every configured package is verified" ""))))

(defn signature-check
  "Whether cosign can run when a configured package requires a signature.
  `which` resolves a command name to its path."
  [{:keys [packages] :as config} registry which]
  (let [signed (filter #(get-in registry [% :signature]) packages)
        cosign (signature/locate config which)
        provider (first (filter #(some-> (get registry %) signature/provides-cosign?) packages))]
    (cond
      (empty? signed) (check :ok "No configured package requires a signature" "")
      cosign (check :ok (str "cosign checks the signatures of " (count signed) " packages") cosign)
      (and provider (nil? (:cosign config)))
      (check :warn "cosign is not installed yet" (str "Packages that require signatures: " (str/join ", " signed))
             (str "breto update installs " provider " first, checked by its checksum, and checks the other signatures with it."))
      :else (check :fail (str (or (:cosign config) "cosign") " is missing, but packages require signatures") (str/join ", " signed)
                   (if (:cosign config)
                     (str "Install cosign at " (:cosign config) ", or remove :cosign from the config to use the one breto installs.")
                     "Add \"cosign\" to :packages; breto installs it first and checks the other signatures with it.")))))

(defn hooks-check
  "Whether the configured hooks can run. `os` is the os.name property and
  `which` resolves a command name to its path."
  [config os which]
  (let [wrong (hooks/problems config)
        configured (:hooks config)
        notify (sort (keep (fn [[event hook]] (when (= :notify hook) (name event))) configured))
        notifier (hooks/notifier config os which)]
    (cond
      (seq wrong)
      (apply check :fail "The hook settings are invalid, so no hooks run" (str/join "; " wrong)
             ["Fix :hooks, :notifier, :messages or :hook-timeout in the config."])

      (empty? configured)
      (check :ok "No hooks are configured" "")

      (and (seq notify) (nil? notifier))
      (check :fail "No desktop notifier is available" (str "Hooks that notify: " (str/join ", " notify))
             (if (= :auto (:notifier config))
               (str "breto knows notify-send for Linux and BSD and osascript for macOS; on " os
                    " set :notifier to a command with {title} and {message}, or install the one for the system.")
               "Set :notifier to a command with {title} and {message}."))

      (and (seq notify) (not (which (first notifier))))
      (check :fail (str (first notifier) " is missing") (str "Hooks that notify: " (str/join ", " notify))
             "Install it, or set :notifier to another command.")

      :else
      (check :ok "Hooks run on these events" (str/join ", " (map name (sort (keys configured))))))))

(defn token-check
  "Where the GitHub token comes from, never the token itself."
  [{:keys [token-command]} io]
  (try
    (if-let [{:keys [source]} (some-> (:github-token io) (apply []))]
      (check :ok "A GitHub token is available" (str "from " source))
      (check :warn "No GitHub token is configured" "GitHub allows 60 unauthenticated API requests an hour."
             "Set :token-command in the config, e.g. [\"gh\" \"auth\" \"token\"] or [\"gopass\" \"show\" \"--password\" \"github/token\"]."
             "Or export GITHUB_TOKEN or GH_TOKEN."))
    (catch clojure.lang.ExceptionInfo e
      (check :fail "The :token-command fails" (str (ex-message e) (some->> (:stderr (ex-data e)) (str ": ")))
             (str "Run " (str/join " " (or token-command [])) " yourself and fix what it reports.")
             "It must print the token on its first line and exit with 0."))))

(defn github-check [io]
  (try
    (let [{:keys [rate]} (json/parse-string ((:get-text io) "https://api.github.com/rate_limit"
                                                            (github/api-headers io))
                                            true)
          {:keys [limit remaining]} rate]
      (cond
        (and (number? remaining) (zero? remaining))
        (check :fail "The GitHub API rate limit is used up" (str remaining " of " limit " left")
               "Set :token-command or GITHUB_TOKEN, or wait for the limit to reset.")

        (and (number? limit) (<= limit 60))
        (check :warn "GitHub API requests are unauthenticated" (str remaining " of " limit " left this hour")
               "Set :token-command or GITHUB_TOKEN; every verified GitHub download costs one request.")

        :else (check :ok "GitHub API requests are authenticated" (str remaining " of " limit " left"))))
    (catch clojure.lang.ExceptionInfo e
      (cond
        (= :token-command (:breto/problem (ex-data e)))
        (check :warn "The GitHub API was not checked" "The :token-command fails; see above.")

        (= 401 (:status (ex-data e)))
        (check :fail "GitHub rejects the token" (ex-message e)
               "The token has expired or was revoked; create a new one and store it where :token-command reads it.")

        :else
        (check :fail "GitHub's API cannot be reached" (ex-message e)
               "Check the network connection; installs from GitHub need api.github.com for verification.")))
    (catch Exception e
      (check :fail "GitHub's API cannot be reached" (ex-message e)
             "Check the network connection; installs from GitHub need api.github.com for verification."))))

(defn last-run-checks [runs]
  (if (seq runs)
    (for [{:keys [command results] :as run} runs
          :let [failed (filter #(= :failed (:status %)) results)]]
      (if (seq failed)
        (check :warn (str "The last breto " command " had failures")
               (str (report/heading run) "; failed: " (str/join ", " (map :package failed)))
               "breto report shows the diagnosis for each.")
        (check :ok (str "The last breto " command " succeeded") (report/heading run))))
    [(check :ok "No previous run recorded" (report/state-path))]))

(defn unconfigured-check [{:keys [packages prune] :as config}]
  (let [extra (store/prune-candidates (store/installed-packages config) packages)]
    (cond
      (empty? extra) (check :ok "The store holds only configured packages" (:store config))
      prune (check :ok "breto gc removes packages the configuration no longer lists" (str/join ", " extra))
      :else (check :warn "The store holds packages the configuration does not list" (str/join ", " extra)
                   "breto uninstall PKG removes one; :prune true in the config makes breto gc remove them all."))))

(defn run-checks [config-path config registry io]
  (concat (tools-check)
          (dirs-check config)
          (config-check config-path config registry)
          (store-check config registry)
          [(unconfigured-check config)
           (unverified-check config registry)
           (signature-check config registry #(some-> (fs/which %) str))
           (hooks-check config (System/getProperty "os.name") #(some-> (fs/which %) str))
           (token-check config io)
           (github-check io)]
          (last-run-checks (report/load-runs))))

(defn render [checks]
  (str/join "\n"
            (for [{:keys [level title detail advice]} checks]
              (str/join "\n"
                        (concat [(str ({:ok "[OK]  " :warn "[WARN]" :fail "[FAIL]"} level) " " title)]
                                (when (and detail (not= :ok level)) [(str "       " detail)])
                                (when (not= :ok level) (map #(str "       - " %) advice)))))))
