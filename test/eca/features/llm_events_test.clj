(ns eca.features.llm-events-test
  "The LLM request, config and model events modules get:
   :llm/before-request, :config/resolve and :models/resolve."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.chat :as f.chat]
   [eca.features.chat.lifecycle :as lifecycle]
   [eca.features.tools :as f.tools]
   [eca.llm-api :as llm-api]
   [eca.llm-providers.ollama :as llm-providers.ollama]
   [eca.models :as models]
   [eca.modules :as modules]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private events* (atom []))

(defn- with-module!
  "Adds an external module with these event handlers, each recording the
   events it gets in `events*`."
  [handlers]
  (reset! events* [])
  (modules/add-external! "/tmp/acme-llm.clj"
                         {:id :acme/llm
                          :event-handlers (update-vals handlers
                                                       (fn [f]
                                                         (fn [ctx event]
                                                           (swap! events* conj event)
                                                           (f ctx event))))}))

(defn- text [s] {:role "user" :content [{:type :text :text s}]})

(def ^:private history
  [(text "old")
   {:role "compact_marker" :content {:auto? true}}
   (text "summary")
   {:role "assistant" :content [{:type :text :text "ok"}]}
   (text "fix the bug")
   {:role "tool_call" :content {:id "call-1" :name "read_file"}}
   {:role "tool_call_output" :content {:id "call-1" :output {:contents [{:type :text :text "secret-123"}]}}}])

(defn- chat-ctx []
  {:db* (h/db*) :config (h/config) :chat-id "chat-1" :full-model "openai/gpt-5.2" :agent "code"})

(defn- setup-chat! []
  (swap! (h/db*) #(-> %
                      (assoc-in [:chats "chat-1"] {:id "chat-1"
                                                   :messages history
                                                   :usage {:last-input-tokens 1000 :last-output-tokens 200}})
                      (assoc-in [:models "openai/gpt-5.2"] {:limit {:context 10000}}))))

(defn- redact [message]
  (cond-> message
    (= "tool_call_output" (:role message))
    (update-in [:content :output :contents] (partial mapv #(update % :text string/replace "secret-123" "[REDACTED]")))))

(deftest before-llm-request-test
  (setup-chat!)
  (let [request {:past-messages (subvec history 2) :user-messages [] :instructions "Be nice." :tools [{:name "t"}]}]
    (testing "without handlers the request is returned as is"
      (is (identical? request (lifecycle/before-llm-request (chat-ctx) request))))
    (testing "modules see the request and the history, and change what the model sees"
      (with-module! {:llm/before-request (fn [_ {:keys [request]}]
                                           {:request (update request :past-messages #(mapv redact %))})})
      (let [sent (lifecycle/before-llm-request (chat-ctx) request)]
        (is (= "[REDACTED]" (get-in sent [:past-messages 4 :content :output :contents 0 :text])))
        (is (= (dissoc request :past-messages) (dissoc sent :past-messages)))
        (is (= history (get-in (h/db) [:chats "chat-1" :messages])) "the history doesn't change"))
      (is (match? [{:request (assoc request :model "openai/gpt-5.2")
                    :history history
                    :since-checkpoint (subvec history 2)
                    :turn (subvec history 4)
                    :budget 8800
                    :chat-id "chat-1" :agent "code" :model "openai/gpt-5.2"}]
                  @events*)))
    (testing "new user messages count in the turn"
      (with-module! {:llm/before-request (constantly nil)})
      (lifecycle/before-llm-request (chat-ctx) (assoc request :user-messages [(text "and add a test")]))
      (is (match? [{:turn [(text "and add a test")]
                    :since-checkpoint (conj (subvec history 2) (text "and add a test"))}]
                  @events*)))
    (testing "changes of the wrong type, and model changes, are ignored"
      (with-module! {:llm/before-request (fn [_ {:keys [request]}]
                                           {:request (assoc request :tools "all" :instructions 42 :model "openai/o3")})})
      (is (= request (lifecycle/before-llm-request (chat-ctx) request))))
    (testing "a request that isn't a map is ignored"
      (with-module! {:llm/before-request (constantly {:request "nope"})})
      (is (= request (lifecycle/before-llm-request (chat-ctx) request))))))

(deftest chat-requests-go-through-the-event-test
  (with-module! {:llm/before-request (fn [_ {:keys [request]}]
                                       {:request (update request :user-messages
                                                         (partial mapv #(update % :content conj {:type :text :text "(from acme)"})))})})
  (let [sent* (atom nil)]
    (with-redefs [llm-api/sync-or-async-prompt! (fn [{:keys [on-before-request on-first-response-received on-message-received]
                                                      :as opts}]
                                                  (reset! sent* (on-before-request (select-keys opts [:past-messages :user-messages
                                                                                                      :instructions :tools])))
                                                  (on-first-response-received {:type :text :text "ok"})
                                                  (on-message-received {:type :text :text "ok"})
                                                  (on-message-received {:type :finish}))
                  llm-api/sync-prompt! (constantly nil)
                  f.tools/all-tools (constantly [])
                  config/await-plugins-resolved! (constantly true)]
      (h/config! {:env "test"})
      (swap! (h/db*) update :models #(merge {"openai/gpt-5.2" {:tools true}} %))
      (f.chat/prompt {:message "hello"} (h/db*) (h/messenger) (h/config) (h/metrics)))
    (is (match? {:user-messages [{:role "user" :content [{:type :text :text "hello"} {:type :text :text "(from acme)"}]}]}
                @sent*))
    (is (match? [{:request {:user-messages [{:role "user"}]} :turn [{:role "user"}]}] @events*))))

(deftest config-resolve-test
  (reset! config/initialization-config* {:pureConfig true :chatRetentionDays 7})
  (config/clear-cache!)
  (with-module! {:config/resolve (fn [_ {:keys [config]}]
                                   {:config (assoc config
                                                   :chatRetentionDays 1
                                                   :modules {:eca/shell {:enabled false}})})})
  (let [config (config/all (h/db))]
    (testing "a module changes the merged config, over the user's"
      (is (= 1 (:chatRetentionDays config)))
      (is (= 7 (get-in (first @events*) [:config :chatRetentionDays]))))
    (testing "but not which modules are active"
      (is (not= {:eca/shell {:enabled false}} (:modules config)))
      (is (modules/active? config :eca/shell))))
  (testing "a config that isn't a map is ignored"
    (with-module! {:config/resolve (constantly {:config "nope"})})
    (config/clear-cache!)
    (is (= 7 (:chatRetentionDays (config/all (h/db)))))))

(deftest models-resolve-test
  (with-module! {:models/resolve (fn [_ {:keys [models]}]
                                   {:models (assoc models "router/fast" {:tools true :reason? false})})})
  (let [updated* (atom nil)]
    (with-redefs [eca.models/models-dev (constantly {})
                  eca.models/fetch-provider-model-catalogs (constantly {:models {}})
                  llm-providers.ollama/list-models (constantly [])]
      (models/sync-models! (h/db*) (h/config) #(reset! updated* %)))
    (testing "a module adds a model to the list"
      (is (= {:tools true :reason? false} (get-in (h/db) [:models "router/fast"])))
      (is (contains? @updated* "router/fast")))
    (is (match? [{:models map?}] @events*))))
