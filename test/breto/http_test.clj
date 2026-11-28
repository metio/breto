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

(defn- with-servers
  "Calls `f` with the base URLs of local servers, one for each handler, so a
  redirect from one to another leaves the origin it started at."
  [handlers f]
  (let [servers (mapv #(httpkit/run-server % {:port 0 :legacy-return-value? false}) handlers)]
    (try
      (apply f (map #(str "http://127.0.0.1:" (httpkit/server-port %)) servers))
      (finally (run! httpkit/server-stop! servers)))))

(defn- recording
  "A handler that records each request's uri and headers in `seen` and
  answers with what `respond` returns for the request."
  [seen respond]
  (fn [req]
    (swap! seen conj (select-keys req [:uri :request-method :headers]))
    (respond req)))

(defn- problem-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def ^:private bearer
  ;; built at run time, so the source holds no token-shaped literal
  (str "Bearer " (apply str (repeat 8 "t"))))

(deftest text-is-the-body-of-a-successful-answer-to-the-headers-sent
  (let [seen (atom [])]
    (with-servers [(recording seen (fn [_] {:status 200 :body "größe 1.2.3"}))]
      (fn [a]
        (is (= "größe 1.2.3" (http/get-text (str a "/v") {"Accept" "application/json"})))
        (is (= "application/json" (get-in (first @seen) [:headers "accept"])))))))

(deftest a-status-outside-2xx-is-an-http-status-problem-with-its-rate-headers
  (with-servers [(fn [_] {:status 429 :headers {"Retry-After" "7" "X-Other" "x"} :body "slow down"})]
    (fn [a]
      (let [data (problem-of #(http/get-text (str a "/v") {}))]
        (is (= :http-status (:breto/problem data)))
        (is (= 429 (:status data)))
        (is (= {"retry-after" "7"} (:headers data)))
        (is (http/rate-limited? data))
        (is (<= 6 (http/upstream-wait) 7))))))

(deftest redirects-are-followed-and-keep-the-token-only-on-their-origin
  (let [seen (atom [])]
    (with-servers [(recording seen (fn [_] {:status 200 :body "far"}))]
      (fn [far]
        (with-servers [(recording seen (fn [{:keys [uri]}]
                                         (case uri
                                           "/same" {:status 302 :headers {"Location" "/final"}}
                                           "/final" {:status 200 :body "here"}
                                           "/cross" {:status 302 :headers {"Location" (str far "/asset")}}
                                           {:status 404})))]
          (fn [a]
            (is (= "here" (http/get-text (str a "/same") {"Authorization" bearer})))
            (is (= "far" (http/get-text (str a "/cross") {"Authorization" bearer})))
            (let [by-uri (group-by :uri @seen)]
              (is (= bearer (get-in (first (by-uri "/final")) [:headers "authorization"]))
                  "the origin the request started at keeps the token")
              (is (nil? (get-in (first (by-uri "/asset")) [:headers "authorization"]))
                  "another origin never receives it"))))))))

(deftest endless-redirects-fail
  (with-servers [(fn [_] {:status 302 :headers {"Location" "/loop"}})]
    (fn [a]
      (is (thrown? clojure.lang.ExceptionInfo (http/get-text (str a "/loop") {}))))))

(deftest existence-follows-redirects-to-another-host
  (with-servers [(fn [{:keys [uri]}] (if (= "/asset" uri) {:status 200 :body "x"} {:status 404}))]
    (fn [far]
      (with-servers [(fn [{:keys [uri]}]
                       (case uri
                         "/download" {:status 302 :headers {"Location" (str far "/asset")}}
                         "/gone" {:status 302 :headers {"Location" (str far "/missing")}}
                         {:status 404}))]
        (fn [a]
          (is (true? (http/exists? (str a "/download"))))
          (is (false? (http/exists? (str a "/gone"))))
          (is (false? (http/exists? (str a "/nothing")))))))))

(deftest gzipped-text-arrives-decoded
  (let [plain (.getBytes "{\"tag_name\":\"v1\"}" "UTF-8")]
    (serving plain #(re-find #"gzip" (str (get-in % [:headers "accept-encoding"])))
             (fn [url] (is (= "{\"tag_name\":\"v1\"}" (http/get-text url {})))))))

(deftest a-closed-port-is-a-network-problem
  (let [port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))
        url (str "http://127.0.0.1:" port "/v")]
    (is (= :network (:breto/problem (problem-of #(http/get-text url {})))))
    (is (= :network (:breto/problem (problem-of #(http/exists? url)))))))

(defn- with-raw-server
  "Calls `f` with the base URL of a local server that answers every request
  by calling `respond` with the request's first line and the output stream
  of its connection, for answers no HTTP server library would send."
  [respond f]
  (let [server (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
        worker (future
                 (loop []
                   (when-let [socket (try (.accept server) (catch java.io.IOException _ nil))]
                     (future
                       (with-open [socket socket]
                         (let [in (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream socket) "ISO-8859-1"))
                               line (.readLine in)]
                           (loop [] (when (seq (.readLine in)) (recur)))
                           (try (respond line (.getOutputStream socket)) (catch java.io.IOException _ nil)))))
                     (recur))))]
    (try
      (f (str "http://127.0.0.1:" (.getLocalPort server)))
      (finally (.close server) (future-cancel worker)))))

(defn- write-ascii [^java.io.OutputStream out ^String s]
  (.write out (.getBytes s "ISO-8859-1"))
  (.flush out))

(deftest existence-ignores-the-length-a-head-answer-states
  (with-raw-server (fn [_ out] (write-ascii out "HTTP/1.1 200 OK\r\nContent-Length: 734003200\r\nConnection: close\r\n\r\n"))
    (fn [a] (is (true? (http/exists? (str a "/big.tar.gz")))))))
