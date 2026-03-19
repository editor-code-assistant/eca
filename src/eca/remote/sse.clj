(ns eca.remote.sse
  "SSE connection management for the remote web control server.
   Each connected client gets a core.async channel with a dropping buffer.
   A writer loop per client reads from the channel and writes SSE-formatted
   text to the Ring async response."
  (:require
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [eca.logger :as logger])
  (:import
   [java.io OutputStream]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[REMOTE-SSE]")
(def ^:private buffer-size 256)
(def ^:private heartbeat-interval-ms 15000)

(defn create-connections
  "Creates a new SSE connections atom (set of client maps)."
  []
  (atom #{}))

(defn- write-sse! [^OutputStream os ^String data]
  (.write os (.getBytes data "UTF-8"))
  (.flush os))

(defn- format-sse-event [{:keys [event data]}]
  (str "event: " event "\n"
       "data: " (json/generate-string data) "\n\n"))

(defn- start-writer-loop!
  "Starts a go-loop that reads events from the client channel and writes
   SSE-formatted text to the output stream. Removes the client on error."
  [connections* {:keys [ch os] :as client}]
  (async/go-loop []
    (when-let [event (async/<! ch)]
      (let [ok? (try
                  (write-sse! os (format-sse-event event))
                  true
                  (catch Exception e
                    (logger/debug logger-tag "SSE write failed, removing client:" (.getMessage e))
                    (swap! connections* disj client)
                    (async/close! ch)
                    false))]
        (when ok?
          (recur))))))

(defn add-client!
  "Adds a new SSE client. Returns the client map with :ch and :os keys.
   Starts a writer loop for the client."
  [connections* ^OutputStream os]
  (let [ch (async/chan (async/dropping-buffer buffer-size))
        client {:ch ch :os os}]
    (swap! connections* conj client)
    (start-writer-loop! connections* client)
    client))

(defn remove-client!
  "Removes a client from the connections set and closes its channel."
  [connections* client]
  (swap! connections* disj client)
  (async/close! (:ch client)))

(defn broadcast!
  "Puts an event on all connected client channels.
   event-type is a string like \"chat:content-received\".
   data is the payload map."
  [connections* event-type data]
  (let [event {:event event-type :data data}]
    (doseq [{:keys [ch]} @connections*]
      (async/offer! ch event))))

(defn start-heartbeat!
  "Starts a background loop that sends SSE comment lines to all clients
   every 15 seconds. Returns a channel that can be closed to stop the loop."
  [connections*]
  (let [stop-ch (async/chan)]
    (async/go-loop []
      (let [[_ port] (async/alts! [stop-ch (async/timeout heartbeat-interval-ms)])]
        (when-not (= port stop-ch)
          (let [dead-clients (atom #{})]
            (doseq [{:keys [^OutputStream os] :as client} @connections*]
              (try
                (write-sse! os ":\n\n")
                (catch Exception _e
                  (swap! dead-clients conj client))))
            (when (seq @dead-clients)
              (swap! connections* #(reduce disj % @dead-clients))
              (doseq [{:keys [ch]} @dead-clients]
                (async/close! ch))))
          (recur))))
    stop-ch))

(defn close-all!
  "Closes all client channels and clears the connections set."
  [connections*]
  (doseq [{:keys [ch]} @connections*]
    (async/close! ch))
  (reset! connections* #{}))
