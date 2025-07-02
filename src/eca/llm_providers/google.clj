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


(defn ^:private base-completion-request! [{:keys [rid body api-key on-error on-response]}]
  (let [api-key (or api-key
                    (System/getenv "GEMINI_API_URL"))
        url (str/replace (url responses-path) "$model" (:model body))]
    (logger/debug logger-tag (format "Sending input: '%s' instructions: '%s' tools: '%s' url: '%s'"
                                     (:input body)
                                     (:instructions body)
                                     (:tools body)
                                     url))
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
