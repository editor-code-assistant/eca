(ns eca.api.http
  "HTTP requests, for modules. They go through ECA's HTTP client, so its
   proxy and TLS settings apply. A response is `{:status :headers :body}`;
   error statuses don't throw."
  (:refer-clojure :exclude [get])
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.client-http :as client]
   [hato.client :as hato]))

(set! *warn-on-reflection* true)

(defn request
  "Sends an HTTP request and waits for the response. `opts`:
   - `:method`: `:get` (the default), `:post`, `:put`, `:patch`, `:delete` or
     `:head`; `:url`.
   - `:headers`: string → string; `:query-params`: a map.
   - `:body`: text to send, or `:json`: data sent as a JSON body.
   - `:oauth-token`, or `:basic-auth` `[user password]`.
   - `:timeout-ms`: 30000 by default.
   - `:as`: `:string` (the default), or `:json` to parse the body, with keyword
     keys."
  [{:keys [method url headers query-params body json oauth-token basic-auth timeout-ms as]
    :or {method :get timeout-ms 30000 as :string}}]
  (let [response (hato/request
                  (cond-> {:method method
                           :url url
                           :as :string
                           :throw-exceptions? false
                           :timeout timeout-ms
                           :http-client (client/merge-with-global-http-client {})}
                    headers (assoc :headers headers)
                    query-params (assoc :query-params query-params)
                    body (assoc :body body)
                    (some? json) (-> (assoc :body (json/generate-string json))
                                     (assoc-in [:headers "Content-Type"] "application/json"))
                    oauth-token (assoc :oauth-token oauth-token)
                    basic-auth (assoc :basic-auth {:user (first basic-auth) :pass (second basic-auth)})))
        response-body (:body response)]
    {:status (:status response)
     :headers (:headers response)
     :body (if (and (= :json as) (string? response-body) (not (string/blank? response-body)))
             (json/parse-string response-body true)
             response-body)}))

(defn get
  "A GET request to `url`; `opts` as `request`'s."
  ([url] (get url nil))
  ([url opts] (request (assoc opts :method :get :url url))))

(defn post
  "A POST request to `url`; `opts` as `request`'s."
  [url opts]
  (request (assoc opts :method :post :url url)))

(defn put
  "A PUT request to `url`; `opts` as `request`'s."
  [url opts]
  (request (assoc opts :method :put :url url)))

(defn delete
  "A DELETE request to `url`; `opts` as `request`'s."
  ([url] (delete url nil))
  ([url opts] (request (assoc opts :method :delete :url url))))
