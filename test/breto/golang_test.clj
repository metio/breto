;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.golang-test
  (:require [babashka.fs :as fs]
            [breto.package :as package]
            [breto.store :as store]
            [breto.support :as s :refer [path]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- fake-go
  "A go command that answers `mod download -json` with a module hash naming
  the version, and for `install` writes a script into GOBIN, logging the
  environment it ran with."
  []
  (s/file! "go" (str "#!/bin/sh\n"
                     "echo \"GOTOOLCHAIN=$GOTOOLCHAIN CGO_ENABLED=$CGO_ENABLED GOFLAGS=$GOFLAGS\" >> " (path "go.log") "\n"
                     "case \"$1\" in\n"
                     "  mod) target=$4; printf '{\"Path\":\"%s\",\"Version\":\"%s\",\"Sum\":\"h1:%s\"}' \"${target%@*}\" \"${target#*@}\" \"${target#*@}\" ;;\n"
                     "  install) target=$3; pkg=${target%@*}; mkdir -p \"$GOBIN\"; printf '#!/bin/sh\\necho %s %s\\n' \"$pkg\" \"${target#*@}\" > \"$GOBIN/${pkg##*/}\" ;;\n"
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
         (:url (package/normalize {:name "tomlv" :source {:go "github.com/BurntSushi/toml" :package "github.com/BurntSushi/toml/cmd/tomlv"}}))))
  (is (= "tomlv" (:asset (package/normalize {:name "tomlv" :source {:go "github.com/BurntSushi/toml" :package "github.com/BurntSushi/toml/cmd/tomlv"}})))))

(deftest a-package-outside-the-module-is-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a package inside that module"
                        (package/normalize {:name "x" :source {:go "golang.org/x/vuln" :package "golang.org/x/tools/cmd/x"}}))))
