(ns eca.features.external-tools-test
  "External modules' tools and commands: their prefix as tool server, call
   routing, approval and disabledTools by full name, :replaces, the
   functions ECA calls, :tools and :commands as functions, and the tool
   servers clients get."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.commands :as f.commands]
   [eca.features.tools :as f.tools]
   [eca.features.tools.util :as tools.util]
   [eca.modules :as modules]
   [eca.modules.core :as m.core]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private calls* (atom []))

(defn- issue-tool [text]
  {:description "Fetch a Jira issue."
   :parameters {:type "object" :properties {"key" {:type "string"}} :required ["key"]}
   :handler (fn [ctx {:strs [key]}]
              (swap! calls* conj [(:module-id ctx) key])
              (str text " " key))})

(def ^:private jira
  {:id :acme/jira
   :description "Jira."
   :tools {"issue" (issue-tool "Issue")}})

(defmacro ^:private with-modules
  "Runs body with the built-in tool modules this test needs and `externals`,
   each added from its own source."
  [externals & body]
  `(with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with :eca/approval :eca/filesystem))]
     (reset! calls* [])
     (doseq [[i# module#] (map-indexed vector ~externals)]
       (modules/add-external! (str "/tmp/acme-" i# ".clj") module#))
     ~@body))

(defn- tools [config]
  (f.tools/all-tools "chat-1" "code" (h/db) config))

(defn- tool-named [config full-name]
  (some #(when (= full-name (:full-name %)) %) (tools config)))

(defn- call! [config full-name args]
  (f.tools/call-tool! full-name args "chat-1" "call-1" "code" (h/db*) config
                      (h/messenger) (h/metrics) identity identity {}))

(deftest prefix-as-server-test
  (with-modules [jira]
    (testing "an external module's tool is on its prefix's server"
      (is (match? {:name "issue" :full-name "jira__issue" :server {:name "jira"} :origin :native
                   :description "Fetch a Jira issue."}
                  (tool-named {} "jira__issue")))
      (is (nil? (tool-named {} "eca__issue"))))
    (testing "calls go to the module's handler, with the module in ctx"
      (is (match? {:error false :contents [{:type :text :text "Issue ECA-1"}]}
                  (call! {} "jira__issue" {"key" "ECA-1"})))
      (is (= [[:acme/jira "ECA-1"]] @calls*)))
    (testing "the built-in tools keep their names and handlers"
      (is (match? {:server {:name "eca"} :name "read_file"} (tool-named {} "eca__read_file"))))
    (testing ":prefix names the server"
      (with-modules [(assoc jira :prefix "tickets")]
        (is (some? (tool-named {} "tickets__issue")))))))

(deftest string-results-test
  (with-modules [{:id :acme/plain
                  :tools {"hello" {:description "Say hello." :parameters {:type "object"}
                                   :handler (fn [_ _] "Hello!")}
                          "nothing" {:description "Nothing." :parameters {:type "object"}
                                     :handler (fn [_ _] nil)}
                          "data" {:description "Data." :parameters {:type "object"}
                                  :handler (fn [_ _] {:a 1})}}}]
    (testing "an external tool may return a string, as the design allows"
      (is (= {:error false :contents [{:type :text :text "Hello!"}]} (call! {} "plain__hello" {})))
      (is (= {:error false :contents [{:type :text :text ""}]} (call! {} "plain__nothing" {})))
      (is (= {:error false :contents [{:type :text :text "{:a 1}"}]} (call! {} "plain__data" {}))))))

(deftest approval-and-disabled-tools-test
  (with-modules [jira]
    (let [approval (fn [config]
                     (let [all-tools (tools config)]
                       (f.tools/approval all-tools (f.tools/resolve-tool "jira__issue" all-tools) {"key" "X"}
                                         (h/db) config "code")))]
      (testing "external tools ask by default"
        (is (= :ask (approval {:toolCall {:approval {:byDefault "ask"}}}))))
      (testing "rules name them in full, or by their server"
        (is (= :allow (approval {:toolCall {:approval {:byDefault "ask" :allow {"jira__issue" {}}}}})))
        (is (= :allow (approval {:toolCall {:approval {:byDefault "ask" :allow {"jira" {}}}}}))))
      (testing "a bare name is one of ECA's tools only"
        (is (= :ask (approval {:toolCall {:approval {:byDefault "ask" :allow {"issue" {}}}}}))))
      (testing "disabledTools by full name or server"
        (is (nil? (tool-named {:disabledTools ["jira__issue"]} "jira__issue")))
        (is (nil? (tool-named {:disabledTools ["jira"]} "jira__issue")))
        (is (some? (tool-named {:disabledTools ["issue"]} "jira__issue")))))))

(deftest bare-selector-test
  (testing "a server named like an external tool is still matched by its name"
    (let [native-tools [{:name "read_file" :server {:name "eca"}}
                        {:name "github" :server {:name "acme"}}]]
      (is (tools.util/tool-selector-matches? "github" "github" "create_issue" native-tools))
      (is (not (tools.util/tool-selector-matches? "github" "acme" "github" native-tools)))
      (is (tools.util/tool-selector-matches? "read_file" "eca" "read_file" native-tools)))))

(deftest replaces-test
  (with-modules [{:id :acme/remote-fs
                  :tools {"read_file" {:replaces "eca__read_file"
                                       :description "Read a file on the remote host."
                                       :parameters {:type "object" :properties {"path" {:type "string"}}}
                                       :handler (fn [_ctx {:strs [path]}] (str "remote " path))}}}]
    (testing "the tool keeps the full name it replaces"
      (is (match? {:server {:name "eca"} :description "Read a file on the remote host."}
                  (tool-named {} "eca__read_file")))
      (is (nil? (tool-named {} "remote-fs__read_file")))
      (is (= 1 (count (filter #(= "eca__read_file" (:full-name %)) (tools {}))))))
    (testing "calls go to the replacing module"
      (is (match? {:contents [{:text "remote /a"}]} (call! {} "eca__read_file" {"path" "/a"}))))))

(deftest name-clash-test
  (with-modules [jira (assoc jira :id :acme/jira-copy :prefix "jira" :tools {"issue" (issue-tool "Copy")})]
    (testing "the first module keeps a full name"
      (is (= 1 (count (filter #(= "jira__issue" (:full-name %)) (tools {})))))
      (is (match? {:contents [{:text "Issue K"}]} (call! {} "jira__issue" {"key" "K"}))))))

(defn- tool-server-module
  "A module with a tool server `server`, whose lookup tool answers `answer`."
  [id server answer]
  {:id id
   :tool-servers {:list (fn [_ctx]
                          [{:name server :version "1.0" :status "running"
                            :tools [{:name "lookup" :description "Look up."
                                     :parameters {:type "object" :properties {"q" {:type "string"}}}}]}])
                  :call (fn [ctx server-name tool-name arguments opts]
                          (swap! calls* conj [(:module-id ctx) server-name tool-name arguments (:chat-id opts)])
                          answer)
                  :await-refresh (fn [_ctx timeout-ms]
                                   (swap! calls* conj [:await-refresh timeout-ms]))}})

(deftest tool-servers-test
  (with-modules [(tool-server-module :acme/docs "docs" "Found it.")
                 (tool-server-module :acme/docs-copy "docs" "Copy.")]
    (testing "a module's tool servers bring their tools, like MCP servers"
      (is (match? {:name "lookup" :full-name "docs__lookup" :server {:name "docs" :version "1.0"} :origin :mcp}
                  (tool-named {} "docs__lookup"))))
    (testing "a server named like another module's is ignored"
      (is (= 1 (count (filter #(= "docs__lookup" (:full-name %)) (tools {}))))))
    (testing "calls go to the module's :call, which may answer a string"
      (is (= {:error false :contents [{:type :text :text "Found it."}]}
             (call! {} "docs__lookup" {"q" "x"})))
      (is (= [[:acme/docs "docs" "lookup" {"q" "x"} "chat-1"]] @calls*)))
    (testing "clients get each server as a module's tool server"
      (h/reset-messenger!)
      (f.tools/refresh-module-tool-servers! (h/db*) (h/messenger) {})
      (is (match? [{:type :module :name "docs" :module "acme/docs" :status "running"
                    :tools [{:name "lookup" :description "Look up." :disabled false}]}]
                  (rest (:tool-server-update (h/messages))))))
    (testing "the refresh wait asks each module, within the time left"
      (reset! calls* [])
      (f.tools/await-tool-servers-refresh! (h/db) {} 5000)
      (is (match? [[:await-refresh #(<= 0 % 5000)] [:await-refresh #(<= 0 % 5000)]] @calls*)))
    (testing "disabled modules' servers are gone"
      (is (nil? (tool-named {:modules {:acme/docs {:enabled false} :acme/docs-copy {:enabled false}}}
                            "docs__lookup"))))))

(deftest malformed-tool-servers-test
  (with-modules [{:id :acme/sloppy
                  :tool-servers {:list (fn [_ctx]
                                         [{:name "ok" :status 42
                                           :tools [{:name "lookup" :description "Look up."} "not a tool" {:description "no name"}]}
                                          {:status "running"}
                                          "not a server"])
                                 :call (fn [& _] "x")}}]
    (testing "what a module lists without a name, or not as a map, is left out instead of breaking every tool list"
      (is (= ["ok__lookup"] (keep #(when (= "ok" (get-in % [:server :name])) (:full-name %)) (tools {})))))
    (testing "and clients get the rest, with an unknown status as running"
      (h/reset-messenger!)
      (f.tools/refresh-module-tool-servers! (h/db*) (h/messenger) {})
      (is (match? [{:type :module :name "ok" :status "running" :tools [{:name "lookup"}]}]
                  (rest (:tool-server-update (h/messages))))))))

(deftest approval-keys-test
  (with-modules [(assoc-in jira [:tools "issue" :approval-keys-fn]
                           (fn [{:strs [key]}] {:keys #{(str "project " (first (string/split key #"-")))}
                                                :complete? true}))]
    (is (= {:keys #{"project ECA"} :complete? true}
           (f.tools/tool-approval-keys "jira__issue" {"key" "ECA-1"} (h/db) {})))
    (testing "bare names are still ECA's tools"
      (is (nil? (f.tools/tool-approval-keys "issue" {"key" "ECA-1"} (h/db) {}))))))

(deftest tool-functions-test
  (with-modules [{:id :acme/rich
                  :tools (fn [_ctx]
                           {"edit" {:description "Edit."
                                    :parameters {:type "object" :properties {"path" {:type "string"}}}
                                    :handler (fn [_ _] "done")
                                    :details-before-fn (fn [ctx {:strs [path]}]
                                                         {:type :fileChange :path path :module (:module-id ctx)})
                                    :details-after-fn (fn [_ctx _args _details _result] (throw (ex-info "boom" {})))
                                    :normalize-arguments-fn (fn [arguments] (update arguments "path" str "!"))
                                    :cleanup-fn (fn [resource-kwd _resource] (swap! calls* conj resource-kwd))}})}]
    (let [tool (tool-named {} "rich__edit")]
      (testing "a :tools function gives an external module's tools"
        (is (match? {:description "Edit." :server {:name "rich"}} tool)))
      (testing "the functions ECA calls for itself work, with the module's ctx"
        (is (= {:type :fileChange :path "/a" :module :acme/rich}
               (f.tools/tool-call-details-before-invocation tool {"path" "/a"} (h/db) {} "chat-1" false "call-1")))
        (is (= {"path" "/a!"} ((:normalize-arguments-fn tool) {"path" "/a"})))
        (f.tools/tool-call-destroy-resource! "rich__edit" :process :p (h/db) {})
        (is (= [:process] @calls*)))
      (testing "one that fails gives nothing, and counts as the module's failure"
        (is (nil? (f.tools/tool-call-details-after-invocation tool {"path" "/a"} nil {:error false :contents []}
                                                              {:db (h/db) :config {}})))
        (is (= 1 (modules/failures :acme/rich)))))))

(deftest transform-tools-test
  (with-modules [{:id :acme/hide-grep
                  :transform-tools (fn [ctx tools]
                                     (swap! calls* conj (:module-id ctx))
                                     (remove #(= "eca__grep" (:full-name %)) tools))}
                 {:id :acme/broken
                  :transform-tools (fn [_ _] (throw (ex-info "boom" {})))}]
    (let [names (set (map :full-name (tools {})))]
      (testing "an external module changes the tool list"
        (is (not (contains? names "eca__grep")))
        (is (= [:acme/hide-grep] @calls*)))
      (testing "one that fails leaves it as it was"
        (is (contains? names "eca__read_file"))
        (is (= 1 (modules/failures :acme/broken)))))))

(deftest command-functions-test
  (with-modules [{:id :acme/cmds
                  :commands (fn [_ctx]
                              [["hello" {:description "Hello." :handler (fn [_ _] "Hi")}]
                               ["note" {:client-type :custom-prompt :description "A note."
                                        :handler (fn [_ _] {:prompt "Take a note"})}]
                               "not a pair"])}]
    (testing "a :commands function gives an external module's commands, also for a source like custom prompts"
      (is (match? (m/embeds [{:name "hello" :type :module :description "Hello."}
                             {:name "note" :type :custom-prompt :description "A note."}])
                  (f.commands/all-commands (h/db) {})))
      (is (= {:type :send-prompt :prompt "Take a note"}
             (f.commands/handle-command! "note" [] {:chat-id "chat-1" :config {} :db* (h/db*)}))))
    (testing "/modules lists its own commands"
      (is (string/includes? (#'m.core/modules-text {} (h/db)) "commands `/hello`")))))

(deftest module-tool-servers-test
  (with-modules [jira]
    (h/reset-messenger!)
    (f.tools/refresh-module-tool-servers! (h/db*) (h/messenger) {})
    (let [updates (:tool-server-update (h/messages))]
      (testing "ECA's server lists only ECA's tools"
        (is (match? {:type :native :name "ECA" :tools (m/embeds [{:name "read_file"}])}
                    (first updates)))
        (is (not-any? #(= "issue" (:name %)) (:tools (first updates)))))
      (testing "each external module with tools gets a server of type module"
        (is (match? [{:type :module :name "jira" :module "acme/jira" :status "running"
                      :tools [{:name "issue" :description "Fetch a Jira issue." :disabled false}]}]
                    (rest updates)))))
    (testing "a module's server is removed when its tools go"
      (h/reset-messenger!)
      (modules/remove-external! "/tmp/acme-0.clj")
      (f.tools/refresh-module-tool-servers! (h/db*) (h/messenger) {})
      (is (= 1 (count (:tool-server-update (h/messages)))))
      (is (= [{:name "jira"}] (:tool-server-removed (h/messages)))))))

(deftest modules-listing-test
  (with-modules [jira {:id :acme/remote-fs
                       :tools {"read_file" {:replaces "eca__read_file" :description "Remote."
                                            :parameters {:type "object"} :handler (fn [_ _] "x")}}}]
    (let [text (#'m.core/modules-text {} (h/db))]
      (is (string/includes? text "tools `jira__issue`"))
      (is (string/includes? text "tools `eca__read_file`")))))

(deftest reserved-prefix-test
  (is (match? {:errors [#":prefix eca is reserved"]} (modules/problems {:id :acme/x :prefix "eca"} {})))
  (is (match? {:errors [#":prefix defaults to the name in :id"]} (modules/problems {:id :acme/eca} {})))
  (is (= [] (:errors (modules/problems {:id :acme/eca :prefix "acme"} {}))))
  (is (= [] (:errors (modules/problems {:id :eca/x :prefix "eca"} {:built-in? true})))))

(defn- refreshes
  "The tool servers clients get, from each of the two refreshes: when modules
   change, and when the config does."
  []
  (mapv (fn [refresh!]
          (h/reset-messenger!)
          (refresh!)
          (:tool-server-update (h/messages)))
        [#(f.tools/refresh-module-tool-servers! (h/db*) (h/messenger) {})
         #(f.tools/refresh-tool-servers! identity (h/db*) (h/messenger) {})]))

(deftest tool-traits-test
  (with-modules [{:id :acme/traits
                  :tools {"plain" {:description "Plain." :parameters {:type "object"} :handler (fn [_ _] "ok")}
                          "hidden" {:description "Hidden." :parameters {:type "object"} :handler (fn [_ _] "ok")
                                    :listed? false}
                          "solo" {:description "Not for subagents." :parameters {:type "object"}
                                  :handler (fn [_ _] "ok") :subagents? false}}}]
    (testing ":subagents? false keeps a tool from subagents"
      (swap! (h/db*) assoc-in [:chats "sub-1"] {:id "sub-1" :subagent true})
      (let [names #(set (map :full-name (f.tools/all-tools % "code" (h/db) {})))]
        (is (contains? (names "chat-1") "traits__solo"))
        (is (not (contains? (names "sub-1") "traits__solo")))
        (is (contains? (names "sub-1") "traits__plain"))))
    (testing ":listed? false leaves a tool out of the lists clients get; the model still gets it"
      (is (some? (tool-named {} "traits__hidden")))
      (doseq [updates (refreshes)]
        (is (match? [{:type :module :name "traits" :tools (m/in-any-order [{:name "plain"} {:name "solo"}])}]
                    (filter #(= :module (:type %)) updates)))))
    (testing "the traits' values are checked"
      (is (match? {:errors [#":subagents\? must be a boolean" #":progress-text must be a string"]}
                  (modules/problems {:id :acme/bad
                                     :tools {"t" {:description "T." :parameters {:type "object"}
                                                  :handler (fn [_ _] "ok")
                                                  :subagents? "no" :progress-text 1}}}
                                    {}))))))

(deftest compact-chat-not-listed-test
  (testing "ECA's compact_chat, the model's during a compaction, is never in the tool list clients get"
    (with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with :eca/approval :eca/compact-as-summary))]
      (is (some? (tool-named {} "eca__compact_chat")))
      (doseq [updates (refreshes)]
        (is (match? {:type :native :name "ECA"} (first updates)))
        (is (not-any? #(= "compact_chat" (:name %)) (:tools (first updates))))))))
