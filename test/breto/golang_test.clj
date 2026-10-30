;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.golang-test
  (:require [babashka.fs :as fs]
            [breto.package :as package]
            [breto.source :as source]
            [breto.store :as store]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- fake-go
  "A go command that answers `mod download -json` with a module hash naming
  the version, and for `install` writes a script into GOBIN named as go names
  binaries, after the last element that is not a major version, logging the
  environment it ran with."
  []
  (s/file! "go" (str "#!/bin/sh\n"
                     "echo \"GOTOOLCHAIN=$GOTOOLCHAIN CGO_ENABLED=$CGO_ENABLED GOFLAGS=$GOFLAGS\" >> " (path "go.log") "\n"
                     "case \"$1\" in\n"
                     "  mod) target=$4; printf '{\"Path\":\"%s\",\"Version\":\"%s\",\"Sum\":\"h1:%s\"}' \"${target%@*}\" \"${target#*@}\" \"${target#*@}\" ;;\n"
                     "  install) target=$3; pkg=${target%@*}; mkdir -p \"$GOBIN\"; name=${pkg##*/}; case \"$name\" in v[0-9]*) rest=${pkg%/*}; name=${rest##*/} ;; esac; printf '#!/bin/sh\\necho %s %s\\n' \"$pkg\" \"${target#*@}\" > \"$GOBIN/$name\" ;;\n"
                     "esac\n")))

(defn- world []
  (s/define! "go" {:source {:github "golang/go"} :asset "go" :verify false})
  (s/define! "govulncheck" {:source {:go "golang.org/x/vuln" :package "golang.org/x/vuln/cmd/govulncheck"}})
  (s/fake-io {"https://github.com/golang/go/releases/download/v1.0.0/go" (fake-go)}
             {"https://github.com/golang/go/releases/latest" "{\"tag_name\":\"v1.0.0\"}"
              "https://proxy.golang.org/golang.org/x/vuln/@latest" "{\"Version\":\"v1.9.0\"}"}
             (atom [])))

(deftest a-go-package-is-built-with-the-installed-go
  (let [io (world)]
    (let [{:keys [code out]} (s/breto! {} ["install" "govulncheck" "go"] {:io io})]
      (is (= 0 code) out)
      (testing "go is installed before what it builds"
        (is (< (str/index-of out "go 1.0.0") (str/index-of out "govulncheck 1.9.0")))))
    (is (= "#!/bin/sh\necho golang.org/x/vuln/cmd/govulncheck v1.9.0\n" (slurp (path "bin" "govulncheck"))))
    (is (= "h1:v1.9.0" (:sum (store/read-meta {:store (path "store")} "govulncheck" "1.9.0"))))
    (is (every? #(= "GOTOOLCHAIN=local CGO_ENABLED=0 GOFLAGS=" %) (str/split-lines (slurp (path "go.log")))))))

(deftest a-go-package-needs-go
  (let [io (world)
        {:keys [code out]} (s/breto! {} ["install" "govulncheck"] {:io io})]
    (is (= 1 code))
    (is (str/includes? out "no go is installed; install go first"))))

(deftest module-paths-are-escaped-for-the-proxy
  (is (= "github.com/!burnt!sushi/toml" (package/go-escape "github.com/BurntSushi/toml")))
  (is (= "https://proxy.golang.org/github.com/!burnt!sushi/toml/@v/{tag}.zip"
         (:url (s/normalize {:name "tomlv" :source {:go "github.com/BurntSushi/toml" :package "github.com/BurntSushi/toml/cmd/tomlv"}}))))
  (is (= "tomlv" (:asset (s/normalize {:name "tomlv" :source {:go "github.com/BurntSushi/toml" :package "github.com/BurntSushi/toml/cmd/tomlv"}})))))

(deftest a-package-outside-the-module-is-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a package inside that module"
                        (s/normalize {:name "x" :source {:go "golang.org/x/vuln" :package "golang.org/x/tools/cmd/x"}}))))

(deftest a-module-with-a-major-version-suffix-builds-its-binary
  (let [io (world)]
    (s/define! "bar" {:source {:go "github.com/foo/bar/v2"}})
    (let [io (update io :get-text (fn [get-text]
                                    (fn [url headers]
                                      (if (= url "https://proxy.golang.org/github.com/foo/bar/v2/@latest")
                                        "{\"Version\":\"v2.1.0\"}"
                                        (get-text url headers)))))
          {:keys [code out]} (s/breto! {} ["install" "go" "bar"] {:io io})]
      (is (= 0 code) out)
      (is (fs/exists? (path "bin" "bar"))))))

(deftest the-skip-fallback-ignores-go-pre-releases
  (let [pkg (s/normalize {:name "gopls" :source {:go "golang.org/x/tools/gopls"} :skip ["0.16.3"]})
        io {:get-text (fn [url _]
                        (case url
                          "https://proxy.golang.org/golang.org/x/tools/gopls/@latest" "{\"Version\":\"v0.16.3\"}"
                          "https://proxy.golang.org/golang.org/x/tools/gopls/@v/list" "v0.16.2\nv0.16.3\nv0.17.0-pre.1\nv0.0.0-20240101000000-abcdefabcdef\n"))}]
    (is (= "0.16.2" (:version (source/newest pkg io))))))
