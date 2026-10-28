;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.http-test
  (:require [breto.diagnose :as diagnose]
            [breto.http :as http]
            [clojure.test :refer [deftest is]]))

(deftest exhausted-limits-wait-until-the-reset
  (is (= 600 (http/rate-wait {"x-ratelimit-remaining" "0" "x-ratelimit-reset" "1600"} 1000))))

(deftest remaining-requests-need-no-wait
  (is (= 0 (http/rate-wait {"x-ratelimit-remaining" "12" "x-ratelimit-reset" "1600"} 1000)))
  (is (= 0 (http/rate-wait {} 1000))))

(deftest retry-after-is-honoured
  (is (= 90 (http/rate-wait {"retry-after" "90"} 1000)))
  (is (= 600 (http/rate-wait {"retry-after" "90" "x-ratelimit-remaining" "0" "x-ratelimit-reset" "1600"} 1000))))

(deftest resets-in-the-past-need-no-wait
  (is (= 0 (http/rate-wait {"x-ratelimit-remaining" "0" "x-ratelimit-reset" "900"} 1000))))

(deftest http-dates-in-retry-after-are-ignored
  (is (= 0 (http/rate-wait {"retry-after" "Wed, 28 Oct 2026 07:28:00 GMT"} 1000))))

(deftest only-refusals-naming-a-wait-count-as-rate-limited
  (is (http/rate-limited? {:breto/problem :http-status :status 429 :headers {"retry-after" "5"}}))
  (is (not (http/rate-limited? {:breto/problem :http-status :status 403 :headers {}})))
  (is (not (http/rate-limited? {:breto/problem :http-status :status 404 :headers {"retry-after" "5"}}))))

(deftest broken-connections-during-a-download-are-network-problems
  (let [in (proxy [java.io.InputStream] []
             (read
               ([] (throw (java.io.IOException. "connection reset")))
               ([_] (throw (java.io.IOException. "connection reset")))
               ([_ _ _] (throw (java.io.IOException. "connection reset")))))
        data (try (#'http/copy-body! "https://x/y" in (java.io.ByteArrayOutputStream.) nil 10) nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :network (:breto/problem data)))
    (is (= 0 (:received data)))
    (is (re-find #"broke off" (:summary (diagnose/diagnose (ex-info "x" data) {}))))))

(deftest complete-bodies-report-their-length
  (let [out (java.io.ByteArrayOutputStream.)
        seen (atom [])]
    (is (= 5 (#'http/copy-body! "u" (java.io.ByteArrayInputStream. (.getBytes "hello")) out
                                #(swap! seen conj [%1 %2]) 5)))
    (is (= "hello" (str out)))
    (is (= [[5 5]] @seen))))
