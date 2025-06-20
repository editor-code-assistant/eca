(ns eca.llm-providers.ollama
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [eca.logger :as logger]
   [hato.client :as http]))

(def ^:private logger-tag "[OLLAMA]")

(def ^:private chat-url "%s/api/chat")
(def ^:private list-running-models-url "%s/api/ps")

(defn ^:private base-url [host port]
  (or (System/getenv "OLLAMA_API_BASE")
      (str host ":" port)))

(defn list-running-models [{:keys [host port]}]
  (try
    (let [{:keys [status body]} (http/get
                                 (format list-running-models-url (base-url host port))
                                 {:throw-exceptions? false
                                  :as :json})]
      (if (= 200 status)
        (:models body)
        (do
          (logger/warn logger-tag "Unknown status code:" status)
          [])))
    (catch Exception e
      (logger/warn logger-tag "Error listing running models:" (ex-message e))
      [])))

(defn ^:private raw-data->messages [data]
  (let [{:keys [message done_reason]} (json/parse-string data true)]
    (cond-> {}
      message (assoc :message (:content message))
      done_reason (assoc :finish-reason done_reason))))

(defn ^:private ->message-with-context [context user-prompt]
  (format "%s\nThe user is asking: '%s'" context user-prompt))

(defn completion! [{:keys [model user-prompt context host port past-messages]}
                   {:keys [on-message-received on-error]}]
  (let [body {:model model
              :messages (if (empty? past-messages)
                          [{:role "user" :content (->message-with-context context user-prompt)}]
                          (conj past-messages {:role "user" :content user-prompt}))
              :stream true}]
    (http/post
     (format chat-url (base-url host port))
     {:body (json/generate-string body)
      :throw-exceptions? false
      :async? true
      :as :stream}
     (fn [{:keys [status body]}]
       (try
         (with-open [rdr (io/reader body)]
           (doseq [line (line-seq rdr)]
             (if (not= 200 status)
               (let [msg line]
                 (logger/warn logger-tag "Unexpected response status" status "." msg)
                 (on-error {:message (str "Ollama response status: " status)}))
               (when-let [message (raw-data->messages line)]
                 (on-message-received message)))))
         (catch Exception e
           (on-error {:exception e}))))
     (fn [e]
       (on-error {:exception e})))))
