(ns eca.modules.events
  "Event dispatch to the active modules' `:event-handlers`.

   Three kinds of events:
   - notify: something happened; handlers may only add the accepted keys.
   - transform: handlers may change the event, or stop the action with
     `:block` or `:stop`; the next handler sees the changes.
   - handle: ECA needs one answer; the first handler that returns non-nil
     gives it, and the rest aren't asked.

   Handlers run by priority, higher first, then module id. A failing handler
   counts as nil."
  (:require
   [clojure.string :as string]
   [eca.logger :as logger]
   [eca.modules :as modules]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MODULES]")

(def core-events
  "The events core emits, defined by `:eca/core`. Every chat event also has
   `:chat-id`, `:agent`, `:model`, `:variant` and, for subagents,
   `:parent-chat-id`."
  {:session/started {:kind :notify
                     :doc "ECA started: plugins and modules are loaded."
                     :payload {:workspaces "The workspace folders."}}
   :session/ended {:kind :notify
                   :doc "ECA is shutting down."}
   :chat/started {:kind :notify
                  :doc "A chat starts, or resumes."
                  :payload {:resumed? "Whether the chat was resumed."}
                  :accepts {:additional-context "Added to the chat's system prompt."
                            :stop "Ends the turn, with this reason."}}
   :chat/ended {:kind :notify
                :doc "A chat was deleted."}
   :chat/status-changed {:kind :notify
                         :doc "A chat's status changed."
                         :payload {:status "The new status."
                                   :pending-approval-ids "Tool calls waiting for approval."
                                   :pending-question-ids "Questions waiting for an answer."
                                   :running-tool-ids "Tool calls running."
                                   :waiting-reason "Why the chat waits, if it does."}}
   :chat/message-added {:kind :notify
                        :doc "A message was added to a chat's history."
                        :payload {:message "The message: :role and :content."}}
   :chat/usage-updated {:kind :notify
                        :doc "A response reported its token usage."
                        :payload {:usage "The chat's usage, as clients get it: tokens, costs and limits."
                                  :session-tokens "Tokens the chat's context holds."
                                  :context-limit "The model's context limit."
                                  :context-used "From 0 to 1."}}
   :chat/input {:kind :transform
                :doc "Text the user sent, before command and skill expansion."
                :payload {:text "The text." :source ":user or :module."}
                :accepts {:text "Replaces the text."
                          :handled "A reply string, or true: the model isn't called."}}
   :chat/before-request {:kind :transform
                         :doc "Once per user message, before the first LLM request."
                         :payload {:prompt "The user's prompt."}
                         :accepts {:prompt "Replaces the prompt."
                                   :additional-context "Added to the request."
                                   :block "Skips the request, with this reason."
                                   :stop "Ends the turn, with this reason."}}
   :llm/before-request {:kind :transform
                        :doc "Before every LLM call of a chat, the tool loop included. Decides what the model sees."
                        :payload {:request (str "The request to send: :model, :instructions, :past-messages, "
                                                ":user-messages (new, not in the history yet) and :tools.")
                                  :history "The chat's whole history."
                                  :since-checkpoint "Messages after the last checkpoint, the current turn included."
                                  :turn "The current user message and the tool calls after it."
                                  :budget "Tokens left in the context window, from the last response's usage."}
                        :accepts {:request (str "Replaces the request's messages, :instructions (not in the tool "
                                                "loop) or :tools; the history doesn't change. :model can't change yet.")}}
   :llm/request-failed {:kind :handle
                        :doc (str "An LLM request of a chat failed, after ECA's own retries. Asked once per turn, "
                                  "before ECA's recovery; no answer: ECA handles it as before.")
                        :payload {:request "The request: :model and :user-messages."
                                  :error "The error, classified: :type (e.g. :context-overflow), :message, :status."}
                        :accepts {:retry "true: send the request again. Request changes aren't supported yet."
                                  :compact "Compaction options, :trigger and :instructions: compact, then resume the turn."}}
   :chat/after-request {:kind :notify
                        :doc "The model finished responding to a user message."
                        :payload {:response "The response."}
                        :accepts {:follow-up "Sent as the next user message."
                                  :stop "Ends the turn, with this reason."}}
   :tool/before-call {:kind :transform
                      :doc (str "Before a tool call runs. :eca/approval sets :approval from the config rules first "
                                "(priority 1000); a deny from the user's config stays deny.")
                      :payload {:tool "The tool's full name." :server "Its server." :arguments "The arguments."
                                :approval ":allow, :ask or :deny; :ask until a module sets it."
                                :tool-call-id "The tool call's id."}
                      :accepts {:arguments "Replaces the arguments." :approval "Replaces the approval."
                                :reason "Why, as {:code :text}: shown with the decision."
                                :block "Rejects the call, with this reason for the model."
                                :stop "Ends the turn, with this reason."
                                :additional-context "Added to the call's output for the model, run or rejected."}}
   :tool/after-call {:kind :transform
                     :doc (str "After a tool call ran, before the model, the client and the postToolCall hooks "
                               "see its result.")
                     :payload {:tool "The tool's full name." :server "Its server." :arguments "The arguments."
                               :result "The result: {:error :contents}." :error? "Whether it failed."
                               :tool-call-id "The tool call's id."}
                     :accepts {:result "Replaces the result: a result map, or a string for a text result."
                               :additional-context "Added to the result for the model only."
                               :stop "Ends the turn after this batch of tool calls, with this reason."}}
   :chat/before-compact {:kind :transform
                         :doc "Before a compaction."
                         :payload {:trigger ":manual, :auto or :overflow." :instructions "Extra instructions."}
                         :accepts {:block "Skips the compaction, with this reason."
                                   :stop "Skips the compaction and ends the turn."
                                   :instructions "Replaces the instructions."}}
   :chat/compact {:kind :handle
                  :doc (str "Compact the chat. The first module that answers does it, writing the result with "
                            "eca.api/checkpoint!; without an answer, :eca/compact-as-summary summarizes the chat.")
                  :payload {:trigger ":manual, :auto or :overflow." :instructions "Extra instructions."}
                  :accepts {:status ":done or :failed." :resume? "Whether the interrupted turn goes on; true by default."
                            :message "Shown to the user."}
                  ;; compacting usually takes an LLM call
                  :timeout-ms 300000}
   :chat/after-compact {:kind :notify
                        :doc "After a compaction wrote a checkpoint."
                        :payload {:trigger ":manual, :auto or :overflow."
                                  :checkpoint "The checkpoint: its :marker and the :messages after it."}
                        :accepts {:additional-context "Added after the checkpoint."
                                  :stop "Ends the turn, with this reason."}}
   :chat/title {:kind :handle
                :doc "Give a chat its title."
                :payload {:messages "The chat's user and assistant messages since the last checkpoint."
                          :retitle? "Whether the chat is titled again, at its third prompt, with more context."}
                :accepts {:title "The title."}}
   :shell/before-exec {:kind :transform
                       :doc (str "Before every shell command ECA runs: the shell and git tools, custom tools, "
                                 "shell hooks and eca.api/shell!.")
                       :payload {:command "The command line." :cwd "Its folder."
                                 :env "Extra environment variables, name → value."
                                 :timeout-ms "When it's killed, if ever." :background? "A background job."
                                 :source (str ":tool, :hook or :module, with the :tool full name, the :hook name "
                                              "and :hook-type, or the calling :module.")}
                       :accepts {:command "Replaces the command line." :cwd "Replaces the folder."
                                 :env "Replaces the extra environment variables."}}
   :config/resolve {:kind :transform
                    :doc (str "After all config layers merge, each time the config is read again (it's cached). "
                              "Read the config from the event, not through eca.api.")
                    :payload {:config "The config."}
                    :accepts {:config "Replaces the config, except its modules key."}}
   :models/resolve {:kind :transform
                    :doc "After the model list is built."
                    :payload {:models "Full model name → capabilities."}
                    :accepts {:models "Replaces the model list."}}})

;; --- Index of definitions and handlers, rebuilt when the active modules change

(defn handler-entries
  "A module's `:event-handlers` entry as a vector of `{:handler ...}` maps."
  [spec]
  (cond
    (sequential? spec) (mapv #(if (map? %) % {:handler %}) spec)
    (map? spec) [spec]
    :else [{:handler spec}]))

(defn ^:private build-index [{:keys [active]}]
  (let [definitions (reduce (fn [acc entry]
                              (reduce-kv (fn [acc event-id definition]
                                           (if (contains? acc event-id)
                                             (do (logger/warn logger-tag
                                                              (format "Event %s is defined by %s and %s; using the first"
                                                                      event-id (:module (get acc event-id)) (:id entry)))
                                                 acc)
                                             (assoc acc event-id (assoc definition :module (:id entry)))))
                                         acc
                                         (get-in (:module entry) [:defines :events])))
                            {}
                            active)
        handlers (for [entry active
                       [event-id spec] (:event-handlers (:module entry))
                       [i h] (map-indexed vector (handler-entries spec))]
                   {:event-id event-id
                    :entry entry
                    :handler (:handler h)
                    :match (:match h)
                    :timeout-ms (:timeout-ms h)
                    :priority (if (integer? (:priority h)) (:priority h) (:priority entry))
                    :index i})]
    (doseq [[event-id module-ids] (->> handlers
                                       (remove #(contains? definitions (:event-id %)))
                                       (group-by :event-id))]
      (logger/warn logger-tag (format "%s handle %s, which no module defines"
                                      (string/join ", " (distinct (map (comp :id :entry) module-ids))) event-id)))
    {:definitions definitions
     :handlers (update-vals (group-by :event-id handlers)
                            (fn [hs] (vec (sort-by (juxt (comp - :priority) (comp :id :entry) :index) hs))))}))

(def ^:private index-cache* (atom nil))

(defn ^:private index [config]
  (let [resolution (modules/resolution config)
        cached @index-cache*]
    (if (identical? resolution (:resolution cached))
      (:index cached)
      (let [idx (build-index resolution)]
        (reset! index-cache* {:resolution resolution :index idx})
        idx))))

(defn definitions
  "Event id → definition, from the active modules' `:defines`."
  [config]
  (:definitions (index config)))

(defn handlers
  "The handlers of `event-id`, in the order they run."
  [config event-id]
  (get-in (index config) [:handlers event-id]))

;; --- Dispatch

(defn ^:private value-matches? [expected actual]
  (cond
    (set? expected) (contains? expected actual)
    (instance? java.util.regex.Pattern expected) (and (string? actual) (boolean (re-find expected actual)))
    :else (= expected actual)))

(defn matches?
  "Whether `event` matches a handler's `:match`."
  [match event]
  (every? (fn [[k expected]] (value-matches? expected (get event k))) match))

(defn merge-changes
  "Merges a handler's changes into the event: `:additional-context` adds up
   (strings only), other keys replace; a nil or false `:stop` or `:block` is
   no change. With `accepted`, other keys are ignored."
  [event changes accepted]
  (reduce-kv (fn [event k v]
               (cond
                 (and accepted (not (contains? accepted k))) event
                 (and (#{:stop :block} k) (not v)) event
                 (= :additional-context k) (if (or (not (string? v)) (string/blank? v))
                                             event
                                             (update event k #(if (string/blank? %) v (str % "\n" v))))
                 :else (assoc event k v)))
             event
             changes))

;; --- Tracing, for eca.api.debug/trace!

;; chat id → (fn [event-id kind event result])
(defonce ^:private tracers* (atom {}))

(defn trace!
  "Calls `(f event-id kind event result)` after each event emitted for chat
   `chat-id`, for debugging; a nil `f` stops tracing that chat."
  [chat-id f]
  (if f
    (swap! tracers* assoc chat-id f)
    (swap! tracers* dissoc chat-id))
  nil)

(defn untrace-all!
  "Stops tracing every chat."
  []
  (reset! tracers* {})
  nil)

(defn ^:private trace-event! [ctx event-id kind event result]
  (let [tracers @tracers*]
    (when (seq tracers)
      (when-let [f (get tracers (or (:chat-id event) (:chat-id ctx)))]
        (try
          (f event-id kind event result)
          (catch Exception e
            (logger/debug logger-tag "Could not trace event" event-id e)))))))

(defn ^:private call-handler [ctx event-id definition {:keys [entry handler timeout-ms]} event]
  (let [module-ctx (modules/module-ctx ctx entry)]
    (modules/invoke-safe module-ctx entry (str "handler of " event-id)
                         {:timeout-ms (modules/timeout-for entry timeout-ms
                                                           (or (:timeout-ms definition)
                                                               modules/default-handler-timeout-ms))}
                         handler module-ctx event)))

(defn emit!
  "Runs the handlers of `event-id` for `event`. `ctx` carries `:config`, and
   what handlers get as their ctx (`:db*`, `:messenger`, `:chat-id`...).
   - notify and transform events return the event with the handlers' accepted
     changes merged; the first `:block` or `:stop` ends the chain, and
     `::ended-by` names the module that ended it.
   - handle events return the first non-nil answer, or nil."
  [ctx event-id event]
  (let [{:keys [definitions handlers]} (index (:config ctx))
        definition (get definitions event-id)
        ;; a notify event without :accepts takes no change; an event nobody
        ;; defines, or a transform without :accepts, takes any
        accepted (cond
                   (:accepts definition) (set (keys (:accepts definition)))
                   (= :notify (:kind definition)) #{}
                   :else nil)
        hs (get handlers event-id)
        result (if (= :handle (:kind definition))
                 (some (fn [h]
                         (when (matches? (:match h) event)
                           (call-handler ctx event-id definition h event)))
                       hs)
                 (reduce (fn [event h]
                           (if-not (matches? (:match h) event)
                             event
                             (let [changes (call-handler ctx event-id definition h event)]
                               (cond
                                 (nil? changes) event

                                 (not (map? changes))
                                 (do (logger/warn logger-tag (format "Module %s returned a %s from a handler of %s; expected a map or nil"
                                                                     (:id (:entry h)) (type changes) event-id))
                                     event)

                                 :else
                                 (let [changed (merge-changes event changes accepted)]
                                   (if (or (and (:block changed) (not (:block event)))
                                           (and (contains? changed :stop) (not (contains? event :stop))))
                                     (reduced (assoc changed ::ended-by (:id (:entry h))))
                                     changed))))))
                         event
                         hs))]
    (trace-event! ctx event-id (:kind definition) event result)
    result))
