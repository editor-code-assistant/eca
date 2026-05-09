(ns eca.perf.stream-tool-calls
  "Small ad-hoc benchmarks for streamed tool-call argument processing.

  Run with:
    clojure -M:dev -m eca.perf.stream-tool-calls

  These benchmarks avoid live LLM and editor dependencies so the same workload can
  be replayed before/after optimizations. Numbers are intentionally simple wall
  clock timings; compare relative changes on the same machine/JVM."
  (:require
   [cheshire.core :as json]
   [eca.features.chat.tool-calls :as tc]
   [eca.features.tools :as f.tools]
   [eca.llm-util :as llm-util]
   [eca.messenger :as messenger]
   [eca.shared :as shared])
  (:import
   [java.io BufferedReader StringReader]))

(set! *warn-on-reflection* true)

(def default-chunks 5000)
(def default-chunk-size 128)

(defn- now []
  (System/nanoTime))

(defn- elapsed-ms [start-ns]
  (/ (double (- (now) start-ns)) 1000000.0))

(defn- fmt-ms [n]
  (format "%.2f" (double n)))

(defn- chunk-text [chunk-size]
  (apply str (take chunk-size (cycle "abcdefghijklmnopqrstuvwxyz0123456789"))))

(defn- make-db []
  {:workspace-folders [{:uri "file:///tmp" :name "tmp"}]
   :chats {"perf-chat" {:id "perf-chat"
                         :status :running
                         :messages []}}
   :tool-servers {}})

(defn- make-config []
  {:toolCall {:approval {:byDefault "allow"}
              :readFile {:maxLines 2000}}
   :disabledTools []})

(defrecord CountingMessenger [state* mode]
  messenger/IMessenger
  (chat-content-received [_this data]
    (let [json-bytes (when (= mode :json)
                       (count (json/generate-string data)))]
      (swap! state*
             (fn [state]
               (cond-> (-> state
                           (update :notifications (fnil inc 0))
                           (update :argument-bytes + (count (or (get-in data [:content :arguments-text]) ""))))
                 json-bytes (update :json-bytes + json-bytes))))))
  (chat-cleared [_this _params])
  (chat-status-changed [_this _params])
  (chat-deleted [_this _params])
  (chat-opened [_this _params])
  (rewrite-content-received [_this _data])
  (tool-server-updated [_this _params])
  (tool-server-removed [_this _params])
  (config-updated [_this _params])
  (provider-updated [_this _params])
  (jobs-updated [_this _params])
  (showMessage [_this _msg])
  (progress [_this _params])
  (editor-diagnostics [_this _uri])
  (ask-question [_this _params]))

(defn- make-chat-ctx [db* messenger]
  {:chat-id "perf-chat"
   :request-id "perf-request"
   :db* db*
   :messenger messenger})

(defn bench-transition-prepare!
  "Benchmark only the tool-call state transition + messenger notification path."
  [{:keys [chunks chunk-size messenger-mode]
    :or {chunks default-chunks
         chunk-size default-chunk-size
         messenger-mode :noop}}]
  (let [db* (atom (make-db))
        state* (atom {:notifications 0
                      :argument-bytes 0
                      :json-bytes 0})
        m (->CountingMessenger state* messenger-mode)
        chat-ctx (make-chat-ctx db* m)
        arg-chunk (chunk-text chunk-size)
        event-data {:name "write_file"
                    :server "eca"
                    :full-name "eca__write_file"
                    :origin :native
                    :arguments-text arg-chunk}
        start (now)]
    (dotimes [_ chunks]
      (tc/transition-tool-call! db* chat-ctx "call-1" :tool-prepare event-data))
    (assoc @state*
           :bench :transition-prepare
           :messenger-mode messenger-mode
           :chunks chunks
           :chunk-size chunk-size
           :elapsed-ms (elapsed-ms start))))

(defn bench-on-prepare-like!
  "Benchmark the heavier chat.clj-like callback path that recomputes all-tools,
  resolves the tool, computes summary, and then transitions for every delta."
  [{:keys [chunks chunk-size messenger-mode cache-tools?]
    :or {chunks default-chunks
         chunk-size default-chunk-size
         messenger-mode :noop
         cache-tools? false}}]
  (let [db* (atom (make-db))
        config (make-config)
        agent nil
        state* (atom {:notifications 0
                      :argument-bytes 0
                      :json-bytes 0})
        m (->CountingMessenger state* messenger-mode)
        chat-ctx (make-chat-ctx db* m)
        cached-tools (when cache-tools?
                       (vec (f.tools/all-tools "perf-chat" agent @db* config)))
        arg-chunk (chunk-text chunk-size)
        start (now)]
    (dotimes [_ chunks]
      (let [all-tools (or cached-tools
                          (vec (f.tools/all-tools "perf-chat" agent @db* config)))
            full-name "eca__write_file"
            tool (f.tools/resolve-tool full-name all-tools)]
        (tc/transition-tool-call!
         db* chat-ctx "call-1" :tool-prepare
         {:name (or (:name tool) full-name)
          :server (:name (:server tool))
          :full-name (or (:full-name tool) full-name)
          :origin (or (:origin tool) :unknown)
          :arguments-text arg-chunk
          :summary (f.tools/tool-call-summary all-tools full-name nil config @db*)})))
    (assoc @state*
           :bench :on-prepare-like
           :messenger-mode messenger-mode
           :cache-tools? cache-tools?
           :chunks chunks
           :chunk-size chunk-size
           :elapsed-ms (elapsed-ms start))))

(defn bench-all-tools!
  [{:keys [iterations]
    :or {iterations default-chunks}}]
  (let [db (make-db)
        config (make-config)
        start (now)
        last-count* (volatile! nil)]
    (dotimes [_ iterations]
      (vreset! last-count* (count (f.tools/all-tools "perf-chat" nil db config))))
    {:bench :all-tools
     :iterations iterations
     :tool-count @last-count*
     :elapsed-ms (elapsed-ms start)}))

(defn bench-camel-case!
  [{:keys [iterations argument-size]
    :or {iterations default-chunks
         argument-size default-chunk-size}}]
  (let [payload {:chat-id "perf-chat"
                 :role :assistant
                 :content {:type :toolCallPrepare
                           :id "call-1"
                           :name "write_file"
                           :server "eca"
                           :origin :native
                           :arguments-text (chunk-text argument-size)}}
        start (now)
        last* (volatile! nil)]
    (dotimes [_ iterations]
      (vreset! last* (shared/map->camel-cased-map payload)))
    {:bench :camel-case
     :iterations iterations
     :argument-size argument-size
     :last-keys (keys @last*)
     :elapsed-ms (elapsed-ms start)}))

(defn bench-event-data-seq!
  [{:keys [chunks chunk-size]
    :or {chunks default-chunks
         chunk-size default-chunk-size}}]
  (let [delta (chunk-text chunk-size)
        line (str "data: " (json/generate-string {:type "response.function_call_arguments.delta"
                                                   :delta delta}) "\n\n")
        body (apply str (repeat chunks line))
        rdr (BufferedReader. (StringReader. body))
        start (now)
        parsed-count (count (llm-util/event-data-seq rdr))]
    {:bench :event-data-seq
     :chunks chunks
     :chunk-size chunk-size
     :parsed-count parsed-count
     :elapsed-ms (elapsed-ms start)}))

(defn- print-result! [{:keys [elapsed-ms chunks iterations] :as result}]
  (let [n (or chunks iterations)
        per-op (when (and n (pos? n)) (/ elapsed-ms n))]
    (println
     (str (name (:bench result))
          " "
          (pr-str (dissoc result :elapsed-ms))
          " elapsed-ms=" (fmt-ms elapsed-ms)
          (when per-op
            (str " ms/op=" (fmt-ms per-op)))))))

(defn run-suite! [{:keys [chunks chunk-size]
                   :or {chunks default-chunks
                        chunk-size default-chunk-size}}]
  (println "Streaming tool-call benchmark")
  (println (str "chunks=" chunks " chunk-size=" chunk-size))
  (println "")
  ;; Warm up enough to load namespaces/classes and reduce first-call noise.
  (bench-transition-prepare! {:chunks 100 :chunk-size chunk-size :messenger-mode :noop})
  (bench-on-prepare-like! {:chunks 100 :chunk-size chunk-size :messenger-mode :noop})
  (doseq [result [(bench-transition-prepare! {:chunks chunks :chunk-size chunk-size :messenger-mode :noop})
                  (bench-transition-prepare! {:chunks chunks :chunk-size chunk-size :messenger-mode :json})
                  (bench-on-prepare-like! {:chunks chunks :chunk-size chunk-size :messenger-mode :noop :cache-tools? false})
                  (bench-on-prepare-like! {:chunks chunks :chunk-size chunk-size :messenger-mode :noop :cache-tools? true})
                  (bench-on-prepare-like! {:chunks chunks :chunk-size chunk-size :messenger-mode :json :cache-tools? false})
                  (bench-on-prepare-like! {:chunks chunks :chunk-size chunk-size :messenger-mode :json :cache-tools? true})
                  (bench-all-tools! {:iterations chunks})
                  (bench-camel-case! {:iterations chunks :argument-size chunk-size})
                  (bench-event-data-seq! {:chunks chunks :chunk-size chunk-size})]]
    (print-result! result)))

(defn- parse-long-or [s default]
  (if (some? s)
    (Long/parseLong s)
    default))

(defn -main [& args]
  (let [[chunks chunk-size] args]
    (run-suite! {:chunks (parse-long-or chunks default-chunks)
                 :chunk-size (parse-long-or chunk-size default-chunk-size)})))
