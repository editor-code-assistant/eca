(ns llm-mock.ollama
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [integration.helper :as h]
   [llm-mock.mocks :as llm.mocks]
   [org.httpkit.server :as hk]))

(defn ^:private sse-send!
  "Send one SSE data line with JSON, followed by a blank line."
  [ch m]
  (hk/send! ch (str "data: " (json/generate-string m) "\n\n") false))

(defn ^:private simple-text-0 [ch]
  (sse-send! ch {:message {:content "Knock"}})
  (sse-send! ch {:message {:content " knock!"}})
  (sse-send! ch {:done_reason "stop"})
  (hk/close ch))

(defn ^:private simple-text-1 [ch]
  (sse-send! ch {:message {:content "Foo"}})
  (sse-send! ch {:done_reason "stop"})
  (hk/close ch))

(defn ^:private simple-text-2 [ch]
  (sse-send! ch {:message {:content "Foo"}})
  (sse-send! ch {:message {:content " bar!"}})
  (sse-send! ch {:message {:content "\n\n"}})
  (sse-send! ch {:message {:content "Ha!"}})
  (sse-send! ch {:done_reason "stop"})
  (hk/close ch))

(defn ^:private reasoning-0 [ch]
  (sse-send! ch {:message {:thinking "I should say"}})
  (sse-send! ch {:message {:thinking " hello"}})
  (sse-send! ch {:message {:content "hello"}})
  (sse-send! ch {:message {:content " there!"}})
  (sse-send! ch {:done_reason "stop"})
  (hk/close ch))

(defn ^:private reasoning-1 [ch]
  (sse-send! ch {:message {:thinking "I should say"}})
  (sse-send! ch {:message {:thinking " fine"}})
  (sse-send! ch {:message {:content "I'm "}})
  (sse-send! ch {:message {:content " fine"}})
  (sse-send! ch {:done_reason "stop"})
  (hk/close ch))

(defn ^:private tool-calling-0 [body ch]
  (let [second-stage? (some #(= "tool" (:role %)) (:messages body))]
    (if-not second-stage?
      (let [args {:path (h/project-path->canon-path "resources")}]
        (sse-send! ch {:message {:content "I will list files"}})
        (sse-send! ch {:message {:tool_calls [{:type "function"
                                               :function {:name "eca_directory_tree"
                                                          :arguments args}}]}})
        (sse-send! ch {:done_reason "stop"})
        (hk/close ch))
      (do
        (sse-send! ch {:message {:content "The files I see:\n"}})
        (sse-send! ch {:message {:content "file1\nfile2\n"}})
        (sse-send! ch {:done_reason "stop"})
        (hk/close ch)))))

(defn ^:private chat-title-text-0 [ch]
  (hk/send! ch
            (json/generate-string
             {:message {:content "Some Cool Title"}})
            true))

(defn handle-ollama-chat [req]
  (let [body-str (slurp (:body req))
        body (some-> body-str
                     (json/parse-string true))]
    (hk/as-channel
     req
     {:on-open (fn [ch]
                 (hk/send! ch {:status 200
                               :headers {"Content-Type" "text/event-stream; charset=utf-8"
                                         "Cache-Control" "no-cache"
                                         "Connection" "keep-alive"}}
                           false)
                 (if (string/includes? (:content (first (:messages body))) llm.mocks/chat-title-generator-str)
                   (chat-title-text-0 ch)
                   (do
                     (llm.mocks/set-req-body! llm.mocks/*case* body)
                     (case llm.mocks/*case*
                       :simple-text-0 (simple-text-0 ch)
                       :simple-text-1 (simple-text-1 ch)
                       :simple-text-2 (simple-text-2 ch)
                       :reasoning-0 (reasoning-0 ch)
                       :reasoning-1 (reasoning-1 ch)
                       :tool-calling-0 (tool-calling-0 body ch)))))})))

(defn handle-ollama-tags [_req]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:models [{:model "qwen3"}]})})

(defn handle-ollama-show [req]
  (let [_body (some-> (slurp (:body req)) (json/parse-string true))]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string {:capabilities ["tools" "thinking"]})}))
