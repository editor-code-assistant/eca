(ns eca.modules.mcp-module-test
  "MCP belongs to :eca/mcp: disabled, no server runs and their tools, prompts
   and prompt sections are hidden. Its servers start when the session is
   ready, follow config changes and stop with the module; its protocol
   methods."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.commands :as f.commands]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools :as f.tools]
   [eca.modules :as modules]
   [eca.modules.events :as events]
   [eca.modules.mcp :as m.mcp]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private disabled {:modules {:eca/mcp {:enabled false}}})

(def ^:private db-with-mcp
  {:mcp-clients {"myMCP" {:status :running
                          :version "1.0"
                          :instructions "Use myMCP for the docs."
                          :tools [{:name "lookup" :description "Look up." :parameters {:type "object"}}]
                          :prompts [{:name "summarize" :description "Summarize."}]}}})

(deftest mcp-tools-and-prompts-test
  (testing "MCP tools are listed while the module is active"
    (with-redefs [m.mcp/all-prompts (constantly [{:name "summarize" :server "myMCP" :description "Summarize."}])]
      (is (some #(= "myMCP__lookup" (:full-name %)) (f.tools/all-tools "c1" "code" db-with-mcp {})))
      (is (some #(= "myMCP:summarize" (:name %)) (f.commands/all-commands db-with-mcp {})))
      (testing "and hidden while it's disabled"
        (is (not-any? #(= "myMCP__lookup" (:full-name %)) (f.tools/all-tools "c1" "code" db-with-mcp disabled)))
        (is (not-any? #(= "myMCP:summarize" (:name %)) (f.commands/all-commands db-with-mcp disabled)))))))

(defn ^:private ctx [config]
  {:db* (h/db*) :messenger (h/messenger) :metrics (h/metrics) :config config})

(deftest mcp-servers-test
  (testing "the servers start when the session is ready, unless the module is disabled"
    (let [started* (atom 0)]
      (with-redefs [m.mcp/initialize-servers-async! (fn [& _] (swap! started* inc))]
        (events/emit! (ctx disabled) :session/ready {})
        (is (= 0 @started*))
        (events/emit! (ctx {}) :session/ready {})
        (is (= 1 @started*)))))
  (testing "enabled once the session is ready, the module starts them right away"
    (let [started* (atom 0)
          start (:start m.mcp/module)]
      (with-redefs [m.mcp/initialize-servers-async! (fn [& _] (swap! started* inc))]
        (start (ctx {}))
        (is (= 0 @started*) "before that, :session/ready starts them")
        (modules/session-ready!)
        (start (ctx {}))
        (is (= 1 @started*)))))
  (testing "a config change reconciles the servers while the module was active"
    (let [calls* (atom [])
          servers {"myMCP" {:command "my-mcp"}}]
      (with-redefs [m.mcp/reconcile-servers! (fn [prev-config new-config & _]
                                               (swap! calls* conj [(:mcpServers prev-config) (:mcpServers new-config)]))]
        (events/emit! (ctx {:mcpServers {}}) :config/changed
                      {:prev-config {:mcpServers servers} :config {:mcpServers {}}})
        (testing "; enabling the module starts them in :start, disabling it stops them in :stop"
          (events/emit! (ctx {:mcpServers servers}) :config/changed
                        {:prev-config (merge {:mcpServers {}} disabled) :config {:mcpServers servers}})
          (events/emit! (ctx (merge {:mcpServers servers} disabled)) :config/changed
                        {:prev-config {:mcpServers servers} :config (merge {:mcpServers servers} disabled)}))
        (is (= [[servers {}]] @calls*)))))
  (testing "disabled, the module stops each server, and clients see them go"
    (let [stopped* (atom [])
          stop (:stop m.mcp/module)]
      (swap! (h/db*) assoc :mcp-clients {"running" {:status :running}})
      (with-redefs [m.mcp/stop-server! (fn [server-name & _] (swap! stopped* conj server-name))]
        (h/reset-messenger!)
        (stop (ctx {:mcpServers {"running" {:command "a"} "off" {:command "b" :disabled true}}}))
        (is (= #{"running" "off"} (set @stopped*)))
        (is (= #{{:name "running"} {:name "off"}} (set (:tool-server-removed (h/messages))))))))
  (testing "ECA shutting down shuts the servers down"
    (let [shutdowns* (atom 0)
          stop (:stop m.mcp/module)]
      (swap! (h/db*) assoc :stopping true)
      (with-redefs [m.mcp/shutdown! (fn [_] (swap! shutdowns* inc))]
        (stop (ctx {}))
        (is (= 1 @shutdowns*))))))

(deftest mcp-prompt-sections-test
  (let [contexts [{:type :mcpResource :uri "mcp://docs/readme" :name "readme" :content "Hello docs"}]]
    (testing "MCP resources and instructions are dynamic sections of :eca/mcp"
      (let [dynamic (f.prompt/build-dynamic-instructions contexts db-with-mcp {})]
        (is (string/includes? dynamic "## MCP Resources"))
        (is (string/includes? dynamic "Use myMCP for the docs."))))
    (testing "disabled, they're left out"
      (is (nil? (f.prompt/build-dynamic-instructions contexts db-with-mcp disabled))))))

(deftest add-server-method-test
  (testing "duplicate name returns :error"
    (h/reset-components!)
    (h/config! {:mcpServers {"existing" {:url "https://x"}}})
    (let [result (m.mcp/add-server-method (h/components)
                                          {:name "existing"
                                           :command "bin"})]
      (is (match? {:error {:code "invalid_request"
                           :message #"already exists"}}
                  result))))

  (testing "missing transport returns :error"
    (h/reset-components!)
    (h/config! {:mcpServers {}})
    (let [result (m.mcp/add-server-method (h/components)
                                          {:name "s"})]
      (is (match? {:error {:code "invalid_request"
                           :message #"must specify :command.*or :url"}}
                  result))))

  (testing "conflicting :command and :url returns :error"
    (h/reset-components!)
    (h/config! {:mcpServers {}})
    (let [result (m.mcp/add-server-method (h/components)
                                          {:name "s"
                                           :command "bin"
                                           :url "https://x"})]
      (is (match? {:error {:code "invalid_request"
                           :message #"must not specify both"}}
                  result)))))

(deftest remove-server-method-test
  (testing "unknown server returns :error"
    (h/reset-components!)
    (h/config! {:mcpServers {}})
    (let [result (m.mcp/remove-server-method (h/components)
                                             {:name "ghost"})]
      (is (match? {:error {:code "invalid_request"
                           :message #"does not exist"}}
                  result)))))

(deftest update-server-method-extended-params-test
  (testing "accepts :env and :headers params (forwarded to update-server!)"
    (h/reset-components!)
    (h/config! {:mcpServers {"s" {:command "bin"}}})
    (let [captured* (atom nil)]
      (with-redefs [m.mcp/update-server!
                    (fn [server-name server-fields _db* _config _metrics _opts]
                      (reset! captured* {:name server-name :fields server-fields}))]
        (m.mcp/update-server-method (h/components)
                                    {:name "s"
                                     :command "bin"
                                     :args ["-x"]
                                     :env {:FOO "bar"}
                                     :headers {:Authorization "Bearer x"}})
        (is (match? {:name "s"
                     :fields {:command "bin"
                              :args ["-x"]
                              :env {:FOO "bar"}
                              :headers {:Authorization "Bearer x"}}}
                    @captured*))))))
