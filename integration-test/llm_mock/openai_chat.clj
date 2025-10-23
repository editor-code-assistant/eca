(ns llm-mock.openai-chat
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [llm-mock.mocks :as llm.mocks]
   [org.httpkit.server :as hk]))

(def ^:dynamic *thinking-tag* "think")

(defn set-thinking-tag! [tag]
  (alter-var-root #'*thinking-tag* (constantly tag)))

(defn ^:private send-sse!
  "Send a single SSE data line with a JSON payload, followed by a blank line."
  [ch m]
  (hk/send! ch (str "data: " (json/generate-string m) "\n\n") false))

(defn ^:private messages->normalized-input
  "Transforms OpenAI Chat messages into the canonical ECA :input + :instructions format
  used by tests for assertions. We extract the first system message as :instructions
  and convert remaining messages into :input entries with input_text/output_text."
  [messages]
  (let [instructions (some (fn [{:keys [role content]}]
                             (when (= role "system")
                               (if (string? content)
                                 (string/trim content)
                                 (->> content (map :text) (remove nil?) (string/join "\n")))))
                           messages)
        to-entry (fn [{:keys [role content]}]
                   (when (#{"user" "assistant"} role)
                     (let [text (cond
                                  (string? content) (string/trim content)
                                  (sequential? content) (->> content (map :text) (remove nil?) (string/join "\n"))
                                  :else (str content))]
                       (when-not (string/blank? text)
                         {:role role
                          :content [(if (= role "user")
                                      {:type "input_text" :text text}
                                      {:type "output_text" :text text})]}))))]
    {:instructions instructions
     :input (->> messages
                 (remove #(= "system" (:role %)))
                 (map to-entry)
                 (remove nil?)
                 vec)}))

(defn ^:private simple-text-0 [ch]
  ;; Stream two content chunks, then a usage chunk, then a finish chunk
  (send-sse! ch {:choices [{:delta {:content "Knock"}}]})
  (send-sse! ch {:choices [{:delta {:content " knock!"}}]})
  (send-sse! ch {:usage {:prompt_tokens 10 :completion_tokens 20}})
  (send-sse! ch {:choices [{:delta {} :finish_reason "stop"}]})
  (hk/close ch))

(defn ^:private simple-text-1 [ch]
  (send-sse! ch {:choices [{:delta {:content "Foo"}}]})
  (send-sse! ch {:usage {:prompt_tokens 10 :completion_tokens 5}})
  (send-sse! ch {:choices [{:delta {} :finish_reason "stop"}]})
  (hk/close ch))

(defn ^:private simple-text-2 [ch]
  (send-sse! ch {:choices [{:delta {:content "Foo"}}]})
  (send-sse! ch {:choices [{:delta {:content " bar!"}}]})
  (send-sse! ch {:choices [{:delta {:content "\n\n"}}]})
  (send-sse! ch {:choices [{:delta {:content "Ha!"}}]})
  (send-sse! ch {:usage {:prompt_tokens 5 :completion_tokens 15}})
  (send-sse! ch {:choices [{:delta {} :finish_reason "stop"}]})
  (hk/close ch))

(defn ^:private reasoning-text-0 [ch]
  (send-sse! ch {:choices [{:delta {:content (str "<" *thinking-tag* ">")}}]})
  (send-sse! ch {:choices [{:delta {:content "I should say"}}]})
  (send-sse! ch {:choices [{:delta {:content " hello"}}]})
  (send-sse! ch {:choices [{:delta {:content (str "</" *thinking-tag* ">")}}]})
  (send-sse! ch {:choices [{:delta {:content "hello"}}]})
  (send-sse! ch {:choices [{:delta {:content " there!"}}]})
  (send-sse! ch {:usage {:prompt_tokens 10 :completion_tokens 20}})
  (send-sse! ch {:choices [{:delta {} :finish_reason "stop"}]})
  (hk/close ch))

(defn ^:private reasoning-text-1 [ch]
  (send-sse! ch {:choices [{:delta {:content (str "<" *thinking-tag* ">")}}]})
  (send-sse! ch {:choices [{:delta {:content "I should say"}}]})
  (send-sse! ch {:choices [{:delta {:content " fine"}}]})
  (send-sse! ch {:choices [{:delta {:content (str "</" *thinking-tag* ">")}}]})
  (send-sse! ch {:choices [{:delta {:content "I'm "}}]})
  (send-sse! ch {:choices [{:delta {:content " fine"}}]})
  (send-sse! ch {:usage {:prompt_tokens 10 :completion_tokens 20}})
  (send-sse! ch {:choices [{:delta {} :finish_reason "stop"}]})
  (hk/close ch))

(defn ^:private chat-title-text-0 [ch]
  (send-sse! ch {:choices [{:delta {:content "Some Cool"}}]})
  (send-sse! ch {:choices [{:delta {:content " Title"}}]})
  (send-sse! ch {:usage {:prompt_tokens 5 :completion_tokens 10}})
  (send-sse! ch {:choices [{:delta {} :finish_reason "stop"}]})
  (hk/close ch))

(defn handle-openai-chat [req]
  ;; Capture and normalize the request body for assertions in tests
  (let [body (some-> (slurp (:body req)) (json/parse-string true))
        messages (:messages body)
        normalized (messages->normalized-input messages)
        normalized-body (merge normalized (select-keys body [:tools]))]
    (hk/as-channel
     req
     {:on-open (fn [ch]
                  ;; Send initial response headers for SSE
                 (hk/send! ch {:status 200
                               :headers {"Content-Type" "text/event-stream; charset=utf-8"
                                         "Cache-Control" "no-cache"
                                         "Connection" "keep-alive"}}
                           false)
                 (if (string/includes? (:content (first (:messages body))) llm.mocks/chat-title-generator-str)
                   (do
                     (Thread/sleep 2000) ;; avoid tests failing with mismatch order of contents
                     (chat-title-text-0 ch))
                   (do
                     (llm.mocks/set-req-body! llm.mocks/*case* normalized-body)
                     (case llm.mocks/*case*
                       :simple-text-0 (simple-text-0 ch)
                       :simple-text-1 (simple-text-1 ch)
                       :simple-text-2 (simple-text-2 ch)
                       :reasoning-0 (reasoning-text-0 ch)
                       :reasoning-1 (reasoning-text-1 ch)
                       ;; default fallback
                       (do
                         (send-sse! ch {:choices [{:delta {:content "hello"}}]})
                         (send-sse! ch {:choices [{:delta {} :finish_reason "stop"}]})
                         (hk/close ch))))))})))
