(ns eca.features.tools.ask-user
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS-USER]")

(defn ^:private normalize-option
  "Coerce a single raw option into a {:label .. :description ..} map.
  Accepts a plain string (used as the label) or a map with label/description
  under string or keyword keys. Returns nil when there is no usable label."
  [opt]
  (cond
    (string? opt)
    (when-not (string/blank? opt)
      {:label opt})

    (map? opt)
    (let [label (or (get opt "label") (get opt :label))
          description (or (get opt "description") (get opt :description))]
      (when (and (string? label) (not (string/blank? label)))
        (cond-> {:label label}
          (and (string? description) (not (string/blank? description)))
          (assoc :description description))))

    :else nil))

(defn ^:private normalize-options
  "Coerce the raw `options` tool argument into a vector of valid option maps.
  Tolerates an array of strings or objects, and a JSON-encoded string an LLM
  may send by mistake. Returns nil when nothing usable remains so the question
  is still asked, just without broken choices for the client to render."
  [options]
  (let [coll (cond
               (sequential? options) options
               (string? options) (try
                                    (let [parsed (json/parse-string options)]
                                      (when (sequential? parsed) parsed))
                                    (catch Exception _ nil))
               :else nil)
        normalized (into [] (keep normalize-option) coll)]
    (when (seq normalized)
      normalized)))

(defn ^:private ask-user
  [arguments {:keys [messenger db* chat-id tool-call-id]}]
  (let [question (get arguments "question")
        options (normalize-options (get arguments "options"))
        allow-freeform (get arguments "allowFreeform" true)]
    (if (or (nil? question) (string/blank? question))
      (tools.util/single-text-content "INVALID_ARGS: `question` is required and must not be blank." :error)
      (let [request-id (str (random-uuid))
            params (cond-> {:chatId chat-id
                            :question question
                            :allowFreeform allow-freeform
                            :request-id request-id}
                     (seq options) (assoc :options options)
                     tool-call-id (assoc :toolCallId tool-call-id))]
        (when (and db* tool-call-id)
          (swap! db* assoc-in [:chats chat-id :tool-calls tool-call-id :ask-question-request-id] request-id))
        (try
          (messenger/chat-content-received messenger
                                           {:chat-id chat-id
                                            :role :system
                                            :content {:type :progress :state :running :text "Waiting answer"}})
          (let [response @(messenger/ask-question messenger params)]
            (if (:cancelled response)
              (tools.util/single-text-content "User cancelled the question." :error)
              (tools.util/single-text-content (str "User answered: " (:answer response)))))
          (catch Exception e
            (logger/error logger-tag "Error asking user question: %s" (ex-message e))
            (tools.util/single-text-content "Error asking user question." :error)))))))

(def definitions
  {"ask_user"
   {:description (tools.util/read-tool-description "ask_user")
    :parameters {:type "object"
                 :properties {"question" {:type "string"
                                          :description "The question to ask the user."}
                              "options" {:type "array"
                                         :description "Optional predefined options for the user to choose from."
                                         :items {:type "object"
                                                 :properties {"label" {:type "string"
                                                                       :description "Short label for the option."}
                                                              "description" {:type "string"
                                                                             :description "Brief explanation of what this option means."}}
                                                 :required ["label"]}}
                              "allowFreeform" {:type "boolean"
                                               :description "Whether the user can type a custom answer instead of selecting an option. Defaults to true."}}
                 :required ["question"]}
    :handler #'ask-user
    :enabled-fn (fn [{:keys [db]}] (-> db :client-capabilities :code-assistant :chat-capabilities :ask-question))
    :summary-fn (fn [{:keys [args]}]
                  (if-let [q (get args "question")]
                    (let [q (string/replace q #"[\r\n]+" " ")
                          prefix "Q: "
                          max-len 50
                          available (- max-len (count prefix))]
                      (if (> (count q) available)
                        (str prefix (subs q 0 (- available 3)) "...")
                        (str prefix q)))
                    "Preparing question"))}})
