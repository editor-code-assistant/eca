(ns eca.api.test
  "For testing modules: run a module's tools, commands and event handlers
   with a mock ctx, away from ECA's session. Exceptions reach the test.

   The mock ctx holds a chat, `test-chat`, in its own db: `eca.api`'s chat
   state, messages, notifications and dialogs work on it. Functions that need
   ECA's session, like `complete!` and `send-prompt!`, don't."
  (:require
   [clojure.string :as string]
   [eca.db :as db]
   [eca.messenger :as messenger]
   [eca.modules.events :as events]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private mock-messenger
  "A messenger that records notifications and answers questions from
   `answers`, in order."
  [recorded* answers*]
  (let [record! (fn [k v] (swap! recorded* update k (fnil conj []) v))]
    (reify messenger/IMessenger
      (chat-content-received [_ data] (record! :chat-contents data))
      (chat-cleared [_ _])
      (chat-truncated [_ _])
      (chat-status-changed [_ _])
      (chat-deleted [_ _])
      (chat-opened [_ _])
      (rewrite-content-received [_ _])
      (tool-server-updated [_ _])
      (tool-server-removed [_ _])
      (config-updated [_ _])
      (provider-updated [_ _])
      (jobs-updated [_ _])
      (showMessage [_ msg] (record! :notifications msg))
      (progress [_ _])
      (editor-diagnostics [_ _] (future {:diagnostics []}))
      (editor-definition [_ _ _] (future nil))
      (editor-references [_ _ _ _] (future nil))
      (ask-question [_ params]
        (record! :questions (:question params))
        (let [[answer] (first (swap-vals! answers* rest))]
          (future (cond
                    (nil? answer) {:answer nil :cancelled true}
                    (map? answer) answer
                    :else {:answer (str answer) :cancelled false})))))))

(defn mock-ctx
  "A ctx for testing `module`. `opts`:
   - `:module`: the module map (its id goes in the ctx), or `:module-id`.
   - `:config`: what `(eca.api/config ctx)` returns; `{}` by default.
   - `:chat`: keys merged into the test chat, e.g. `{:agent \"code\"}`;
     `:messages`: its messages.
   - `:workspaces`: absolute paths.
   - `:answers`: the user's answers to dialogs, in order: a string, true and
     false for `confirm`, or nil to cancel.
   `(recorded ctx)` returns what the module showed the user."
  ([] (mock-ctx {}))
  ([{:keys [module module-id config chat messages workspaces answers]}]
   (let [recorded* (atom {})
         answers* (atom (map #(case % true "Yes" false "No" %) answers))
         db* (atom (assoc db/initial-db
                          :workspace-folders (mapv (fn [path] {:uri (shared/filename->uri path)}) workspaces)
                          :client-capabilities {:code-assistant {:chat-capabilities {:ask-question true}}}
                          :chats {"test-chat" (merge {:id "test-chat" :status :idle :messages (vec messages)}
                                                     chat)}))]
     {:module-id (or module-id (:id module))
      :chat-id "test-chat"
      :config (or config {})
      :db* db*
      :messenger (mock-messenger recorded* answers*)
      ::recorded* recorded*})))

(defn recorded
  "What the module showed the user through a mock ctx: `{:notifications
   [..] :questions [..]}`."
  [ctx]
  (some-> (::recorded* ctx) deref))

(defn ^:private ctx-for [module ctx]
  (assoc (or ctx (mock-ctx {:module module})) :module-id (:id module)))

(defn run-tool
  "Runs `module`'s tool `tool-name` with `args` (string keys, as the model
   sends them), and returns what its handler returns."
  ([module tool-name args] (run-tool module tool-name args nil))
  ([module tool-name args ctx]
   (let [tool (or (get-in module [:tools tool-name])
                  (throw (ex-info (str "The module has no tool " tool-name) {:tool tool-name})))]
     ((:handler tool) (ctx-for module ctx) args))))

(defn run-command
  "Runs `module`'s command `command-name` with `args`: the text typed after
   the command, or a map of its arguments. Returns what its handler returns."
  ([module command-name args] (run-command module command-name args nil))
  ([module command-name args ctx]
   (let [command (or (get-in module [:commands command-name])
                     (throw (ex-info (str "The module has no command " command-name) {:command command-name})))
         args (if (map? args)
                args
                (let [argv (remove string/blank? (string/split (str args) #"\s+"))]
                  (merge (zipmap (map (comp keyword :name) (:arguments command)) argv)
                         {:argv (vec argv) :raw (string/trim (str args))})))]
     ((:handler command) (ctx-for module ctx) args))))

(defn run-event
  "Runs `module`'s handlers of `event-id` for `event`, in order, as ECA
   would: returns the event with the handlers' changes for a notify or
   transform event (the first `:block` or `:stop` ends the chain), and the
   first answer for a handle event. The kind comes from the core events or
   the module's `:defines`."
  ([module event-id event] (run-event module event-id event nil))
  ([module event-id event ctx]
   (let [definition (or (get events/core-events event-id)
                        (get-in module [:defines :events event-id]))
         accepted (some-> (:accepts definition) keys set)
         ctx (ctx-for module ctx)
         handlers (->> (events/handler-entries (get-in module [:event-handlers event-id]))
                       (map-indexed (fn [i h] (assoc h :index i)))
                       (sort-by (juxt (comp - #(or (:priority %) 0)) :index)))]
     (if (= :handle (:kind definition))
       (some (fn [{:keys [handler match]}]
               (when (events/matches? match event)
                 (handler ctx event)))
             handlers)
       (reduce (fn [event {:keys [handler match]}]
                 (if-not (events/matches? match event)
                   event
                   (let [changes (handler ctx event)
                         changed (if (map? changes) (events/merge-changes event changes accepted) event)]
                     (if (or (and (:block changed) (not (:block event)))
                             (and (contains? changed :stop) (not (contains? event :stop))))
                       (reduced changed)
                       changed))))
               event
               handlers)))))
