;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.http
  "Requests to registries, forges and download hosts. Lookups go through
  briko.http, which bounds each one by deadlines and a body limit. Downloads
  stream to a file through babashka.http-client, because briko.http holds a
  body in memory and a release asset can be far larger than that should be."
  (:require [babashka.http-client :as http-client]
            [breto.log :as log]
            [briko.http :as http]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset StandardCharsets)
           (java.util.zip GZIPInputStream)))

(def max-body-bytes
  "The largest body a lookup reads, after decoding. The largest document breto
  fetched for a definition of the package registry on 2026-11-28 was the
  abbreviated npm document of renovate, 37695413 bytes; GitHub release
  listings reached 11055127 bytes (sigstore/cosign). The limit leaves room
  for those to grow while keeping a hostile server from filling memory."
  (* 128 1024 1024))

(def ^:private lookup-options
  "The bounds of every lookup. Connecting takes milliseconds to the hosts
  breto talks to, so 10 s only cuts off a host that does not answer. Five
  minutes carry the largest body measured, 37.7 MB, at 1 Mbit/s even where
  the host does not compress it (gzipped it is 2.5 MB). Five redirects are as
  many as the client followed before, enough for a release download that
  redirects to another host."
  {:connect-timeout-ms 10000
   :timeout-ms 300000
   :max-bytes max-body-bytes
   :max-redirects 5})

(def ^:private head-options
  "A HEAD answer states the length of the body a GET would send, which for a
  release asset is far beyond `max-body-bytes`, while it sends no body at
  all. briko.http refuses a stated length above :max-bytes before reading,
  so existence checks allow the largest it accepts."
  (assoc lookup-options :max-bytes (- Integer/MAX_VALUE 9)))

(def ^:private unreachable
  "The refusals of briko.http that say the host was not reached in time."
  #{::http/connect-failed ::http/connect-timeout ::http/tls-failed ::http/timeout
    ::http/failed ::http/interrupted})

(def ^:private kept-headers
  ["x-ratelimit-remaining" "x-ratelimit-reset" "retry-after"])

(defn- now []
  (quot (System/currentTimeMillis) 1000))

(defn rate-wait
  "Seconds a host asks for before its next request, read from one response's
  headers at `now` (epoch seconds): its retry-after, or the time until its
  rate limit resets once no requests remain."
  [headers now]
  (let [number #(some-> (get headers %) str parse-long)
        retry (number "retry-after")
        reset (when (= 0 (number "x-ratelimit-remaining")) (number "x-ratelimit-reset"))]
    (max 0 (or retry 0) (if reset (- reset now) 0))))

(defonce ^:private not-before
  (atom {}))

(defn upstream-wait
  "Seconds until every host contacted so far accepts requests again."
  []
  (max 0 (- (reduce max 0 (vals @not-before)) (now))))

(defn rate-limited?
  "Whether failure data is a host refusing requests for now and naming a wait."
  [{:keys [status headers] :as data}]
  (and (= :http-status (:breto/problem data))
       (contains? #{403 429} status)
       (pos? (rate-wait headers (now)))))

(defn- note-wait! [url headers]
  (let [t (now)]
    (swap! not-before assoc (.getHost (java.net.URI. url)) (+ t (rate-wait headers t)))))

(defn- ok! [url {:keys [status headers] :as response}]
  (log/debug "GET" url "->" status)
  (note-wait! url headers)
  (when-not (<= 200 status 299)
    (throw (ex-info (str "GET " url " returned " status)
                    {:breto/problem :http-status :url url :status status
                     :headers (select-keys headers kept-headers)})))
  response)

(defn- refused!
  "Rethrows the briko.http refusal `e` of a `method` request for `url` as a
  breto problem: :network when the host was not reached in time, else
  :http-refused naming the rule the request or its answer broke."
  [method url e]
  (let [{:keys [type limit]} (ex-data e)
        reason (name type)]
    (log/debug method url "refused:" reason)
    (throw (ex-info (str method " " url " failed: " (ex-message e))
                    (cond-> {:breto/problem (if (unreachable type) :network :http-refused)
                             :url url :exception reason}
                      limit (assoc :limit limit))
                    e))))

(defn- with-defaults
  "`headers` with the client's name and the encoding it decodes, unless the
  caller names them already."
  [headers]
  (let [named (set (map (comp str/lower-case name) (keys headers)))]
    (cond-> (or headers {})
      (not (named "user-agent")) (assoc "User-Agent" "breto")
      (not (named "accept-encoding")) (assoc "Accept-Encoding" "gzip"))))

(defn- lookup
  "The answer to a `method` request for `url`, each header reduced to its
  first value, as breto's bookkeeping reads them."
  [method url headers opts]
  (let [response (try
                   (http/request method url (assoc opts :headers (with-defaults headers)))
                   (catch clojure.lang.ExceptionInfo e
                     (if (http/refusal? e)
                       (refused! (str/upper-case (name method)) url e)
                       (throw e))))]
    (update response :headers update-vals first)))

(defn- refuse-body! [url reason data]
  (throw (ex-info (str "GET " url " failed: " reason)
                  (merge {:breto/problem :http-refused :url url} data))))

(defn- decoded
  "The body of `response` with its content encoding undone, within
  `max-body-bytes`. Compression shrinks a JSON document about fifteen times,
  so the limit applies to the decoded bytes; otherwise a small gzip body
  could expand without bound."
  ^bytes [url {:keys [headers body]}]
  (let [encoding (some-> (get headers "content-encoding") str/trim str/lower-case)]
    (case encoding
      (nil "" "identity") body
      "gzip" (let [bs (try (with-open [in (GZIPInputStream. (ByteArrayInputStream. ^bytes body))]
                             (.readNBytes in (int (inc max-body-bytes))))
                           (catch java.io.IOException _
                             (refuse-body! url "the gzip body is corrupt" {:exception "content-encoding"})))]
               (when (< (long max-body-bytes) (alength ^bytes bs))
                 (refuse-body! url (str "the decoded body is larger than " max-body-bytes " bytes")
                               {:exception "too-large" :limit max-body-bytes}))
               bs)
      (refuse-body! url (str "the body is encoded as " encoding ", which breto did not ask for")
                    {:exception "content-encoding"}))))

(defn exists?
  "Whether `url` answers a HEAD request with success, after redirects."
  [url]
  (let [{:keys [status headers]} (lookup :head url {} head-options)]
    (log/debug "HEAD" url "->" status)
    (note-wait! url headers)
    (<= 200 status 299)))

(defn get-text
  "The body of a successful GET of `url` with `headers`, as UTF-8 text."
  [url headers]
  (String. (decoded url (ok! url (lookup :get url headers lookup-options))) StandardCharsets/UTF_8))

(defn- stream-request [url opts]
  (try
    (http-client/get url (assoc opts :throw false))
    (catch java.io.IOException e
      (log/debug "GET" url "failed:" (.getName (class e)) (ex-message e))
      (throw (ex-info (str "GET " url " failed: " (or (ex-message e) (.getName (class e))))
                      {:breto/problem :network :url url :exception (.getName (class e))}
                      e)))))

(defn- copy-body!
  "Copies `in` to `out`, returning the byte count. A read that fails is the
  connection failing, so it is reported as a network problem."
  [url ^java.io.InputStream in out on-bytes expected]
  (let [buffer (byte-array 65536)]
    (loop [done 0]
      (let [n (try (.read in buffer)
                   (catch java.io.IOException e
                     (throw (ex-info (str "GET " url " broke off after " done " bytes: "
                                          (or (ex-message e) (.getName (class e))))
                                     {:breto/problem :network :url url :exception (.getName (class e))
                                      :received done :expected expected}
                                     e))))]
        (if (pos? n)
          (do (.write out buffer 0 n)
              (when on-bytes (on-bytes (+ done n) expected))
              (recur (+ done n)))
          done)))))

(defn download!
  "Saves `url` to `dest`, calling `on-bytes` with the bytes written so far and
  the expected total, when the server states one. A body shorter than the
  stated length fails, so an unverified install never keeps a truncated file.
  The request asks for the file unencoded, since the client decodes a gzip
  or deflate body while Content-Length counts the encoded bytes; a server
  that encodes it anyway states no length the decoded body can be held to."
  ([url dest] (download! url dest nil))
  ([url dest on-bytes]
   (let [response (ok! url (stream-request url {:as :stream :headers {"accept-encoding" "identity"}}))
         encoding (some-> (get-in response [:headers "content-encoding"]) str)
         expected (when (or (nil? encoding) (= "identity" encoding))
                    (some-> (get-in response [:headers "content-length"]) str parse-long))
         received (with-open [in ^java.io.InputStream (:body response)
                              out (io/output-stream (io/file (str dest)))]
                    (copy-body! url in out on-bytes expected))]
     (when (and expected (not= expected received))
       (throw (ex-info (str "GET " url " ended after " received " of " expected " bytes")
                       {:breto/problem :network :url url :exception "truncated body"
                        :received received :expected expected}))))))

(def io
  "The network operations the installer needs, as a map so tests can swap them."
  {:get-text get-text
   :download! download!
   :exists? exists?
   :upstream-wait upstream-wait})
