(ns eca.llm-providers.google
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.shared :as shared]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[GEMINI]")

(def ^:private gemini-url "https://generativelanguage.googleapis.com")
(def ^:private gemini-responses-path "/v1beta/models/$model:streamGenerateContent?alt=sse")

(defn ^:private gemini-vertex-url [{:keys [project location]}]
  (format "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/$model:streamGenerateContent?alt=sse"
          location project location))

;; TODO: this will need to run a refresh loop because this token will expire!
(defn ^:private fetch-adc [{:keys [project]}]
  (if-let [adc-path (let [adc-path (or (System/getenv "GOOGLE_APPLICATION_CREDENTIALS")
                                       (fs/expand-home "~/.config/gcloud/application_default_credentials.json"))]
                      (when (fs/exists? adc-path)
                        (str adc-path)))]
    (let [{:keys [client_id client_secret refresh_token]} (json/parse-string (slurp adc-path) true)
          {:keys [status body] :as _Response} (http/post "https://oauth2.googleapis.com/token"
                                                         {:form-params {:client_id client_id
                                                                        :client_secret client_secret
                                                                        :refresh_token refresh_token
                                                                        :grant_type "refresh_token"}
                                                          :throw-exceptions? false
                                                          :as :json
                                                          :headers {"Content-Type" "application/x-www-form-urlencoded"}})]

      (if (= 200 status)
        (select-keys body [:access_token :expires_in])
        ;; NOTE: technically, we could run `gcloud --project=... auth application-default login` here and retry the request
        (logger/error logger-tag
                      (str "Failed to fetch ADC token. Status: " status
                           ", body: " body
                           "\nRun 'gcloud --project=" project " auth application-default login' to update application default credentials."))))
    (logger/error logger-tag
                  (str "No GOOGLE_APPLICATION_CREDENTIALS env var or ~/.config/gcloud/application_default_credentials.json file found. "
                       "Please run 'gcloud auth application-default login' to set up application default credentials."))))

(defonce adc-info (atom nil))

(defn ^:private get-adc-token [{:keys [project location]}]
  ;; if we have a token - just return it
  (if-let [token (-> @adc-info :access_token)]
    token
    ;; looks like we're fetching for the first time
    (let [{:keys [expires_in access_token] :as token-info} (try
                                                             (fetch-adc {:project project
                                                                         :location location})
                                                             (catch Exception e
                                                               (logger/error logger-tag "Error fetching ADC token: %s" (ex-message e))
                                                               (throw e)))]
      (reset! adc-info token-info)
      (logger/info logger-tag (format "Scheduling token refresh in %d seconds" expires_in))
      ;; spin up a future to refresh the token
      (when token-info
        (future
          ;; wait for the token to expire, then fetch a new one
          ;; we subtract 60 seconds to ensure we don't hit the expiration time
          (Thread/sleep ^long (* 1000 (- expires_in 60)))
          (logger/info logger-tag "Refreshing ADC token")
          (let [new-token-info (fetch-adc {:project project
                                           :location location})]
            (reset! adc-info new-token-info)
            (logger/info logger-tag "Fetched new ADC token"))))
      access_token)))

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
                                                  (get-adc-token {:project-id google-project-id
                                                                  :location google-project-location})))}}))

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
        _ (when-not url
            (logger/error logger-tag
                          (str "No URL for the request. url:" url))

            (on-error {:message (format (str "Couldn't determine the request URL. "
                                             "Please check your configuration for gemini-api-key or google-project-id and google-project-location.\n "
                                             "Gemini API key: %s, "
                                             "Google project ID: %s, Google project location: %s"
                                             "Google API key: %s")
                                        (shared/redact-api-key gemini-api-key)
                                        google-project-id
                                        google-project-location
                                        (shared/redact-api-key google-api-key))}))
        url (str/replace url "$model" (:model body))]
    (logger/debug logger-tag (format "Sending input: '%s' instructions: '%s' tools: '%s' url: '%s'"
                                     (:input body)
                                     (:instructions body)
                                     (:tools body)
                                     url))
    (try
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
             (logger/error logger-tag "Error in Gemini request: %s" (-> e ex-data :body slurp))
             (on-error {:exception e}))))
       (fn [e]
         (logger/error logger-tag "Error in Gemini request: %s" (-> e ex-data :body slurp))
         (on-error {:exception e})))
      (catch Exception e
        (on-error {:exception e})))))

(defn ^:private normalize-messages [messages]
  (mapv (fn [{:keys [role content]}]
          (case role
            "tool_call"
            {:role "model"
             :parts [{:functionCall {:name (:name content)
                                     :args (:arguments content)}}]}

            "tool_call_output" (do
                                 (logger/debug logger-tag (format "Tool call output: %s" content))
                                 {:role "tool"
                                  :parts [{:functionResponse
                                           {:name (:name content)
                                            :response {:name (:name content)
                                                       :content (:output content)}}}]})

            {:role (if (= role "assistant") "model" role)
             :parts (if (string? content)
                      [{:text content}]
                      (mapv (fn [c] (if (= "text" (name (:type c)))
                                      (dissoc c :type)
                                      c))
                            content))}))
        messages))

(defn ^:private ->tools [tools]
  (when (seq tools)
    [{:function_declarations (mapv #(select-keys % [:description :name :parameters]) tools)}]))

(defn completion! [{:keys [model user-messages instructions temperature max-output-tokens tools
                           past-messages

                           google-api-key
                           gemini-api-key
                           google-project-id
                           google-project-location]

                    :or {temperature 1.0}}
                   {:keys [on-message-received on-error on-tools-called]}]
  (let [messages (concat (normalize-messages past-messages)
                         (normalize-messages user-messages))
        body {:model model
              :tools (->tools tools)
              :tool_config {:function_calling_config {:mode "AUTO"}}
              :contents messages
              :system_instruction {:parts [{:text instructions}]}
              :generationConfig {:temperature temperature
                                 :maxOutputTokens max-output-tokens}}
        on-response-fn (fn handle-response [_event data]
                         (let [parts (get-in data [:candidates 0 :content :parts])
                               text-parts (filter :text parts)
                               function-call-parts (filter :functionCall parts)]
                           (doseq [part text-parts]
                             (on-message-received {:type :text, :text (:text part)}))
                           (when (seq function-call-parts)
                             (let [tool-calls (mapv
                                               (fn [part]
                                                 (let [function-call (:functionCall part)]
                                                   {:id (str (random-uuid))
                                                    :name (:name function-call)
                                                    :arguments (:args function-call)}))
                                               function-call-parts)
                                   {:keys [new-messages]} (on-tools-called tool-calls)
                                   final-messages (conj (vec new-messages) {:role "user" :content "Please continue."})]
                               (base-completion-request!
                                {:body (assoc body :contents (normalize-messages final-messages))
                                 :rid (llm-util/gen-rid)
                                 :gemini-api-key gemini-api-key
                                 :google-api-key google-api-key
                                 :google-project-id google-project-id
                                 :google-project-location google-project-location
                                 :on-error on-error
                                 :on-response handle-response})))
                           (when-let [finish-reason (get-in data [:candidates 0 :finishReason])]
                             (on-message-received {:type :finish, :finish-reason finish-reason}))))]
    (base-completion-request!
     {:body body
      :rid (llm-util/gen-rid)

      :gemini-api-key gemini-api-key
      :google-api-key google-api-key
      :google-project-id google-project-id
      :google-project-location google-project-location

      :on-error on-error
      :on-response on-response-fn})))
