;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.http
  (:require [babashka.http-client :as http]
            [breto.log :as log]
            [clojure.java.io :as io]))

(def ^:private kept-headers
  ["x-ratelimit-remaining" "x-ratelimit-reset" "retry-after"])

(defn- ok! [url {:keys [status headers] :as response}]
  (log/debug "GET" url "->" status)
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
   :download! download!})
