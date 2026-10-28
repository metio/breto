;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.hooks-test
  (:require [breto.config :as config]
            [breto.doctor :as doctor]
            [breto.hooks :as hooks]
            [breto.proc :as proc]
            [breto.support :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each s/with-root)

(defn- with-defaults [m]
  (merge (select-keys (config/defaults) [:hooks :notifier :messages :hook-timeout]) m))

(def ^:private everything (constantly "/usr/bin/x"))

(deftest default-settings-are-valid
  (is (empty? (hooks/problems (with-defaults {})))))

(deftest invalid-settings-are-named
  (let [problems (hooks/problems (with-defaults {:hooks {:done ["x"] :installed "notify-send"}
                                                 :notifier "notify-send"
                                                 :messages {:installed 1}
                                                 :hook-timeout 0}))
        text (str/join "\n" problems)]
    (is (= 5 (count problems)))
    (is (str/includes? text ":hooks :done is not an event"))
    (is (str/includes? text ":hooks :installed must be :notify or a vector of strings"))
    (is (str/includes? text ":notifier must be"))
    (is (str/includes? text ":messages must map events to strings"))
    (is (str/includes? text ":hook-timeout"))))

(deftest auto-picks-the-notifier-of-the-operating-system
  (is (= "notify-send" (first (hooks/auto-notifier "Linux" everything))))
  (is (= "notify-send" (first (hooks/auto-notifier "FreeBSD" everything))))
  (is (= "osascript" (first (hooks/auto-notifier "Mac OS X" everything))))
  (is (nil? (hooks/auto-notifier "Windows 11" everything)))
  (is (nil? (hooks/auto-notifier "Linux" (constantly nil))))
  (is (= ["x"] (hooks/notifier {:notifier ["x"]} "Linux" (constantly nil)))))

(deftest hooks-get-values-as-separate-arguments-and-environment
  (let [version "1.0; rm -rf ~ $(id)"
        {:keys [command env]} (hooks/invocation {:hooks {:installed ["log" "{package}" "{version}"]}} nil :installed
                                                {:event "installed" :package "tool" :version version})]
    (is (= ["log" "tool" version] command))
    (is (= {"BRETO_EVENT" "installed" "BRETO_PACKAGE" "tool" "BRETO_VERSION" version} env))))

(deftest notify-renders-the-message-into-the-notifier
  (let [notifier ["notify" "{urgency}" "{title}" "{message}"]
        vars {:event "installed" :package "tool" :version "1.2"}]
    (is (= ["notify" "low" "breto" "tool 1.2 installed"]
           (:command (hooks/invocation {:hooks {:installed :notify}} notifier :installed vars))))
    (is (= ["notify" "low" "breto" "tool is now 1.2"]
           (:command (hooks/invocation {:hooks {:installed :notify} :messages {:installed "{package} is now {version}"}}
                                       notifier :installed vars))))
    (is (= "tool 1.2 installed"
           (get (:env (hooks/invocation {:hooks {:installed :notify}} notifier :installed vars)) "BRETO_MESSAGE")))))

(deftest events-without-a-hook-run-nothing
  (is (nil? (hooks/invocation {:hooks {:finished ["x"]}} nil :installed {}))))

(defn- problem [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest unknown-placeholders-and-missing-notifiers-are-problems
  (is (= :hook (:breto/problem (problem #(hooks/invocation {:hooks {:installed ["x" "{nope}"]}} nil :installed {})))))
  (is (:no-notifier (problem #(hooks/invocation {:hooks {:installed :notify}} nil :installed
                                                {:package "a" :version "1"})))))

(defn- kind-world []
  (let [binary (s/file! "kind" "#!/bin/sh\necho kind\n")
        url "https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-linux-amd64"]
    (s/define! "kind" {:source {:github "kubernetes-sigs/kind"} :asset "kind-linux-amd64"
                       :checksum "{asset}.sha256sum"})
    (s/define! "gone" {:source {:github "acme/gone"} :asset "gone"})
    (s/fake-io {url binary}
               {"https://github.com/kubernetes-sigs/kind/releases/latest" "{\"tag_name\":\"v0.33.0\"}"
                (str url ".sha256sum") (str (s/sha256 binary) "  kind-linux-amd64\n")}
               (atom []))))

(defn- update! [config run-hook]
  (let [err (java.io.StringWriter.)
        result (binding [*err* err]
                 (s/breto! (merge {:packages ["kind" "gone"]} config) ["update"]
                           {:io (assoc (kind-world) :run-hook run-hook)}))]
    (assoc result :err (str err))))

(deftest update-runs-hooks-per-package-and-once-at-the-end
  (let [calls (atom [])
        {:keys [code err]} (update! {:hooks {:installed ["on" "{package}" "{version}"]
                                             :failed ["fail" "{package}" "{stage}"]
                                             :finished ["end" "{installed}" "{failed}" "{summary}"]}}
                                    (fn [inv] (swap! calls conj inv) {:exit 0 :err ""}))]
    (is (= 1 code))
    (is (= [["on" "kind" "0.33.0"]
            ["fail" "gone" "resolve"]
            ["end" "1" "1" "breto update: 1 installed, 1 failed"]]
           (map :command @calls)))
    (is (every? #(= 30 (:timeout %)) @calls))
    (is (str/blank? err))))

(deftest failing-hooks-warn-without-failing-the-update
  (testing "a non-zero exit"
    (let [{:keys [code err out]} (update! {:packages ["kind"] :hooks {:installed ["on"]}}
                                          (constantly {:exit 3 :err "no session bus\n"}))]
      (is (= 0 code))
      (is (str/includes? out "[OK] kind 0.33.0 installed"))
      (is (str/includes? err "[WARN] the hook on exited with 3: no session bus"))))
  (testing "a timeout"
    (let [{:keys [err]} (update! {:packages ["kind"] :hooks {:finished ["on"]} :hook-timeout 5}
                                 (constantly {:exit nil :err "" :timed-out true}))]
      (is (str/includes? err "ran longer than 5 seconds"))))
  (testing "a program that cannot start"
    (let [{:keys [code err]} (update! {:packages ["kind"] :hooks {:finished ["on"]}}
                                      (fn [_] (throw (java.io.IOException. "Cannot run program \"on\""))))]
      (is (= 0 code))
      (is (str/includes? err "the finished hook did not run: Cannot run program")))))

(deftest invalid-settings-disable-hooks-with-one-warning
  (let [calls (atom 0)
        {:keys [code err]} (update! {:packages ["kind"] :hooks {:installed "on"}}
                                    (fn [_] (swap! calls inc) {:exit 0 :err ""}))]
    (is (= 0 code))
    (is (zero? @calls))
    (is (= 1 (count (re-seq #"\[WARN\]" err))))
    (is (str/includes? err "no hooks run: :hooks :installed must be"))))

(deftest hooks-are-real-processes-with-the-values-in-their-environment
  (is (= 0 (:exit (proc/run-hook {:command ["sh" "-c" "test \"$BRETO_PACKAGE\" = kind"]
                                  :env {"BRETO_PACKAGE" "kind"} :timeout 10}))))
  (is (= 1 (:exit (proc/run-hook {:command ["sh" "-c" "test \"$BRETO_PACKAGE\" = kind"]
                                  :env {"BRETO_PACKAGE" "other"} :timeout 10}))))
  (let [started (System/nanoTime)
        result (proc/run-hook {:command ["sleep" "30"] :env {} :timeout 1})]
    (is (:timed-out result))
    (is (< (/ (- (System/nanoTime) started) 1e9) 10))))

(deftest doctor-checks-that-hooks-can-run
  (let [level #(:level (doctor/hooks-check (with-defaults %1) %2 %3))]
    (is (= :ok (level {} "Linux" (constantly nil))))
    (is (= :ok (level {:hooks {:finished :notify}} "Linux" everything)))
    (is (= :fail (level {:hooks {:finished :notify}} "Linux" (constantly nil))))
    (is (= :fail (level {:hooks {:finished :notify}} "Windows 11" everything)))
    (is (= :ok (level {:hooks {:finished :notify} :notifier ["toast" "{message}"]} "Windows 11" everything)))
    (is (= :fail (level {:hooks {:finished :notify} :notifier ["toast" "{message}"]} "Windows 11" (constantly nil))))
    (is (= :fail (level {:hooks {:done ["x"]}} "Linux" everything)))
    (is (= :ok (level {:hooks {:finished ["logger" "{summary}"]}} "Windows 11" (constantly nil))))))
