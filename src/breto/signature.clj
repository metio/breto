;; SPDX-FileCopyrightText: The breto Authors
;; SPDX-License-Identifier: 0BSD

(ns breto.signature
  "Sigstore signatures, checked with cosign verify-blob against the identity
  and OIDC issuer the definition expects. A checksum only shows the download
  is what the release lists; a signature shows who built the release."
  (:require [clojure.string :as str]))

(defn command
  "The cosign command verifying `file` against `bundle` for `signature`."
  [cosign {:keys [identity identity-regexp issuer]} file bundle]
  (concat [cosign "verify-blob" "--bundle" (str bundle)]
          (if identity
            ["--certificate-identity" identity]
            ["--certificate-identity-regexp" identity-regexp])
          ["--certificate-oidc-issuer" issuer (str file)]))

(defn verify!
  "Throws unless cosign accepts `bundle` as the signature of `file`. `run`
  runs a command and returns its exit code and output."
  [run cosign signature file bundle context]
  (let [cmd (command cosign signature file bundle)
        {:keys [exit err]} (try (run cmd)
                                (catch Exception e
                                  (throw (ex-info (str cosign " could not run: " (ex-message e))
                                                  (assoc context :breto/problem :signature-tool :cosign cosign
                                                         :error (ex-message e))))))]
    (when-not (zero? exit)
      (throw (ex-info (str "the signature of " (:signed context) " does not verify")
                      (assoc context :breto/problem :signature-invalid :exit exit
                             :stderr (str/trim (or err "")) :identity (or (:identity signature) (:identity-regexp signature))
                             :issuer (:issuer signature)))))))
