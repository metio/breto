;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.http-test
  (:require [babashka.fs :as fs]
            [breto.diagnose :as diagnose]
            [breto.http :as http]
            [clojure.test :refer [deftest is]]
            [org.httpkit.server :as httpkit]))

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

(defn- gzipped [^bytes plain]
  (let [out (java.io.ByteArrayOutputStream.)]
    (with-open [gz (java.util.zip.GZIPOutputStream. out)] (.write gz plain))
    (.toByteArray out)))

(defn- serving
  "Calls `f` with the URL of a local server answering every request with
  `plain`, gzip-encoded whenever `gzip?` holds for the request, and stating
  the length of the bytes it sends."
  [plain gzip? f]
  (let [server (httpkit/run-server
                (fn [req]
                  (let [body (if (gzip? req) (gzipped plain) plain)]
                    {:status 200
                     :headers (cond-> {"Content-Length" (str (count body))}
                                (gzip? req) (assoc "Content-Encoding" "gzip"))
                     :body (java.io.ByteArrayInputStream. body)}))
                {:port 0 :legacy-return-value? false})]
    (try
      (f (str "http://127.0.0.1:" (httpkit/server-port server) "/file"))
      (finally (httpkit/server-stop! server)))))

(deftest downloads-are-the-bytes-the-server-holds-whatever-encoding-it-offers
  (let [plain (.getBytes (apply str (repeat 60000 "a")))
        dest (str (fs/create-temp-file))]
    (try
      (doseq [[what gzip? total] [["a server gzipping on request" #(re-find #"gzip" (str (get-in % [:headers "accept-encoding"]))) 60000]
                                  ["a server gzipping every response" (constantly true) nil]]]
        (serving plain gzip?
                 (fn [url]
                   (let [seen (atom nil)]
                     (http/download! url dest #(reset! seen [%1 %2]))
                     (is (= (seq plain) (seq (fs/read-all-bytes dest))) what)
                     (is (= [60000 total] @seen) what)))))
      (finally (fs/delete-if-exists dest)))))
