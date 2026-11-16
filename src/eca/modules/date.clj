(ns eca.modules.date
  "The `:eca/date` module. Tells the model today's date: with the first user
   message of each context window and day, and on a compaction's summary,
   so the system prompt, and the provider's prompt cache, stay the same
   across days."
  (:require
   [eca.api :as eca]))

(set! *warn-on-reflection* true)

(defn ^:private date-text []
  (str "Current date: " (java.time.LocalDate/now)))

(defn ^:private before-request
  "Today's date, unless the model has it in this context window already."
  [ctx _event]
  (let [text (date-text)]
    (when-not (some #(= text (:text %)) (eca/added-contexts ctx {:since-checkpoint? true}))
      {:additional-context text})))

(defn ^:private after-compact
  "A new context window starts with today's date, on the summary."
  [_ctx _event]
  {:additional-context (date-text)})

(def module
  {:id :eca/date
   :description "Tells the model today's date, once per context window and day."
   :event-handlers {:chat/before-request before-request
                    :chat/after-compact after-compact}})
