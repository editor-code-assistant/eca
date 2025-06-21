(ns eca.llm-providers.google
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [eca.logger :as logger]
   [hato.client :as http]))

(def ^:private logger-tag "[GOOGLE_GEMINI]")

(def ^:private url "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")

(defn ^:private raw-data->messages [data]
  (let [{:keys [choices]} (json/parse-string data true)]
    (map (fn [{:keys [delta finish_reason]}]
           (cond-> {}
             (:content delta) (assoc :message (:content delta))
             finish_reason (assoc :finish-reason finish_reason)))
         choices)))

(defn ^:private ->message [{:keys [role behavior context]} user-prompt]
  (format "%s\n%s\n%s\nThe user is asking: '%s'"
          role behavior context user-prompt))

(defn completion! [{:keys [model user-prompt context temperature api-key]
                    :or {temperature 1.0}}
                   {:keys [on-message-received on-error]}]
  (let [messages [{:role "user" :content (->message context user-prompt)}]
        body {:model model
              :messages messages
              :temperature temperature
              :stream true}
        api-key (or api-key
                    (System/getenv "GOOGLE_GEMINI_API_KEY"))]
    (http/post
     url
     {:headers {"Authorization" (str "Bearer " api-key)
                "Content-Type" "application/json"}
      :body (json/generate-string body)
      :throw-exceptions? false
      :async? true
      :as :stream}
     (fn [{:keys [status body]}]
       (try
         (if (not= 200 status)
           (do
             (logger/warn logger-tag "Unexpected response status" status)
             (on-error {:message (str "Google Gemini response status: " status)}))
           (with-open [rdr (io/reader body)]
             (doseq [line (line-seq rdr)]
               (when (str/starts-with? line "data: ")
                 (let [data (subs line 6)]
                   (when-not (= "[DONE]" data)
                     (doseq [message (raw-data->messages data)]
                       (on-message-received message))))))))
         (catch Exception e
           (on-error {:exception e}))))
     (fn [e]
       (on-error {:exception e})))))
