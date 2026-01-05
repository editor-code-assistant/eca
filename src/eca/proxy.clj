(ns eca.proxy
  (:require
   [clojure.string :as string]
   [eca.config :as config])
  (:import
   [java.net URI URLDecoder]))

(set! *warn-on-reflection* true)

(defn parse-proxy-url
  "Parses a proxy URL-STR of the form `scheme://[user:pass@]host:port` into a map.

  Returns a map with the following keys:

  :host     - the host part of the URL

  :port     - the port part. Defaults to 80 for HTTP, 443 for HTTPS,
              and throws `IllegalArgumentException` if the scheme is neither

  :username - the user part of the URL, if present; URL-decoded

  :password - the password part of the URL, if present; URL-decoded

  Both username and password are automatically URL-decoded if they contain percent encoded characters."
  [url-str]
  (when (and url-str (not (clojure.string/blank? url-str)))
    (let [^URI uri       (URI. url-str)
          host           (.getHost uri)
          port           (if (neg? (.getPort uri))
                           (case (.getScheme uri)
                             "http" 80
                             "https" 443
                             (throw (IllegalArgumentException. (str "Unsupported scheme: " (.getScheme uri)))))
                           (.getPort uri))
          userinfo       (.getUserInfo uri)
          [user pass]    (when userinfo
                           (clojure.string/split userinfo #":" 2))
          decode         (fn [^String s] (when s (URLDecoder/decode s "UTF-8")))]
      {:host host
       :port port
       :username (decode user)
       :password (decode pass)})))

(defn proxy-urls-system-env-get
  "Returns a map of the HTTP and HTTPS proxy environment variables,
  preferring lowercase variable names over uppercase.

  The map includes:

  :http  - string value of http_proxy or HTTP_PROXY.
  :https - string value of https_proxy or HTTPS_PROXY."
  []
  {:http  (or (config/get-env "http_proxy")
              (config/get-env "HTTP_PROXY"))
   :https  (or (config/get-env "https_proxy")
               (config/get-env "HTTPS_PROXY"))})

(defn proxy-urls-parse
  "Parses the HTTP and HTTPS proxy URL strings from URLS-MAP and returns a map of parsed values.

  The input map may contain the keys:

  :http  - the HTTP proxy URL string, or nil
  :https - the HTTPS proxy URL string, or nil

  Returns a map with the same keys:

  :http  - a map with keys :host, :port, :username, :password for the HTTP proxy, or nil if not set
  :https - a map with keys :host, :port, :username, :password for the HTTPS proxy, or nil if not set

  Ports default to 80 for HTTP and 443 for HTTPS if not specified. Usernames and passwords are URL-decoded if present."
  [urls-map]
  (update-vals (select-keys urls-map [:http :https]) parse-proxy-url))

(defn env-proxy-urls-parse
  "Fetches the HTTP and HTTPS proxy URLs from environment variables and returns a map of parsed values.

  The function looks for these environment variables, preferring lowercase over uppercase:

  :http  - from `http_proxy` or `HTTP_PROXY`
  :https - from `https_proxy` or `HTTPS_PROXY`

  The returned map contains:

  :http  - a map with keys :host, :port, :username, :password for the HTTP proxy, or nil if not set
  :https - a map with keys :host, :port, :username, :password for the HTTPS proxy, or nil if not set

  Port defaults to 80 for HTTP and 443 for HTTPS if not specified. Usernames and passwords are URL-decoded if present."
  []
  (proxy-urls-parse (proxy-urls-system-env-get)))

