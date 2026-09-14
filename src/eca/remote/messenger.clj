(ns eca.remote.messenger
  "BroadcastMessenger wraps an inner IMessenger (typically ServerMessenger)
   and broadcasts events to all connected SSE clients."
  (:require
   [eca.messenger :as messenger]
   [eca.remote.sse :as sse]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private ->camel [data]
  (shared/map->camel-cased-map data))

(defn ^:private cancel-handle! [handle]
  (when (future? handle)
    ;; PendingRequest cancellation sends $/cancelRequest; a transport failure
    ;; must not prevent cleanup of the other handle or completion of the caller.
    (try (future-cancel handle) (catch Exception _ nil))))

(defn ^:private question-result [result]
  ;; Keep the registry promise raw: late handle attachment must wait for
  ;; publication without rethrowing a winning editor error.
  (let [unwrap (fn [response]
                 (if (instance? Exception response)
                   (throw response)
                   response))
        timeout-sentinel (Object.)]
    (reify
      clojure.lang.IDeref
      (deref [_] (unwrap @result))
      clojure.lang.IBlockingDeref
      (deref [_ timeout-ms timeout-value]
        (let [response (deref result timeout-ms timeout-sentinel)]
          (if (identical? timeout-sentinel response)
            timeout-value
            (unwrap response))))
      clojure.lang.IPending
      (isRealized [_] (realized? result)))))

(defn ^:private resolve-question! [pending-questions* request-id response cancel-handles?]
  (let [[old _] (swap-vals! pending-questions* dissoc request-id)]
    (when-let [{:keys [promise inner-result watcher]} (get old request-id)]
      (deliver promise response)
      (when cancel-handles?
        (cancel-handle! inner-result)
        (cancel-handle! watcher))
      true)))

(defn ^:private attach-handle! [pending-questions* request-id result key handle]
  (let [[old _] (swap-vals! pending-questions*
                           (fn [pending]
                             (if (identical? result (get-in pending [request-id :promise]))
                               (assoc-in pending [request-id key] handle)
                               pending)))]
    (when-not (identical? result (get-in old [request-id :promise]))
      ;; A winner may have claimed the entry but not yet published its result.
      ;; Don't interrupt that watcher until delivery is complete.
      @result
      (cancel-handle! handle))))

(defrecord BroadcastMessenger [inner sse-connections* pending-questions*]
  messenger/IMessenger

  (chat-content-received [_this data]
    (messenger/chat-content-received inner data)
    (sse/broadcast! sse-connections* "chat:content-received" (->camel data)))

  (chat-cleared [_this params]
    (messenger/chat-cleared inner params)
    (sse/broadcast! sse-connections* "chat:cleared" (->camel params)))

  (chat-status-changed [_this params]
    (messenger/chat-status-changed inner params)
    (sse/broadcast! sse-connections* "chat:status-changed" (->camel params)))

  (chat-deleted [_this params]
    (messenger/chat-deleted inner params)
    (sse/broadcast! sse-connections* "chat:deleted" (->camel params)))

  (chat-opened [_this params]
    (messenger/chat-opened inner params)
    (sse/broadcast! sse-connections* "chat:opened" (->camel params)))

  (rewrite-content-received [_this data]
    (messenger/rewrite-content-received inner data))

  (tool-server-updated [_this params]
    (messenger/tool-server-updated inner params)
    (sse/broadcast! sse-connections* "tool:server-updated" (->camel params)))

  (tool-server-removed [_this params]
    (messenger/tool-server-removed inner params)
    (sse/broadcast! sse-connections* "tool:server-removed" (->camel params)))

  (provider-updated [_this params]
    (messenger/provider-updated inner params)
    (sse/broadcast! sse-connections* "providers:updated" (->camel params)))

  (jobs-updated [_this params]
    (messenger/jobs-updated inner params)
    (sse/broadcast! sse-connections* "jobs:updated" (->camel params)))

  (config-updated [_this params]
    (messenger/config-updated inner params)
    (sse/broadcast! sse-connections* "config:updated" (->camel params)))

  (showMessage [_this msg]
    (messenger/showMessage inner msg)
    (sse/broadcast! sse-connections* "session:message" (->camel msg)))

  (progress [_this params]
    (messenger/progress inner params)
    (sse/broadcast! sse-connections* "session:progress" (->camel params)))

  (editor-diagnostics [_this uri]
    (messenger/editor-diagnostics inner uri))
  (editor-definition [_this uri position]
    (messenger/editor-definition inner uri position))
  (editor-references [_this uri position include-declaration]
    (messenger/editor-references inner uri position include-declaration))
  (ask-question [_this params]
    (let [request-id (or (:request-id params) (str (random-uuid)))
          result (promise)
          wire-params (-> params (dissoc :request-id) (assoc :requestId request-id))]
      ;; HTTP answers don't depend on SSE subscriptions. Register before asking
      ;; the editor, which may answer immediately.
      (swap! pending-questions* assoc request-id {:promise result})
      (try
        (let [inner-result (messenger/ask-question inner params)]
          (attach-handle! pending-questions* request-id result :inner-result inner-result)
          (let [watcher (future
                          (let [response (try
                                           (deref inner-result)
                                           (catch Exception e e))]
                            ;; Never cancel the currently executing watcher.
                            (resolve-question! pending-questions* request-id response false)))]
            (attach-handle! pending-questions* request-id result :watcher watcher)))
        (catch Exception e
          (resolve-question! pending-questions* request-id e true)))
      ;; Skip questions already claimed during dispatch or handle attachment.
      ;; This check does not serialize broadcasting with concurrent resolution.
      (when (identical? result (get-in @pending-questions* [request-id :promise]))
        (sse/broadcast! sse-connections* "chat:ask-question" (->camel wire-params)))
      (question-result result))))

(defn make-broadcast-messenger
  "Creates a BroadcastMessenger with a fresh pending-questions registry.
   Prefer this over `->BroadcastMessenger` so callers don't have to know
   about the internal registry atom."
  [inner sse-connections*]
  (->BroadcastMessenger inner sse-connections* (atom {})))

(defn answer-question!
  "Resolves a pending question by request-id: delivers
   `{:answer answer :cancelled (boolean cancelled)}` to the registered promise,
   cancels the editor's pending request (sending `$/cancelRequest`) and its
   watcher, and removes the entry. Returns true when a pending question was
   found and delivered, nil otherwise.

   Uses `swap-vals!` so claiming the entry is a single atomic op: under
   concurrent calls for the same request-id only the swap winner delivers."
  [{:keys [pending-questions*]} request-id answer cancelled]
  (resolve-question! pending-questions* request-id
                     {:answer answer :cancelled (boolean cancelled)} true))
