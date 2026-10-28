;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.share-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [breto.install :as install]
            [breto.package :as package]
            [breto.report :as report]
            [breto.share :as share]
            [breto.store :as store]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:dynamic *root* nil)

(use-fixtures :each
  (fn [t]
    (let [root (fs/create-temp-dir {:prefix "breto-test-"})]
      (try (binding [*root* root] (t))
           (finally (fs/delete-tree root))))))

(defn- at [& parts]
  (str (apply fs/path *root* parts)))

(defn- config []
  {:store (at "store") :bin-dir (at "bin") :man-dir (at "man")
   :completion-dirs {:bash (at "bash") :zsh (at "zsh") :fish (at "fish")}})

(defn- tarball!
  "Builds `name` holding `files` (path to content) under a top-level directory;
  files under bin/ are executable."
  [name files]
  (let [src (fs/path *root* "src" "release")
        out (fs/path *root* "assets" name)]
    (doseq [[path content] files
            :let [file (fs/path src path)]]
      (fs/create-dirs (fs/parent file))
      (spit (str file) content)
      (when (str/starts-with? path "bin/")
        (fs/set-posix-file-permissions file "rwxr-xr-x")))
    (fs/create-dirs (fs/parent out))
    (process/shell "tar" "--create" "--gzip" "--file" (str out)
                   "--directory" (str (fs/parent src)) "release")
    (fs/delete-tree (fs/path *root* "src"))
    out))

(defn- release-url [version file]
  (str "https://github.com/acme/tool/releases/download/v" version "/" file))

(defn- release [version]
  {"bin/tool" (str "#!/bin/sh\necho " version "\n")
   "share/man/tool.1.gz" (str "man " version)
   "share/man/tool-config.5" (str "config " version)
   "share/completions/tool.bash" (str "bash " version)
   "share/completions/_tool" (str "zsh " version)
   "README" "read me"})

(defn- serve [releases & {:keys [run-hook]}]
  (let [assets (into {} (for [[v files] releases]
                          [(release-url v (str "tool-" v ".tar.gz"))
                           (tarball! (str "tool-" v ".tar.gz") files)]))]
    (cond-> {:download! (fn [url dest] (fs/copy (get assets url) dest))
             :get-text (fn [url _]
                         (if (str/ends-with? url "/releases/latest")
                           (str "{\"tag_name\":\"v" (key (last releases)) "\"}")
                           (throw (ex-info (str "no text " url) {}))))}
      run-hook (assoc :run-hook run-hook))))

(defn- tool [extra]
  (package/normalize (merge {:name "tool" :source {:github "acme/tool"}
                             :asset "tool-{version}.tar.gz" :strip 1 :verify false
                             :bins {"tool" "bin/tool"}}
                            extra)))

(def ^:private shipped
  {:man ["share/man/*"]
   :completions {:bash "share/completions/tool.bash" :zsh "share/completions/_tool"}})

(defn- content [& parts]
  (slurp (apply at parts)))

(deftest the-binary-layout-links-shipped-man-pages-and-completions
  (let [cfg (config)]
    (install/install! cfg (serve (sorted-map "1.0.0" (release "1.0.0"))) (tool shipped) {:version "1.0.0"})
    (is (= "man 1.0.0" (content "man" "man1" "tool.1.gz")))
    (is (= "config 1.0.0" (content "man" "man5" "tool-config.5")))
    (is (= "bash 1.0.0" (content "bash" "tool")))
    (is (= "zsh 1.0.0" (content "zsh" "_tool")))
    (is (not (fs/exists? (at "fish" "tool.fish"))))
    (testing "only the named files are kept"
      (is (not (fs/exists? (fs/path (store/version-dir cfg "tool" "1.0.0") "README")))))
    (testing "links resolve through current"
      (is (str/includes? (str (fs/read-link (at "bash" "tool"))) "/current/")))))

(deftest the-tree-layout-links-from-the-kept-tree
  (let [cfg (config)]
    (install/install! cfg (serve (sorted-map "1.0.0" (release "1.0.0")))
                      (tool (assoc shipped :layout :tree)) {:version "1.0.0"})
    (is (= "man 1.0.0" (content "man" "man1" "tool.1.gz")))
    (is (= "bash 1.0.0" (content "bash" "tool")))))

(deftest switching-versions-switches-the-links-and-drops-dangling-ones
  (let [cfg (config)
        io (serve (sorted-map "1.0.0" (release "1.0.0")
                              "2.0.0" (dissoc (release "2.0.0") "share/completions/_tool")))
        v1 (tool shipped)
        v2 (tool (update shipped :completions dissoc :zsh))]
    (install/install! cfg io v1 {:version "1.0.0"})
    (install/install! cfg io v2 {:version "2.0.0"})
    (is (= "bash 2.0.0" (content "bash" "tool")))
    (is (not (fs/exists? (at "zsh" "_tool") {:nofollow-links true})))
    (install/use! cfg v1 "1.0.0")
    (is (= "bash 1.0.0" (content "bash" "tool")))
    (is (= "zsh 1.0.0" (content "zsh" "_tool")))))

(deftest uninstalling-removes-the-links
  (let [cfg (config)]
    (install/install! cfg (serve (sorted-map "1.0.0" (release "1.0.0"))) (tool shipped) {:version "1.0.0"})
    (let [links (store/links-into cfg "tool" [])]
      (is (some #(str/ends-with? % "man1/tool.1.gz") links))
      (is (some #(str/ends-with? % "bash/tool") links))
      (store/uninstall! cfg "tool" links))
    (is (empty? (fs/list-dir (at "bash"))))
    (is (empty? (fs/list-dir (at "man" "man1"))))))

(deftest files-breto-did-not-link-are-left-alone
  (let [cfg (config)]
    (fs/create-dirs (at "bash"))
    (spit (at "bash" "tool") "mine")
    (let [{:keys [warnings]} (install/install! cfg (serve (sorted-map "1.0.0" (release "1.0.0")))
                                               (tool shipped) {:version "1.0.0"})]
      (is (= "mine" (content "bash" "tool")))
      (is (= 1 (count warnings)))
      (is (str/includes? (first warnings) "was not linked by breto")))
    (is (not (some #(str/ends-with? % "bash/tool") (store/links-into cfg "tool" []))))))

(deftest a-named-completion-missing-from-the-release-fails-the-install
  (let [data (try (install/install! (config) (serve (sorted-map "1.0.0" (release "1.0.0")))
                                    (tool {:completions {:fish "share/completions/tool.fish"}}) {:version "1.0.0"})
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :path-missing (:breto/problem data)))
    (is (= "share/completions/tool.fish" (:path data)))))

(deftest completions-a-command-prints-are-generated-once-per-version
  (let [cfg (config)
        calls (atom [])
        run-hook (fn [{:keys [command env] :as call}]
                   (swap! calls conj call)
                   {:exit 0 :out (str (last command) " " (get env "COMPLETE" "-")) :err ""})
        pkg (tool {:completions {:bash {:env {"COMPLETE" "bash"}}
                                 :fish {:args ["completions" "fish"]}}})
        io (serve (sorted-map "1.0.0" (release "1.0.0")) :run-hook run-hook)]
    (install/install! cfg io pkg {:version "1.0.0"})
    (is (= [[(at "bin" "tool")] [(at "bin" "tool") "completions" "fish"]] (map :command @calls)))
    (is (= [{"COMPLETE" "bash"} {}] (map :env @calls)))
    (is (= (str (at "bin" "tool") " bash") (content "bash" "tool")))
    (is (= "fish -" (content "fish" "tool.fish")))
    (is (not (fs/exists? (at "zsh" "_tool"))))
    (install/install! cfg io pkg {:version "1.0.0"})
    (is (= 2 (count @calls)))))

(deftest failed-generation-warns-without-failing-the-install
  (let [cfg (config)
        pkg (tool {:completions {:bash {:args ["completion" "bash"]}
                                 :zsh {:args ["completion" "zsh"]}
                                 :fish {:args ["completion" "fish"]}}})
        outcomes {"bash" {:exit 2 :out "" :err "unknown command\n"}
                  "zsh" {:exit 0 :out "  \n" :err ""}
                  "fish" {:exit nil :out "" :err "" :timed-out true}}
        io (serve (sorted-map "1.0.0" (release "1.0.0"))
                  :run-hook (fn [{:keys [command]}] (get outcomes (last command))))
        {:keys [installed? warnings]} (install/install! cfg io pkg {:version "1.0.0"})]
    (is installed?)
    (is (= 3 (count warnings)))
    (is (some #(str/includes? % "bash completions of tool could not be generated: it exited with 2: unknown command") warnings))
    (is (some #(str/includes? % "it printed nothing") warnings))
    (is (some #(str/includes? % "ran longer than 30 seconds") warnings))
    (is (not (fs/exists? (at "bash" "tool"))))))

(deftest warnings-show-in-the-report
  (is (str/includes? (report/render {:command "update"
                                     :results [{:package "tool" :version "1.0.0" :status :installed
                                                :warnings ["bash completions of tool could not be generated: x"]}]})
                     "[WARN] tool: bash completions of tool could not be generated: x")))

(deftest the-command-and-sections-come-from-the-definition
  (is (= "tool" (share/command {:name "tool" :bins {"tool" "x" "aux" "y"}})))
  (is (= "aux" (share/command {:name "suite" :bins {"tool" "x" "aux" "y"}})))
  (is (= "tool" (share/command {:name "suite" :bins {"aux" "y"} :completions {:command "tool"}})))
  (is (= "1" (share/man-section "tool.1.gz")))
  (is (= "5" (share/man-section "tool-config.5")))
  (is (= "3" (share/man-section "Tool.3pm")))
  (is (nil? (share/man-section "README.md"))))

(deftest invalid-definitions-are-named
  (let [problems #(try (package/normalize (merge {:name "tool" :source {:github "acme/tool"} :asset "t"
                                                  :bins {"tool" "t"}}
                                                 %))
                       nil
                       (catch clojure.lang.ExceptionInfo e (str/join "\n" (:problems (ex-data e)))))]
    (is (nil? (problems shipped)))
    (is (str/includes? (problems {:man "share/man/*"}) ":man"))
    (is (str/includes? (problems {:man ["/usr/share/man/x.1"]}) ":man"))
    (is (str/includes? (problems {:completions {:nu "x"}}) ":completions"))
    (is (str/includes? (problems {:completions {:bash "../x"}}) ":completions"))
    (is (str/includes? (problems {:completions {:bash {:args "x"}}}) ":completions"))))

(deftest versions-installed-before-the-definition-named-completions-get-them
  (let [cfg (config)
        io (serve (sorted-map "1.0.0" (release "1.0.0")))]
    (install/install! cfg io (tool {}) {:version "1.0.0"})
    (is (not (fs/exists? (at "bash" "tool"))))
    (let [{:keys [installed?]} (install/install! cfg io (tool shipped) {:version "1.0.0"})]
      (is installed?))
    (is (= "bash 1.0.0" (content "bash" "tool")))
    (is (= "man 1.0.0" (content "man" "man1" "tool.1.gz")))
    (testing "and only once"
      (is (not (:installed? (install/install! cfg io (tool shipped) {:version "1.0.0"})))))))
