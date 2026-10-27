;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.http
  (:require [babashka.http-client :as http]
            [clojure.java.io :as io]))

(defn- ok! [url {:keys [status] :as response}]
  (when-not (<= 200 status 299)
    (throw (ex-info (str "GET " url " returned " status) {:url url :status status})))
  response)

(defn get-text [url headers]
  (:body (ok! url (http/get url {:headers headers :throw false}))))

(defn download! [url dest]
  (let [response (ok! url (http/get url {:as :stream :throw false}))]
    (with-open [in (:body response)]
      (io/copy in (io/file (str dest))))))

(def io
  "The network operations the installer needs, as a map so tests can swap them."
  {:get-text get-text
   :download! download!})
