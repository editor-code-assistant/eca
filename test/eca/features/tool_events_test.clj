(ns eca.features.tool-events-test
  "The tool and shell events modules get: :tool/after-call,
   :additional-context on :tool/before-call, and :shell/before-exec for the
   shell, git and custom tools, shell hooks and eca.api/shell!."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat :as f.chat]
   [eca.features.chat.lifecycle :as lifecycle]
   [eca.features.chat.tool-calls :as tc]
   [eca.features.tools :as f.tools]
   [eca.modules :as modules]
   [eca.modules.hooks :as f.hooks]
   [eca.modules.tools.custom-tools :as f.tools.custom]
   [eca.modules.tools.git :as f.tools.git]
   [eca.modules.tools.shell :as f.tools.shell]
   [eca.shell :as shell]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private events* (atom []))

(defn- with-module!
  "Adds an external module with these event handlers, each recording the
   events it gets in `events*`."
  [handlers]
  (reset! events* [])
  (modules/add-external! "/tmp/acme-tools.clj"
                         {:id :acme/tools
                          :event-handlers (update-vals handlers
                                                       (fn [f]
                                                         (fn [ctx event]
                                                           (swap! events* conj event)
                                                           (f ctx event))))}))

(def ^:private chat-id "chat-1")

(def ^:private all-tools
  [{:name "tool_a" :full-name "eca__tool_a" :origin :native :server {:name "eca"}}
   {:name "tool_b" :full-name "eca__tool_b" :origin :native :server {:name "eca"}}])

(defn- tool-call [id full-name]
  {:id id :full-name full-name :arguments {"x" 1} :arguments-text "{\"x\":1}"})

(defn- run-tools!
  "Runs `tool-calls` through `on-tools-called!`, every call returning
   `result`. Returns `:continue`, what the tool loop returned (nil when the
   turn ended), `:outputs`, the history's tool_call_output by id, `:called`,
   the toolCalled outputs the client got by id, and `:hooks`, the preToolCall
   and postToolCall hooks run, as `[hook-type data]`."
  [tool-calls & [{:keys [result] :or {result {:error false :contents [{:type :text :text "ok"}]}}}]]
  (h/reset-messenger!)
  (let [db* (h/db*)
        hooks* (atom [])
        _ (swap! db* assoc-in [:chats chat-id] {:id chat-id
                                                :status :running
                                                :prompt-id "prompt-1"
                                                :messages []
                                                :tool-calls (into {} (map (fn [{:keys [id]}] [id {:status :preparing}]))
                                                                  tool-calls)})
        chat-ctx {:db* db* :config (h/config) :chat-id chat-id :provider "openai" :agent "code"
                  :messenger (h/messenger) :metrics (h/metrics) :prompt-id "prompt-1"}
        add-to-history! (fn [msg] (swap! db* update-in [:chats chat-id :messages] (fnil conj []) msg))
        continue (with-redefs [f.tools/all-tools (constantly all-tools)
                               f.tools/approval (constantly :allow)
                               f.hooks/trigger-if-matches! (fn [hook-type data & _]
                                                             (swap! hooks* conj [hook-type data])
                                                             nil)
                               f.tools/call-tool! (fn [& _] result)
                               f.tools/tool-call-details-before-invocation (constantly nil)
                               f.tools/tool-call-details-after-invocation (constantly nil)
                               f.tools/tool-call-summary (constantly "Tool")
                               lifecycle/maybe-renew-auth-token (fn [_] nil)]
                   ((tc/on-tools-called! chat-ctx (atom "") add-to-history! []) tool-calls))
        messages (get-in @db* [:chats chat-id :messages])]
    {:continue continue
     :outputs (into {} (keep (fn [{:keys [role content]}]
                               (when (= "tool_call_output" role) [(:id content) (:output content)])))
                    messages)
     :called (into {} (keep (fn [{:keys [content]}]
                              (when (= :toolCalled (:type content)) [(:id content) (:outputs content)])))
                   (:chat-content-received (h/messages)))
     :hooks (filterv (comp #{:preToolCall :postToolCall} first) @hooks*)}))

(defn- system-texts []
  (->> (:chat-content-received (h/messages))
       (filter #(= :system (:role %)))
       (keep #(get-in % [:content :text]))))

(defn- context-text [s]
  (str "<additionalContext>\n" s "\n</additionalContext>"))

(deftest tool-after-call-test
  (testing "a module replaces the result: the model, the client and the postToolCall hooks get it"
    (with-module! {:tool/after-call (fn [_ {:keys [result]}]
                                      {:result (update result :contents
                                                       (partial mapv #(update % :text string/upper-case)))})})
    (let [{:keys [continue outputs called hooks]} (run-tools! [(tool-call "call-1" "eca__tool_a")])]
      (is (some? continue) "the tool loop goes on")
      (is (match? {"call-1" {:error false :contents [{:type :text :text "OK"}]}} outputs))
      (is (match? {"call-1" [{:type :text :text "OK"}]} called))
      (is (match? [[:preToolCall {:tool-name "tool_a"}] [:postToolCall {:tool-response #"^OK"}]] hooks))
      (is (match? [{:tool "eca__tool_a" :server "eca" :arguments {"x" 1} :error? false
                    :result {:contents [{:type :text :text "ok"}]}
                    :tool-call-id "call-1" :chat-id chat-id :agent "code"}]
                  @events*))))
  (testing "a string replaces the contents and keeps the error flag"
    (with-module! {:tool/after-call (constantly {:result "[REDACTED]"})})
    (let [{:keys [outputs]} (run-tools! [(tool-call "call-1" "eca__tool_a")]
                                        {:result {:error true :contents [{:type :text :text "secret"}]}})]
      (is (= {"call-1" {:error true :contents [{:type :text :text "[REDACTED]"}]}} outputs))))
  (testing "a value that isn't a result is ignored"
    (with-module! {:tool/after-call (constantly {:result 42})})
    (let [{:keys [outputs]} (run-tools! [(tool-call "call-1" "eca__tool_a")])]
      (is (= {"call-1" {:error false :contents [{:type :text :text "ok"}]}} outputs))))
  (testing "context goes to the model only"
    (with-module! {:tool/after-call (constantly {:additional-context "Lint: 2 warnings."})})
    (let [{:keys [outputs called]} (run-tools! [(tool-call "call-1" "eca__tool_a")])]
      (is (= {"call-1" {:error false :contents [{:type :text :text "ok"}
                                                {:type :text :text (context-text "Lint: 2 warnings.")}]}}
             outputs))
      (is (= {"call-1" [{:type :text :text "ok"}]} called))))
  (testing "a module stops the turn after the batch, and the postToolCall hooks don't run"
    (with-module! {:tool/after-call (fn [_ {:keys [tool]}] (when (= "eca__tool_a" tool) {:stop "Tests are red."}))})
    (let [{:keys [continue outputs hooks]} (run-tools! [(tool-call "call-1" "eca__tool_a")
                                                        (tool-call "call-2" "eca__tool_b")])]
      (is (nil? continue))
      (is (= #{"call-1" "call-2"} (set (keys outputs))) "every call of the batch ran")
      (is (some #{"Turn stopped by module 'acme/tools': Tests are red."} (system-texts)))
      (is (= [["call-2"]] (keep (fn [[hook-type data]] (when (= :postToolCall hook-type) [(:tool-call-id data)]))
                                hooks))
          "only the call the module didn't stop gets its hooks")))
  (testing "rejected calls don't get it"
    (with-module! {:tool/before-call (constantly {:block "No."})
                   :tool/after-call (constantly nil)})
    (run-tools! [(tool-call "call-1" "eca__tool_a")])
    (is (match? [{:approval keyword?}] @events*) "only :tool/before-call ran")
    (is (not-any? #(contains? % :result) @events*))))

(deftest tool-before-call-additional-context-test
  (testing "the context is added to the output of a call that runs"
    (with-module! {:tool/before-call (constantly {:additional-context "Prefer small edits."})})
    (let [{:keys [outputs called]} (run-tools! [(tool-call "call-1" "eca__tool_a")])]
      (is (= {"call-1" {:error false :contents [{:type :text :text "ok"}
                                                {:type :text :text (context-text "Prefer small edits.")}]}}
             outputs))
      (is (= {"call-1" [{:type :text :text "ok"}]} called))))
  (testing "after the :tool/after-call context"
    (with-module! {:tool/before-call (constantly {:additional-context "before"})
                   :tool/after-call (constantly {:additional-context "after"})})
    (let [{:keys [outputs]} (run-tools! [(tool-call "call-1" "eca__tool_a")])]
      (is (= [(context-text "before") (context-text "after")]
             (rest (map :text (get-in outputs ["call-1" :contents])))))))
  (testing "and to the output of a call a module blocks; then no preToolCall hook runs"
    (with-module! {:tool/before-call (constantly {:block "Secrets inside." :additional-context "Ask the user."})})
    (let [{:keys [continue outputs hooks]} (run-tools! [(tool-call "call-1" "eca__tool_a")])]
      (is (some? continue) "the model gets the rejection")
      (is (match? {"call-1" {:error true :contents [{:type :text :text #"Reason: Secrets inside\.$"}
                                                    {:type :text :text (context-text "Ask the user.")}]}}
                  outputs))
      (is (empty? hooks))))
  (testing "a block that isn't a string still blocks, with a default reason"
    (with-module! {:tool/before-call (constantly {:block true})})
    (let [{:keys [outputs]} (run-tools! [(tool-call "call-1" "eca__tool_a")])]
      (is (match? {"call-1" {:error true :contents [{:text #"Reason: Tool call blocked by module 'acme/tools'$"}]}}
                  outputs))))
  (testing "a module's stop names the module"
    (with-module! {:tool/before-call (constantly {:stop "Budget reached."})})
    (let [{:keys [continue hooks]} (run-tools! [(tool-call "call-1" "eca__tool_a")])]
      (is (nil? continue))
      (is (empty? hooks))
      (is (some #{"Turn stopped by module 'acme/tools': Budget reached."} (system-texts))))))

(def ^:private opts {:cwd "/tmp" :script "echo hi" :chat-id "chat-1"})

(defn- ctx [] {:config (h/config) :chat-id "chat-1"})

(deftest before-exec-test
  (testing "the modules change the command, its folder and variables"
    (with-module! {:shell/before-exec (fn [_ {:keys [command]}]
                                        {:command (str "ssh box " command) :cwd "/srv" :env {"A" "1"}})})
    (is (= {:cwd "/srv" :script "ssh box echo hi" :chat-id "chat-1" :env {"A" "1"}}
           (shell/before-exec (ctx) opts {:source :tool :tool "eca__shell_command" :timeout-ms 1000})))
    (is (= [{:command "echo hi" :cwd "/tmp" :env nil :timeout-ms 1000 :background? false
             :source :tool :tool "eca__shell_command" :chat-id "chat-1"}]
           @events*)))
  (testing "without changes the options stay as they were"
    (with-module! {:shell/before-exec (constantly nil)})
    (is (identical? opts (shell/before-exec (ctx) opts {:source :tool}))))
  (testing "values of the wrong type are ignored"
    (with-module! {:shell/before-exec (constantly {:command 42 :cwd "" :env "A=1"})})
    (is (= opts (shell/before-exec (ctx) opts {:source :tool}))))
  (testing "without a config no event runs"
    (with-module! {:shell/before-exec (constantly {:command "nope"})})
    (is (= opts (shell/before-exec {} opts {:source :tool})))
    (is (empty? @events*)))
  (when-not h/windows?
    (testing "a script file is offered as the command line that runs it; a new command runs as a script"
      (with-module! {:shell/before-exec (fn [_ {:keys [command]}] {:command (str "nice " command)})})
      (is (= {:cwd "/tmp" :script "nice bash '/tmp/it'\\''s.sh'"}
             (shell/before-exec (ctx) {:cwd "/tmp" :file "/tmp/it's.sh"} {:source :hook})))
      (is (match? [{:command "bash '/tmp/it'\\''s.sh'"}] @events*)))))

(deftest shell-tool-before-exec-test
  (when-not h/windows?
    (let [shell-command (get-in f.tools.shell/definitions ["shell_command" :handler])
          tool-ctx {:db (h/db) :config (h/config) :chat-id "chat-1"
                    :call-state-fn (constantly {:status :executing})
                    :state-transition-fn (constantly nil)}]
      (testing "the shell tool runs the command the modules give"
        (with-module! {:shell/before-exec (fn [_ {:keys [command]}] {:command (str command " world")})})
        (is (match? {:error false :contents [{:text "Exit code: 0"} {:text "Stdout:\nhello world"}]}
                    (shell-command {"command" "echo hello"} tool-ctx)))
        (is (match? [{:source :tool :tool "eca__shell_command" :command "echo hello"
                      :timeout-ms 60000 :background? false}]
                    @events*)))
      (testing "and so do background jobs"
        (let [captured (atom nil)]
          (with-module! {:shell/before-exec (fn [_ {:keys [command]}] {:command (str "nice " command)})})
          (with-redefs [shell/start-shell-process! (fn [opts] (reset! captured opts) (throw (ex-info "stop" {})))]
            (shell-command {"command" "sleep 1" "background" "nap"} (assoc tool-ctx :db* (h/db*))))
          (is (= "nice sleep 1" (:script @captured)))
          (is (match? [{:command "sleep 1" :background? true :timeout-ms nil}] @events*)))))))

(deftest git-tool-before-exec-test
  (let [captured (atom nil)
        git (get-in f.tools.git/definitions ["git" :handler])]
    (with-module! {:shell/before-exec (fn [_ {:keys [command]}] {:command (str "nice " command)})})
    (with-redefs [shell/start-shell-process! (fn [opts]
                                                       (reset! captured opts)
                                                       (doto (promise) (deliver {:exit 0 :out "" :err ""})))]
      (git {"command" "git status"} {:db (h/db) :config (h/config) :chat-id "chat-1"
                                     :call-state-fn (constantly {:status :executing})
                                     :state-transition-fn (constantly nil)}))
    (is (= "nice git status" (:script @captured)))
    (is (match? [{:source :tool :tool "eca__git" :command "git status" :timeout-ms 120000}] @events*))))

(deftest custom-tool-before-exec-test
  (let [captured (atom nil)
        handler (get-in (f.tools.custom/definitions {:customTools {"ls" {:description "List." :command "ls"}}})
                        ["ls" :handler])]
    (with-module! {:shell/before-exec (fn [_ {:keys [command]}] {:command (str "sandbox " command)})})
    (with-redefs [shell/start-shell-process! (fn [opts] (reset! captured opts) (delay {:out "" :exit 0}))]
      (handler {} (ctx)))
    (is (= "sandbox ls" (:script @captured)))
    (is (match? [{:source :tool :tool "eca__ls" :command "ls" :timeout-ms nil}] @events*))))

(deftest hook-before-exec-test
  (with-module! {:shell/before-exec (fn [_ {:keys [command]}] {:command (str "sandbox " command)})})
  (h/config! {:hooks {"notify" {:type "postRequest" :actions [{:type "shell" :shell "notify-send hi" :timeout 5000}]}
                      "check" {:type "preRequest" :actions [{:type "shell" :shell "check-prompt"}]}}})
  (let [opts* (atom [])]
    (with-redefs [f.hooks/run-shell-cmd (fn [opts] (swap! opts* conj opts) {:exit 0 :out nil :err nil})]
      (testing "shell hooks run the command the modules give"
        (f.hooks/trigger-if-matches! :postRequest {:chat-id "chat-1"} {} (h/db) (h/config))
        (is (match? [{:script "sandbox notify-send hi"}] @opts*))
        (is (match? [{:source :hook :hook "notify" :hook-type :postRequest :command "notify-send hi"
                      :timeout-ms 5000 :chat-id "chat-1"}]
                    @events*)))
      (testing "preRequest hooks too"
        (reset! opts* [])
        (reset! events* [])
        (#'f.chat/run-pre-request-hooks! {:db* (h/db*) :config (h/config) :chat-id "chat-1"
                                          :messenger (h/messenger) :message "hi"})
        (is (match? [{:script "sandbox check-prompt"}] @opts*))
        (is (match? [{:source :hook :hook "check" :hook-type :preRequest :timeout-ms 30000}] @events*))))))
