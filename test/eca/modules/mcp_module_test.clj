(ns eca.modules.mcp-module-test
  "MCP belongs to :eca/mcp: disabled, no server runs and their tools, prompts
   and prompt sections are hidden."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.commands :as f.commands]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools :as f.tools]
   [eca.modules.mcp :as m.mcp]
   [eca.test-helper :as h]))

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
    (with-redefs [m.mcp/all-tools (constantly [{:name "lookup" :server {:name "myMCP"} :description "Look up."
                                                :parameters {:type "object"}}])
                  m.mcp/all-prompts (constantly [{:name "summarize" :server "myMCP" :description "Summarize."}])]
      (is (some #(= "myMCP__lookup" (:full-name %)) (f.tools/all-tools "c1" "code" db-with-mcp {})))
      (is (some #(= "myMCP:summarize" (:name %)) (f.commands/all-commands db-with-mcp {})))
      (testing "and hidden while it's disabled"
        (is (not-any? #(= "myMCP__lookup" (:full-name %)) (f.tools/all-tools "c1" "code" db-with-mcp disabled)))
        (is (not-any? #(= "myMCP:summarize" (:name %)) (f.commands/all-commands db-with-mcp disabled)))))))

(deftest mcp-servers-test
  (testing "disabling the module stops the servers through the config reconcile, enabling starts them"
    (let [calls* (atom [])
          servers {"myMCP" {:command "my-mcp"}}]
      (with-redefs [m.mcp/reconcile-servers! (fn [prev-config new-config & _]
                                               (swap! calls* conj [(:mcpServers prev-config) (:mcpServers new-config)]))]
        (f.tools/reconcile-servers! {:mcpServers servers} (merge {:mcpServers servers} disabled)
                                    (h/db*) (h/messenger) (h/metrics))
        (f.tools/reconcile-servers! (merge {:mcpServers servers} disabled) {:mcpServers servers}
                                    (h/db*) (h/messenger) (h/metrics))
        (is (= [[servers {}] [{} servers]] @calls*)))))
  (testing "disabled, init starts no server"
    (let [started* (atom 0)]
      (with-redefs [m.mcp/initialize-servers-async! (fn [& _] (swap! started* inc))]
        (f.tools/init-servers! (h/db*) (h/messenger) disabled (h/metrics))
        (is (= 0 @started*))
        (f.tools/init-servers! (h/db*) (h/messenger) {} (h/metrics))
        (is (= 1 @started*))))))

(deftest mcp-prompt-sections-test
  (let [contexts [{:type :mcpResource :uri "mcp://docs/readme" :name "readme" :content "Hello docs"}]]
    (testing "MCP resources and instructions are dynamic sections of :eca/mcp"
      (let [dynamic (f.prompt/build-dynamic-instructions contexts db-with-mcp {})]
        (is (string/includes? dynamic "## MCP Resources"))
        (is (string/includes? dynamic "Use myMCP for the docs."))))
    (testing "disabled, they're left out"
      (is (nil? (f.prompt/build-dynamic-instructions contexts db-with-mcp disabled))))))
