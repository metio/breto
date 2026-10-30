;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.search-test
  (:require [babashka.fs :as fs]
            [breto.doctor :as doctor]
            [breto.registry :as registry]
            [breto.search :as search]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(def ^:private registry
  {"yq" {:name "yq" :description "yq is a portable command-line YAML processor" :source {:github "mikefarah/yq"}}
   "jq" {:name "jq" :description "Command-line JSON processor" :source {:github "jqlang/jq"}}
   "kind" {:name "kind" :description "Kubernetes IN Docker" :source {:github "kubernetes-sigs/kind"}}
   "kubectl" {:name "kubectl" :source {:text "https://dl.k8s.io/release/stable.txt"}}})

(deftest matching-looks-at-name-description-and-source
  (is (= ["jq" "yq"] (map :name (search/matches registry ["processor"]))))
  (is (= ["yq"] (map :name (search/matches registry ["YAML" "portable"]))))
  (is (= ["kind"] (map :name (search/matches registry ["kubernetes-sigs"]))))
  (is (= ["kubectl"] (map :name (search/matches registry ["dl.k8s.io"]))))
  (is (= 4 (count (search/matches registry []))))
  (is (empty? (search/matches registry ["nothing-like-this"]))))

(deftest rows-are-one-line-each
  (is (= "jq\tCommand-line JSON processor\tjqlang/jq" (search/row (registry "jq"))))
  (is (= "x\ta b\tacme/x" (search/row {:name "x" :description "a\n\tb" :source {:github "acme/x"}})))
  (is (= "kubectl\t\thttps://dl.k8s.io/release/stable.txt" (search/row (registry "kubectl")))))

(deftest the-finder-is-fzf-then-sk-from-the-bin-dir-or-path
  (let [bin (path "bin")
        on-path (fn [& names] #(when ((set names) %) (str "/usr/bin/" %)))]
    (is (= ["fzf"] (search/finder {:finder :auto :bin-dir bin} (on-path "fzf" "sk"))))
    (is (= ["sk"] (search/finder {:finder :auto :bin-dir bin} (on-path "sk"))))
    (is (nil? (search/finder {:finder :auto :bin-dir bin} (on-path))))
    (testing "one breto linked but that is not on PATH yet"
      (fs/create-dirs bin)
      (spit (path "bin" "sk") "#!/bin/sh\n")
      (fs/set-posix-file-permissions (path "bin" "sk") "rwxr-xr-x")
      (is (= [(path "bin" "sk")] (search/finder {:finder :auto :bin-dir bin} (on-path)))))
    (testing "a configured finder"
      (is (= ["peco"] (search/finder {:finder ["peco"]} (on-path "peco"))))
      (is (nil? (search/finder {:finder ["peco"]} (on-path))))
      (is (nil? (search/finder {:finder nil} (on-path "fzf")))))))

(deftest finder-invocations
  (is (= ["fzf" "--multi" "--delimiter" "\t" "--with-nth" "1,2" "--prompt" "breto install> " "--query" "kube"]
         (search/finder-command {:finder :auto} ["fzf"] "kube")))
  (is (= ["peco" "--prompt" "x"] (search/finder-command {:finder ["peco" "--prompt" "x"]} ["peco" "--prompt" "x"] "kube")))
  (is (= ["jq" "yq"] (search/selected "jq\tCommand-line JSON processor\tjqlang/jq\nyq\t\t\n")))
  (is (= [] (search/selected ""))))

(defn- answers [& lines]
  (let [left (atom lines)
        asked (atom [])]
    [(fn [question] (swap! asked conj question) (let [[a & more] @left] (reset! left more) a))
     asked]))

(deftest the-prompt-searches-lists-and-reads-numbers
  (let [[ask asked] (answers "processor" "2 1 2")
        out (with-out-str (is (= ["yq" "jq"] (search/prompt! registry ask nil))))]
    (is (str/includes? out "  1) jq - Command-line JSON processor"))
    (is (str/includes? out "  2) yq - yq is a portable"))
    (is (= 2 (count @asked))))
  (testing "another search, numbers out of range, and a blank answer"
    (let [[ask] (answers "nothing-like-this" "kind" "kube" "7 1")]
      (with-out-str (is (= ["kind"] (search/prompt! registry ask nil))))))
  (let [[ask] (answers "")]
    (with-out-str (is (= [] (search/prompt! registry ask "kind")))))
  (let [[ask] (answers nil)]
    (is (nil? (search/prompt! registry ask nil))))
  (testing "long lists say how many more there are"
    (is (str/includes? (last (search/numbered (for [i (range 35)] {:name (str "p" i)}))) "and 5 more"))))

(defn- world []
  (doseq [name ["alpha" "beta"]]
    (s/define! name {:description (str name " does things") :source {:github (str "acme/" name)} :asset name :verify false}))
  (s/fake-io (into {} (for [name ["alpha" "beta"]]
                        [(str "https://github.com/acme/" name "/releases/download/v1.0.0/" name)
                         (s/file! name (str "#!/bin/sh\necho " name "\n"))]))
             (into {} (for [name ["alpha" "beta"]]
                        [(str "https://github.com/acme/" name "/releases/latest") "{\"tag_name\":\"v1.0.0\"}"]))
             (atom [])))

(deftest install-without-packages-installs-what-the-finder-returns
  (let [calls (atom [])
        io (assoc (world)
                  :interactive? true
                  :which #(when (= "fzf" %) "/usr/bin/fzf")
                  :pick (fn [command input]
                          (swap! calls conj [command input])
                          {:exit 0 :out "beta\tbeta does things\tacme/beta\n"}))
        {:keys [code out]} (s/breto! {:packages ["alpha"] :prune true} ["install" "--query" "be"] {:io io})]
    (is (= 0 code) out)
    (is (= "be" (last (ffirst @calls))))
    (is (= "alpha\talpha does things\tacme/alpha\nbeta\tbeta does things\tacme/beta\n" (second (first @calls))))
    (is (fs/exists? (path "bin" "beta")))
    (is (str/includes? out "add \"beta\" to keep them"))
    (is (str/includes? out "breto gc uninstalls them"))))

(deftest install-without-packages-and-without-a-finder-prompts
  (let [[ask] (answers "does" "1")
        io (assoc (world) :interactive? true :ask ask)
        {:keys [code out]} (s/breto! {:packages []} ["install"] {:io io})]
    (is (= 0 code) out)
    (is (fs/exists? (path "bin" "alpha")))
    (is (not (fs/exists? (path "bin" "beta"))))))

(deftest choosing-nothing-installs-nothing
  (let [io (assoc (world) :interactive? true :which (constantly "/usr/bin/fzf")
                  :pick (constantly {:exit 130 :out ""}))
        {:keys [code out]} (s/breto! {:packages []} ["install"] {:io io})]
    (is (= 0 code))
    (is (str/includes? out "Nothing chosen."))))

(deftest install-without-packages-needs-a-terminal
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"run it in a terminal"
                        (s/breto! {:packages []} ["install"] {:io (world)}))))

(deftest search-prints-the-matches
  (world)
  (is (= "beta\tbeta does things\tacme/beta\n" (:out (s/breto! {} ["search" "beta"]))))
  (is (str/includes? (:out (s/breto! {} ["search" "gamma"])) "No package matches gamma.")))

(deftest overlays-with-another-source-conflict
  (let [overlay (path "overlay")]
    (s/define! "yq" {:source {:github "mikefarah/yq"} :asset "yq_linux_amd64"})
    (fs/create-dirs overlay)
    (testing "a key-by-key override keeps the source"
      (spit (path "overlay" "yq.edn") (pr-str {:checksum "checksums"}))
      (is (= {:github "mikefarah/yq"} (:source (get (s/load-registry [(path "packages") overlay]) "yq")))))
    (testing "another project under the same name"
      (spit (path "overlay" "yq.edn") (pr-str (s/everywhere {:source {:github "kislyuk/yq"} :asset "yq"})))
      (let [data (try (s/load-registry [(path "packages") overlay]) nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :invalid-package (:breto/problem data)))
        (is (= [(path "packages" "yq.edn") (path "overlay" "yq.edn")] (:files data)))
        (is (str/includes? (first (:problems data)) ":replace true"))))
    (testing "a replacement stands alone"
      (spit (path "overlay" "yq.edn") (pr-str (s/everywhere {:replace true :source {:github "kislyuk/yq"} :asset "yq"
                                                             :bins {"yq" "yq"}})))
      (let [yq (get (s/load-registry [(path "packages") overlay]) "yq")]
        (is (= {:github "kislyuk/yq"} (:source yq)))
        (is (= "yq" (:asset yq)))
        (is (not (contains? yq :replace)))))))

(deftest doctor-names-the-finder
  (is (= :ok (:level (doctor/finder-check {:finder :auto :bin-dir (path "bin")} #(when (= "sk" %) "/usr/bin/sk")))))
  (is (= :warn (:level (doctor/finder-check {:finder :auto :bin-dir (path "bin")} (constantly nil)))))
  (is (str/includes? (:detail (doctor/finder-check {:finder ["peco"]} (constantly nil))) "peco from :finder")))

(deftest superseded-packages-are-marked
  (is (= "exa\tls replacement (superseded by eza)\togham/exa"
         (search/row {:name "exa" :description "ls replacement" :source {:github "ogham/exa"} :superseded-by "eza"})))
  (is (= "exa\t(superseded by eza)\togham/exa"
         (search/row {:name "exa" :source {:github "ogham/exa"} :superseded-by "eza"}))))
