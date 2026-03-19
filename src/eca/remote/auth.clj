(ns eca.remote.auth
  "Bearer token authentication middleware for the remote server."
  (:require
   [cheshire.core :as json])
  (:import
   [java.security MessageDigest SecureRandom]))

(set! *warn-on-reflection* true)

(defn generate-token
  "Generates a cryptographically random 32-byte hex token (64 characters)."
  []
  (let [random (SecureRandom.)
        bytes (byte-array 32)]
    (.nextBytes random bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(def ^:private unauthorized-response
  {:status 401
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body (json/generate-string
          {:error {:code "unauthorized"
                   :message "Missing or invalid Authorization Bearer token"}})})

(defn- extract-bearer-token [request]
  (when-let [auth-header (get-in request [:headers "authorization"])]
    (when (.startsWith ^String auth-header "Bearer ")
      (subs auth-header 7))))

(defn- constant-time-equals?
  "Constant-time comparison to prevent timing side-channel attacks."
  [^String a ^String b]
  (and a b
       (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8"))))

(defn wrap-bearer-auth
  "Ring middleware that validates Bearer token auth.
   Exempt paths (like /api/v1/health and GET /) skip auth."
  [handler token exempt-paths]
  (let [exempt-set (set exempt-paths)]
    (fn [request]
      (if (contains? exempt-set (:uri request))
        (handler request)
        (let [request-token (extract-bearer-token request)]
          (if (constant-time-equals? token request-token)
            (handler request)
            unauthorized-response))))))
