;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.http
  "Requests to registries, forges and download hosts, all through briko.http,
  which bounds each one by deadlines and a body limit. Lookups read their
  body into memory; downloads stream it to a file."
  (:require [breto.log :as log]
            [briko.fs :as bfs]
            [briko.http :as http]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io ByteArrayInputStream InputStream)
           (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.util.zip GZIPInputStream InflaterInputStream)))

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
  (let [{:keys [status headers]} (lookup :head url {} lookup-options)]
    (log/debug "HEAD" url "->" status)
    (note-wait! url headers)
    (<= 200 status 299)))

(defn get-text
  "The body of a successful GET of `url` with `headers`, as UTF-8 text."
  [url headers]
  (String. (decoded url (ok! url (lookup :get url headers lookup-options))) StandardCharsets/UTF_8))

(def max-download-bytes
  "The largest release asset a download writes. The largest asset of a
  sample of 25 definitions of the package registry on 2026-11-28 was the
  IntelliJ IDEA archive, 1664140391 bytes, followed by ollama's at
  1649689331; the limit leaves five times that, so it only stops a server
  that sends without end."
  (* 8 1024 1024 1024))

(def ^:private download-options
  "The bounds of every download. Four hours carry the largest asset
  measured, 1.66 GB, at about 1 Mbit/s, the slowest link the lookups are
  sized for as well; a download had no deadline before, and a shorter one
  would cut off a slow but working link. The body goes to a staged file
  beside the target and replaces it only once complete. It is not forced
  to disk, because the file is verified and unpacked right after and a
  crash before that leaves nothing installed."
  {:connect-timeout-ms 10000
   :timeout-ms (* 4 60 60 1000)
   :max-bytes max-download-bytes
   :max-redirects 5})

(defn- decode-file!
  "Replaces `dest`, a body a server sent with `encoding` although breto asked
  for none, by its decoded bytes, within `max-download-bytes`, and returns
  their count. The client sent `Accept-Encoding: identity`, yet a server that
  compresses every answer still does so, and the checksum covers the
  decoded file."
  [url dest encoding]
  (let [decoder (case encoding
                  "gzip" #(GZIPInputStream. %)
                  "deflate" #(InflaterInputStream. %)
                  (refuse-body! url (str "the body is encoded as " encoding ", which breto did not ask for")
                                {:exception "content-encoding"}))
        written (volatile! 0)]
    (bfs/atomic-write!
     dest
     (fn [^java.nio.channels.FileChannel channel]
       ;; A read failure is rethrown as an ex-info, which briko.fs passes on
       ;; unchanged, where an IOException would become a failed write.
       (let [corrupt #(refuse-body! url (str "the " encoding " body is corrupt") {:exception "content-encoding"})
             in ^InputStream (try (decoder (io/input-stream (str dest)))
                                  (catch java.io.IOException _ (corrupt)))
             buffer (byte-array 65536)]
         (with-open [in in]
           (loop []
             (let [n (try (.read in buffer) (catch java.io.IOException _ (corrupt)))]
               (when (pos? n)
                 (when (< (long max-download-bytes) (+ (long @written) n))
                   (refuse-body! url (str "the decoded body is larger than " max-download-bytes " bytes")
                                 {:exception "too-large" :limit max-download-bytes}))
                 (let [bb (ByteBuffer/wrap buffer 0 n)]
                   (while (.hasRemaining bb) (.write channel bb)))
                 (vswap! written + n)
                 (recur)))))))
     {:fsync? false})
    @written))

(defn- download-refused!
  "Rethrows the briko.http refusal `e` of a download as a breto problem, as
  `refused!` does for lookups. A connection that broke off after some bytes
  arrived carries what was `received` and `expected`, and a file that could
  not be written is a :filesystem problem."
  [url dest e [received expected]]
  (let [{:keys [type reason]} (ex-data e)]
    (cond
      (= ::http/file-failed type)
      (throw (ex-info (str "GET " url " could not be written to " dest)
                      {:breto/problem :filesystem :url url :path (str dest)
                       :exception (if reason (name reason) "file-failed")}
                      e))

      (and (unreachable type) (pos? (long received)))
      (throw (ex-info (str "GET " url " broke off after " received " bytes: " (ex-message e))
                      (cond-> {:breto/problem :network :url url :exception (name type) :received received}
                        expected (assoc :expected expected))
                      e))

      :else (refused! "GET" url e))))

(defn download!
  "Saves `url` to `dest`, calling `on-bytes` with the bytes written so far and
  the expected total, when the server states one. `dest` is replaced only by
  a complete body of a 2xx answer: a body shorter than its stated length,
  over `max-download-bytes` or past the deadline leaves `dest` as it was, so
  an unverified install never keeps a truncated file. The request asks for
  the file unencoded; a server that encodes it anyway has it decoded, with
  the decoded size reported last."
  ([url dest] (download! url dest nil))
  ([url dest on-bytes]
   (let [progress (volatile! [0 nil])
         track (fn [done size]
                 (vreset! progress [done size])
                 (when on-bytes (on-bytes done size)))
         response (try
                    (http/request :get url (assoc download-options
                                                  :headers {"User-Agent" "breto" "Accept-Encoding" "identity"}
                                                  :as {:file (str dest) :fsync? false :on-progress track}))
                    (catch clojure.lang.ExceptionInfo e
                      (if (http/refusal? e)
                        (download-refused! url dest e @progress)
                        (throw e))))
         response (update response :headers update-vals first)
         _ (ok! url response)
         encoding (some-> (get-in response [:headers "content-encoding"]) str/trim str/lower-case)]
     (when-not (contains? #{nil "" "identity"} encoding)
       (let [decoded (decode-file! url dest encoding)]
         (when on-bytes (on-bytes decoded nil)))))))

(def io
  "The network operations the installer needs, as a map so tests can swap them."
  {:get-text get-text
   :download! download!
   :exists? exists?
   :upstream-wait upstream-wait})
