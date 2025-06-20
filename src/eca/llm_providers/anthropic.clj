(ns eca.llm-providers.anthropic
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [eca.logger :as logger]
   [hato.client :as http]))

(def ^:private logger-tag "[ANTHROPIC]")

(def ^:private url "https://api.anthropic.com/v1/messages")

(defn ^:private raw-data->messages [data]
  (let [{:keys [type delta]} (json/parse-string data true)]
    (case type
      "content_block_delta" (case (:type delta)
                              "text_delta" {:message (:text delta)}
                              (logger/warn "Unkown response delta type" (:type delta)))
      "message_stop" {:finish-reason type}
      nil)))

(defn completion! [{:keys [model user-prompt temperature context max-tokens api-key past-messages]
                    :or {max-tokens 1024
                         temperature 1.0}}
                   {:keys [on-message-received on-error]}]
  (let [body {:model model
              :messages (conj past-messages {:role "user" :content user-prompt})
              :max_tokens max-tokens
              :temperature temperature
              ;; TODO support :thinking
              :stream true
              :system context}
        api-key (or api-key
                    (System/getenv "ANTHROPIC_API_KEY"))]
    (http/post
     url
     {:headers {"x-api-key" api-key
                "anthropic-version" "2023-06-01"
                "Content-Type" "application/json"}
      :body (json/generate-string body)
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
                 (on-error {:message (str "Anthropic response status: " status)}))
               (when (str/starts-with? line "data: ")
                 (let [data (subs line 6)]
                   (when-let [message (raw-data->messages data)]
                     (on-message-received message)))))))
         (catch Exception e
           (on-error {:exception e}))))
     (fn [e]
       (on-error {:exception e})))))
