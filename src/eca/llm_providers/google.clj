(ns eca.llm-providers.google
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[GEMINI]")

(def ^:private gemini-url "https://generativelanguage.googleapis.com")
(def ^:private responses-path "/v1beta/models/$model:streamGenerateContent?alt=sse")

(defn ^:private url [path]
  (format "%s%s"
          (or (System/getenv "GEMINI_API_URL")
              gemini-url)
          path))

;; TODO: this is not 100% correct, just a sketch
(defn ->request+auth [{:keys [;; gemini API:
                              gemini-api-key
                              ;; vertex + api key
                              google-api-key

                              ;; vertex + ADC
                              google-project-id
                              google-project-location
                              ;; optional, we will try to look in GOOGLE_APPLICATION_CREDENTIALS env var
                              application-default-credentials]}]

  (cond
    gemini-api-key
    {:auth-type :gemini-api
     :url (str gemini-url responses-path)
     :auth-headers {"x-goog-api-key" gemini-api-key}}

    google-api-key
    {:auth-type :vertex-api-key
     :url "some.google.url"
     :headers {"x-goog-api-key" google-api-key}}

    (and google-project-id google-project-location)
    {:auth-type :vertex-adc
     :url (str "https://"
               google-project-location
               "-aiplatform.googleapis.com/v1/projects/"
               google-project-id
               "/locations/"
               google-project-location
               "/publishers/google/models/$model:streamGenerateContent?alt=sse")
     :headers {"Authorization" (str "Bearer " (or application-default-credentials
                                                  (System/getenv "GOOGLE_APPLICATION_CREDENTIALS")))}}))

(defn ^:private base-completion-request! [{:keys [rid body api-key on-error on-response]}]
  (let [api-key (or api-key
                    (System/getenv "GEMINI_API_URL"))
        url (str/replace (url responses-path) "$model" (:model body))]
    (logger/debug logger-tag (format "Sending input: '%s' instructions: '%s' tools: '%s' url: '%s'"
                                     (:input body)
                                     (:instructions body)
                                     (:tools body)
                                     url))
    ;; TODO: use ->request+auth to get the right URL and headers
    (http/post
     url
     {:headers {"x-goog-api-key" (str api-key)
                "Content-Type" "application/json"}
      :body (json/generate-string body)
      :throw-exceptions? true
      :async? true
      :as :stream}
     (fn [{:keys [status body]}]
       (try
         (if (not= 200 status)
           (let [body-str (slurp body)]
             (logger/warn logger-tag "Unexpected response status: %s body: %s" status body-str)
             (on-error {:message (format "Gemini response status: %s body: %s" status body-str)}))
           (with-open [rdr (io/reader body)]
             (doseq [[event data] (llm-util/event-data-seq rdr)]
               (llm-util/log-response logger-tag rid event data)
               (on-response event data))))
         (catch Exception e
           (on-error {:exception e}))))
     (fn [e]
       (on-error {:exception e})))))

(defn completion! [{:keys [model user-prompt context temperature api-key past-messages tools web-search]
                    :or {temperature 1.0}}
                   {:keys [on-message-received on-error on-tool-called on-reason]}]
  (let [input (conj past-messages {:role "user" :parts [{:text user-prompt}]})
        tools (cond-> tools
                web-search (conj {:google_search {}}))
        body {:model model
              :contents input
              :system_instruction {:parts [{:text context}]}
              :tools tools
              :stream true}
        on-response-fn (fn handle-response [_event data]
                         ())]
    (base-completion-request!
     {:body body
      :api-key api-key
      :on-error on-error
      :on-response on-response-fn})))
