;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.edn-text
  "EDN that breto did not write itself: the configuration, project files and
  lock files of a checkout, and the definitions and groups of a registry. It
  is read through briko.edn, which bounds its size, nesting and number
  literals and refuses trailing forms, so a hostile file is refused instead
  of exhausting the stack or the heap.

  The bounds are briko.edn's defaults, 1 MiB and 64 levels: the largest file
  of these kinds in metio/packages and metio/toolchains was a 25 KiB lock
  file on 2026-11-28, and none nests deeper than about ten levels. #inst and
  #uuid read as clojure.edn reads them; every other tag is refused, as
  clojure.edn refuses a tag it has no reader for. Text without a form, an
  empty file or one holding only comments, reads as nil, as it did with
  clojure.edn, so an empty configuration file still means the defaults.

  A refusal is an ex-info, a RuntimeException, whose data carries briko.edn's
  :type."
  (:require [briko.edn :as edn]))

(def options
  "The briko.edn options of every read."
  {:readers default-data-readers})

(defn- form-or-nil [read]
  (try (read)
       (catch clojure.lang.ExceptionInfo e
         (if (= :briko.edn/no-form (:type (ex-data e)))
           nil
           (throw e)))))

(defn read-text
  "The one form in `text`, or nil when it holds none."
  [text]
  (form-or-nil #(edn/read-string text options)))

(defn read-file
  "The one form in the regular file at `path`, or nil when it holds none. At
  most the bound is read, so a file of any size costs no more. The message of
  a refusal leaves the path out, since every caller names the file itself;
  the data still carries it as :file."
  [path]
  (form-or-nil #(try (edn/read-file (str path) options)
                     (catch clojure.lang.ExceptionInfo e
                       (let [cause (ex-cause e)]
                         (throw (if (and (edn/refusal? e) (instance? clojure.lang.ExceptionInfo cause))
                                  (ex-info (ex-message cause) (ex-data e) cause)
                                  e)))))))

(defn refusal?
  "Whether `e` is briko.edn refusing the text."
  [e]
  (edn/refusal? e))
