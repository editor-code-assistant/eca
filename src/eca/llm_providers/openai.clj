(ns eca.llm-providers.openai
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.logger :as logger]
   [hato.client :as http])
  (:import
   [java.io BufferedReader]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OPENAI]")

(def ^:private url "https://api.openai.com/v1/responses")

(defn ^:private event-data-seq [^BufferedReader rdr]
  (when-let [event (.readLine rdr)]
    (when (string/starts-with? event "event:")
      (when-let [data (.readLine rdr)]
        (.readLine rdr) ;; blank line
        (when (string/starts-with? data "data:")
          (cons [(subs event 7)
                 (json/parse-string (subs data 6) true)]
                (lazy-seq (event-data-seq rdr))))))))

(defn completion! [{:keys [model user-prompt context temperature api-key past-messages]
                    :or {temperature 1.0}}
                   {:keys [on-message-received on-error]}]
  (let [body {:model model
              :input (conj past-messages {:role "user" :content user-prompt})
              :user (str (System/getProperty "user.name") "@ECA")
              :instructions context
              :temperature temperature
              :stream true}
        api-key (or api-key
                    (System/getenv "OPENAI_API_KEY"))]
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
             (on-error {:message (str "OpenAI response status: " status)}))
           (with-open [rdr (io/reader body)]
             (doseq [[event data] (event-data-seq rdr)]
               (case event
                 "response.output_text.delta" (on-message-received {:message (:delta data)})
                 "response.completed" (on-message-received {:finish-reason (-> data :response :status)})
                 nil))))
         (catch Exception e
           (on-error {:exception e}))))
     (fn [e]
       (on-error {:exception e})))))
