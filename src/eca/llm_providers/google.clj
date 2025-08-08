(ns eca.llm-providers.google
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[GEMINI]")

(def ^:private gemini-url "https://generativelanguage.googleapis.com")
(def ^:private gemini-responses-path "/v1beta/models/$model:streamGenerateContent?alt=sse")

(defn ^:private gemini-vertex-url [{:keys [project location]}]
  (format "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/$model:streamGenerateContent?alt=sse"
          location project location))

;; TODO: this will need to run a refresh loop because this token will expire!
(defn get-adc-token []
  (if-let [adc-path (let [adc-path (or (System/getenv "GOOGLE_APPLICATION_CREDENTIALS")
                                       (fs/expand-home "~/.config/gcloud/application_default_credentials.json"))]
                      (when (fs/exists? adc-path)
                        (str adc-path)))]
    (let [{:keys [client_id client_secret refresh_token]} (json/parse-string (slurp adc-path) true)]
      (-> (http/post "https://oauth2.googleapis.com/token"
                     {:form-params {:client_id client_id
                                    :client_secret client_secret
                                    :refresh_token refresh_token
                                    :grant_type "refresh_token"}
                      :as :json
                      :headers {"Content-Type" "application/x-www-form-urlencoded"}})
          :body
          ;; TODO: get expires_in
          :access_token))
    (logger/error logger-tag
                  (str "No GOOGLE_APPLICATION_CREDENTIALS env var or ~/.config/gcloud/application_default_credentials.json file found. "
                       "Please run 'gcloud auth application-default login' to set up application default credentials."))))

;; TODO: this is not 100% correct, just a sketch
(defn ->request+auth [{:keys [;; gemini API:
                              gemini-api-key
                              ;; vertex
                              google-project-id
                              google-project-location
                              ;; w/ api key
                              google-api-key
                              ;; w/ ADC - we will get the token ourselves
                              ]}]

  (cond
    gemini-api-key
    {:auth-type :gemini-api
     :url (str gemini-url gemini-responses-path)
     :headers {"x-goog-api-key" gemini-api-key}}

    (and google-project-id google-project-location)
    {:auth-type :vertex-adc
     :url (gemini-vertex-url {:project google-project-id
                              :location google-project-location})

     ;; TODO: blow up when either are nil!
     :headers {"Authorization" (str "Bearer " (or google-api-key
                                                  (get-adc-token)))}}))

(defn ^:private base-completion-request! [{:keys [rid
                                                  body

                                                  gemini-api-key

                                                  google-api-key
                                                  google-project-id
                                                  google-project-location

                                                  on-error
                                                  on-response]}]
  (let [{:keys [url headers]} (->request+auth
                               {:gemini-api-key gemini-api-key
                                :google-api-key google-api-key
                                :google-project-id google-project-id
                                :google-project-location google-project-location})

        url (str/replace url "$model" (:model body))]
    (logger/debug logger-tag (format "Sending input: '%s' instructions: '%s' tools: '%s' url: '%s'"
                                     (:input body)
                                     (:instructions body)
                                     (:tools body)
                                     url))
    ;; TODO: use ->request+auth to get the right URL and headers
    (http/post
     url
     {:headers (merge {"Content-Type" "application/json"} headers)
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

(defn completion! [{:keys [model user-prompt context temperature

                           google-api-key
                           gemini-api-key
                           google-project-id
                           google-project-location
                           application-default-credentials

                           past-messages tools web-search]
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
      :rid (llm-util/gen-rid)

      :gemini-api-key gemini-api-key
      :google-api-key google-api-key
      :application-default-credentials application-default-credentials
      :google-project-id google-project-id
      :google-project-location google-project-location

      :on-error on-error
      :on-response on-response-fn})))
