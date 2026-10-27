;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.http
  (:require [babashka.http-client :as http]
            [breto.log :as log]
            [clojure.java.io :as io]))

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

(defn- ok! [url {:keys [status headers] :as response}]
  (log/debug "GET" url "->" status)
  (let [t (now)]
    (swap! not-before assoc (.getHost (java.net.URI. url)) (+ t (rate-wait headers t))))
  (when-not (<= 200 status 299)
    (throw (ex-info (str "GET " url " returned " status)
                    {:breto/problem :http-status :url url :status status
                     :headers (select-keys headers kept-headers)})))
  response)

(defn- request [url opts]
  (try
    (http/get url (assoc opts :throw false))
    (catch java.io.IOException e
      (log/debug "GET" url "failed:" (.getName (class e)) (ex-message e))
      (throw (ex-info (str "GET " url " failed: " (or (ex-message e) (.getName (class e))))
                      {:breto/problem :network :url url :exception (.getName (class e))}
                      e)))))

(defn get-text [url headers]
  (:body (ok! url (request url {:headers headers}))))

(defn download! [url dest]
  (let [response (ok! url (request url {:as :stream}))]
    (with-open [in (:body response)]
      (io/copy in (io/file (str dest))))))

(def io
  "The network operations the installer needs, as a map so tests can swap them."
  {:get-text get-text
   :download! download!
   :upstream-wait upstream-wait})
