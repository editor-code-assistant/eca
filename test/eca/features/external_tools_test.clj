(ns eca.features.external-tools-test
  "External modules' tools and commands: their prefix as tool server, call
   routing, approval and disabledTools by full name, :replaces, the tools
   and commands modules register while they run, and the tool servers
   clients get."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.api :as eca]
   [eca.features.commands :as f.commands]
   [eca.features.modules :as f.modules]
   [eca.features.tools :as f.tools]
   [eca.features.tools.util :as tools.util]
   [eca.modules :as modules]
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
              (eca/text (str text " " key)))})

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
                                       :handler (fn [_ctx {:strs [path]}] (eca/text (str "remote " path)))}}}]
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

(deftest approval-keys-test
  (with-modules [(assoc-in jira [:tools "issue" :approval-keys-fn]
                           (fn [{:strs [key]}] {:keys #{(str "project " (first (string/split key #"-")))}
                                                :complete? true}))]
    (is (= {:keys #{"project ECA"} :complete? true}
           (f.tools/tool-approval-keys "jira__issue" {"key" "ECA-1"} (h/db) {})))
    (testing "bare names are still ECA's tools"
      (is (nil? (f.tools/tool-approval-keys "issue" {"key" "ECA-1"} (h/db) {}))))))

(deftest registrations-test
  (with-modules [{:id :acme/dynamic}]
    (let [ctx {:module-id :acme/dynamic}]
      (testing "a module adds a tool while it runs"
        (eca/register-tool! ctx "ping" {:description "Ping."
                                        :parameters {:type "object"}
                                        :handler (fn [_ _] (eca/text "pong"))})
        (is (some? (tool-named {} "dynamic__ping")))
        (is (match? {:contents [{:text "pong"}]} (call! {} "dynamic__ping" {}))))
      (testing "and removes it"
        (eca/unregister-tool! ctx "ping")
        (is (nil? (tool-named {} "dynamic__ping"))))
      (testing "invalid tools and names are refused"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":handler must be a function"
                              (eca/register-tool! ctx "bad" {:description "x" :parameters {}})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the name must be a string"
                              (eca/register-tool! ctx "a b" {:description "x" :parameters {} :handler (fn [_ _])})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"belongs to no module"
                              (eca/register-tool! {} "x" {:description "x" :parameters {} :handler (fn [_ _])}))))
      (testing "a module adds a command"
        (eca/register-command! ctx "greet" {:description "Greet." :handler (fn [_ {:keys [raw]}] (str "Hi " raw))})
        (is (match? (m/embeds [{:name "greet" :type :module :description "Greet."}])
                    (f.commands/all-commands (h/db) {})))
        (is (= "Hi Ana"
               (get-in (f.commands/handle-command! "greet" ["Ana"] {:chat-id "chat-1" :config {} :db* (h/db*)})
                       [:chats "chat-1" :messages 0 :content 0 :text])))
        (eca/unregister-command! ctx "greet")
        (is (not-any? #(= "greet" (:name %)) (f.commands/all-commands (h/db) {}))))
      (testing "registrations of a module that isn't active don't count"
        (eca/register-tool! ctx "ping" {:description "Ping." :parameters {:type "object"} :handler (fn [_ _] "pong")})
        (is (nil? (tool-named {:modules {:acme/dynamic {:enabled false}}} "dynamic__ping")))))))

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
    (let [text (f.modules/modules-text {} (h/db))]
      (is (string/includes? text "tools `jira__issue`"))
      (is (string/includes? text "tools `eca__read_file`")))))

(deftest reserved-prefix-test
  (is (match? {:errors [#":prefix eca is reserved"]} (modules/problems {:id :acme/x :prefix "eca"} {})))
  (is (match? {:errors [#":prefix defaults to the name in :id"]} (modules/problems {:id :acme/eca} {})))
  (is (= [] (:errors (modules/problems {:id :acme/eca :prefix "acme"} {}))))
  (is (= [] (:errors (modules/problems {:id :eca/x :prefix "eca"} {:built-in? true})))))
