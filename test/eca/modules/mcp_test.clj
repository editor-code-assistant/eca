(ns eca.modules.mcp-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.db.global-cache :as db.global-cache]
   [eca.modules.mcp :as m.mcp]
   [eca.network :as network]
   [hato.client :as http]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]
   [plumcp.core.api.mcp-client :as pmc]
   [plumcp.core.client.client-support :as pcs]
   [plumcp.core.client.http-client-transport :as phct]
   [plumcp.core.protocol :as pp]
   [plumcp.core.support.http-client :as phc]
   [ring.util.codec :as ring.util]))

(deftest tool-servers-test
  (let [tool-servers #(#'m.mcp/tool-servers {:db %1 :config (or %2 {})})]
    (testing "no servers"
      (is (= [] (tool-servers {} nil)))
      (is (= [] (tool-servers {:some-other-key "value"} nil)))
      (is (= [] (tool-servers {:mcp-clients {}} nil))))

    (testing "a server without tools is listed without tools"
      (is (= [{:type :mcp :name "server1" :version nil :status nil :tools nil}]
             (tool-servers {:mcp-clients {"server1" {}}} nil))))

    (testing "servers with their version, status and tools, in order"
      (let [tools1 [{:name "tool1" :description "desc1"}]
            tools2 [{:name "tool2" :description "desc2"}
                    {:name "tool3" :description "desc3"}]]
        (is (= [{:type :mcp :name "server1" :version "1.0.0" :status :running :tools tools1}
                {:type :mcp :name "server2" :version nil :status nil :tools tools2}]
               (tool-servers {:mcp-clients (array-map "server1" {:version "1.0.0" :status :running :tools tools1}
                                                      "server2" {:tools tools2})}
                             nil)))))

    (testing "the disabled servers that don't run come last, without tools"
      (is (= [{:type :mcp :name "on" :version nil :status :running :tools []}
              {:type :mcp :name "off" :status :disabled :tools []}]
             (tool-servers {:mcp-clients {"on" {:status :running :tools []}}}
                           {:mcpServers {"on" {:command "on"}
                                         "off" {:command "off" :disabled true}}}))))))

(deftest call-tool-routes-to-server-test
  (testing "uses the requested MCP server when tool names collide"
    (let [db {:mcp-clients (array-map
                            "server-a" {:client :client-a
                                        :tools [{:name "shared_tool"}]}
                            "server-b" {:client :client-b
                                        :tools [{:name "shared_tool"}]})}
          calls* (atom [])]
      (with-redefs [m.mcp/do-call-tool (fn [client name arguments needs-reinit?* _request-opts]
                                           (swap! calls* conj {:client client
                                                              :name name
                                                              :arguments arguments
                                                              :needs-reinit?* needs-reinit?*})
                                           {:error false
                                            :contents [{:type :text :text (str client)}]})]
        (is (= {:error false
                :contents [{:type :text :text ":client-b"}]}
               (m.mcp/call-tool! "server-b" "shared_tool" {"query" "value"} {:db db})))
        (is (= [{:client :client-b
                 :name "shared_tool"
                 :arguments {"query" "value"}
                 :needs-reinit?* nil}]
               @calls*))))))

(deftest all-prompts-test
  (testing "empty db"
    (is (= []
           (m.mcp/all-prompts {}))))

  (testing "db with no mcp-clients"
    (is (= []
           (m.mcp/all-prompts {:some-other-key "value"}))))

  (testing "db with empty mcp-clients"
    (is (= []
           (m.mcp/all-prompts {:mcp-clients {}}))))

  (testing "db with mcp-clients but no prompts"
    (is (= []
           (m.mcp/all-prompts {:mcp-clients {"server1" {}}}))))

  (testing "db with single server with prompts"
    (let [prompts [{:name "prompt1" :description "desc1"}
                   {:name "prompt2" :description "desc2"}]
          expected (mapv #(assoc % :server "server1") prompts)]
      (is (= expected
             (m.mcp/all-prompts {:mcp-clients {"server1" {:prompts prompts}}})))))

  (testing "db with multiple servers with prompts"
    (let [prompts1 [{:name "prompt1" :description "desc1"}]
          prompts2 [{:name "prompt2" :description "desc2"}
                    {:name "prompt3" :description "desc3"}]
          expected (concat
                    (mapv #(assoc % :server "server1") prompts1)
                    (mapv #(assoc % :server "server2") prompts2))]
      (is (= expected
             (m.mcp/all-prompts {:mcp-clients {"server1" {:prompts prompts1}
                                               "server2" {:prompts prompts2}}}))))))

(deftest all-resources-test
  (testing "empty db"
    (is (= []
           (m.mcp/all-resources {}))))

  (testing "db with no mcp-clients"
    (is (= []
           (m.mcp/all-resources {:some-other-key "value"}))))

  (testing "db with empty mcp-clients"
    (is (= []
           (m.mcp/all-resources {:mcp-clients {}}))))

  (testing "db with mcp-clients but no resources"
    (is (= []
           (m.mcp/all-resources {:mcp-clients {"server1" {}}}))))

  (testing "db with single server with resources"
    (let [resources [{:uri "file://test1" :name "resource1"}
                     {:uri "file://test2" :name "resource2"}]
          expected (mapv #(assoc % :server "server1") resources)]
      (is (= expected
             (m.mcp/all-resources {:mcp-clients {"server1" {:resources resources}}})))))

  (testing "db with multiple servers with resources"
    (let [resources1 [{:uri "file://test1" :name "resource1"}]
          resources2 [{:uri "file://test2" :name "resource2"}
                      {:uri "file://test3" :name "resource3"}]
          expected (concat
                    (mapv #(assoc % :server "server1") resources1)
                    (mapv #(assoc % :server "server2") resources2))]
      (is (= expected
             (m.mcp/all-resources {:mcp-clients {"server1" {:resources resources1}
                                                 "server2" {:resources resources2}}}))))))

(deftest shutdown!-test
  (testing "shutdown with no clients"
    (let [db* (atom {})]
      (m.mcp/shutdown! db*)
      (is (= {:mcp-clients {}} @db*))))

  (testing "shutdown with empty mcp-clients"
    (let [db* (atom {:mcp-clients {}})]
      (m.mcp/shutdown! db*)
      (is (= {:mcp-clients {}} @db*))))

  (testing "shutdown preserves other db data"
    (let [db* (atom {:mcp-clients {}
                     :workspace-folders []
                     :other-key "value"})]
      (m.mcp/shutdown! db*)
      (is (= {:mcp-clients {}
              :workspace-folders []
              :other-key "value"} @db*)))))

(deftest stop-a-starting-server-test
  (let [db* (atom {:workspace-folders [{:uri "file:///tmp" :name "test"}]})
        config {:mcpServers {"slow" {:command "slow-server"}} :mcpTimeoutSeconds 5}
        statuses* (atom [])
        notifiers {:on-server-updated #(swap! statuses* conj (:status %))}
        calls* (atom 0)
        first-init* (promise)
        second-init* (promise)]
    (with-redefs-fn {#'m.mcp/->transport (fn [& _] {:transport :mock-transport :needs-reinit?* (atom false)})
                     ;; the handshake waits until the init is stopped; the first init, once stopped,
                     ;; ends only after a second one started
                     #'m.mcp/->client (fn [& _]
                                        (if (= 1 (swap! calls* inc))
                                          (do (deliver first-init* (Thread/currentThread))
                                              (try @(promise)
                                                   (catch InterruptedException e
                                                     (deref second-init* 5000 nil)
                                                     (throw e))))
                                          (do (deliver second-init* (Thread/currentThread))
                                              @(promise))))
                     #'pp/stop-client-transport! (fn [_t _g] nil)}
      (fn []
        (m.mcp/initialize-servers-async! notifiers db* config nil)
        (let [^Thread first-init (deref first-init* 5000 nil)]
          (m.mcp/stop-server! "slow" db* config notifiers)
          (m.mcp/initialize-servers-async! notifiers db* config nil)
          (let [^Thread second-init (deref second-init* 5000 nil)]
            (.join first-init 5000)
            (testing "a server stopped while it starts is stopped, not failed, so it can start again"
              (is (= [:starting :stopped :starting] @statuses*))
              (is (nil? (get-in @db* [:mcp-clients "slow"]))))
            (testing "the init that took its place is still the server's, so it can be stopped"
              (is (identical? second-init (get @@#'m.mcp/init-threads* "slow"))))
            (m.mcp/stop-server! "slow" db* config notifiers)
            (.join second-init 5000)
            (is (= [:starting :stopped :starting :stopped] @statuses*))
            (is (= {} @@#'m.mcp/init-threads*))))))))

(def ^:private ->transport #'m.mcp/->transport)

(deftest transport-middleware-reads-latest-token-test
  (testing "request middleware uses current atom value, not a stale snapshot"
    (let [db* (atom {:mcp-auth {"test-server" {:access-token "token-v1"}}})
          captured-rm* (atom nil)]
      (binding [network/*ssl-context* nil]
        (with-redefs [phc/make-http-client (fn [_url {:keys [request-middleware]}]
                                             (reset! captured-rm* request-middleware)
                                             :mock-http-client)
                      phct/make-streamable-http-transport (fn [_hc] :mock-transport)]
          (->transport "test-server" {:url "https://example.com/mcp"} [] db*)))
      (let [rm @captured-rm*]
        (is (some? rm) "middleware should have been captured")

        (testing "reads initial token"
          (let [result (rm {:headers {}})]
            (is (= "Bearer token-v1" (get-in result [:headers "Authorization"])))))

        (testing "reads updated token after atom mutation"
          (swap! db* assoc-in [:mcp-auth "test-server" :access-token] "token-v2")
          (let [result (rm {:headers {}})]
            (is (= "Bearer token-v2" (get-in result [:headers "Authorization"])))))

        (testing "no Authorization header when token is removed"
          (swap! db* update :mcp-auth dissoc "test-server")
          (let [result (rm {:headers {}})]
            (is (nil? (get-in result [:headers "Authorization"])))))))))

(def ^:private walk-server-leaves #'m.mcp/walk-server-leaves)
(def ^:private rj-assoc-server-entry #'m.mcp/rj-assoc-server-entry)

(deftest walk-server-leaves-test
  (testing "flattens string/keyword keys to string paths"
    (is (= #{[["command"] "my-bin"]
             [["args"] ["-a" "-b"]]}
           (set (walk-server-leaves {:command "my-bin"
                                     :args ["-a" "-b"]})))))

  (testing "recurses into nested maps (env, headers)"
    (is (= #{[["env" "FOO"] "bar"]
             [["env" "BAZ"] "qux"]
             [["headers" "Authorization"] "Bearer x"]}
           (set (walk-server-leaves {:env {:FOO "bar" :BAZ "qux"}
                                     :headers {:Authorization "Bearer x"}})))))

  (testing "empty map produces no leaves"
    (is (= [] (walk-server-leaves {}))))

  (testing "empty sub-map is treated as a leaf (assoc'd as empty JSON object)"
    (is (= #{[["env"] {}]}
           (set (walk-server-leaves {:env {}}))))))

(deftest rj-assoc-server-entry-preserves-comments-test
  (testing "new entry added to a file with comments keeps the comments"
    (let [raw (str "// user config\n"
                   "{\n"
                   "  \"mcpServers\": {\n"
                   "    // existing server\n"
                   "    \"existing\": { \"command\": \"existing-bin\" }\n"
                   "  }\n"
                   "}\n")
          temp (fs/create-temp-dir)
          file (io/file (str temp) "config.json")]
      (try
        (spit file raw)
        (#'m.mcp/update-config-file! file
                                     #(rj-assoc-server-entry % "added"
                                                             {:command "new-bin"
                                                              :args ["-x"]
                                                              :env {:FOO "bar"}}))
        (let [after (slurp file)]
          (is (string/includes? after "// user config")
              "top-of-file comment preserved")
          (is (string/includes? after "// existing server")
              "in-place comment preserved")
          (is (string/includes? after "\"added\"")
              "new entry written")
          ;; Parse the written file (stripping comments via cheshire+allow-comments)
          (let [parsed (#'m.mcp/parse-json-with-comments after)]
            (is (= {"command" "existing-bin"}
                   (get-in parsed ["mcpServers" "existing"])))
            (is (= {"command" "new-bin"
                    "args" ["-x"]
                    "env" {"FOO" "bar"}}
                   (get-in parsed ["mcpServers" "added"])))))
        (finally
          (fs/delete-tree temp)))))

  (testing "replacing an existing entry strips stale fields (url -> command)"
    (let [raw (str "{\n"
                   "  \"mcpServers\": {\n"
                   "    \"s\": { \"url\": \"https://old.example.com\", \"headers\": { \"X\": \"1\" } }\n"
                   "  }\n"
                   "}\n")
          temp (fs/create-temp-dir)
          file (io/file (str temp) "config.json")]
      (try
        (spit file raw)
        (#'m.mcp/update-config-file! file
                                     #(rj-assoc-server-entry % "s"
                                                             {:command "new-bin"
                                                              :args ["-a"]}))
        (let [parsed (#'m.mcp/parse-json-with-comments (slurp file))]
          (is (= {"command" "new-bin"
                  "args" ["-a"]}
                 (get-in parsed ["mcpServers" "s"]))
              "old url/headers removed after switch to stdio"))
        (finally
          (fs/delete-tree temp))))))

(deftest add-server!-validation-test
  (testing "rejects duplicate name"
    (let [db* (atom {:workspace-folders []})
          config {:mcpServers {"existing" {:url "https://x"}}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"MCP server 'existing' already exists"
           (m.mcp/add-server! "existing" {:command "bin"} {} db* config nil
                              {:on-server-updated (constantly nil)})))))

  (testing "rejects both :command and :url"
    (let [db* (atom {:workspace-folders []})
          config {:mcpServers {}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"must not specify both"
           (m.mcp/add-server! "s" {:command "bin" :url "https://x"} {} db* config nil
                              {:on-server-updated (constantly nil)})))))

  (testing "rejects neither :command nor :url"
    (let [db* (atom {:workspace-folders []})
          config {:mcpServers {}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"must specify :command.*or :url"
           (m.mcp/add-server! "s" {:disabled true} {} db* config nil
                              {:on-server-updated (constantly nil)})))))

  (testing "rejects blank name"
    (let [db* (atom {:workspace-folders []})
          config {:mcpServers {}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"name must be non-blank"
           (m.mcp/add-server! "" {:command "bin"} {} db* config nil
                              {:on-server-updated (constantly nil)})))))

  (testing "rejects :workspace scope without :workspace-uri"
    (let [db* (atom {:workspace-folders []})
          config {:mcpServers {}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #":workspace scope requires :workspace-uri"
           (m.mcp/add-server! "s" {:command "bin"} {:scope :workspace} db* config nil
                              {:on-server-updated (constantly nil)}))))))

(deftest add-server!-disabled-test
  (testing "disabled=true: writes config, emits :disabled, does not start"
    (let [temp (fs/create-temp-dir)
          config-file (io/file (str temp) "config.json")
          db* (atom {:workspace-folders []})
          updates* (atom [])]
      (try
        (with-redefs [config/global-config-file (constantly config-file)
                      config/all (fn [_] {:mcpServers {"s" {:command "bin" :disabled true}}})]
          (let [result (m.mcp/add-server! "s"
                                          {:command "bin" :disabled true}
                                          {:scope :global}
                                          db*
                                          {:mcpServers {}}
                                          nil
                                          {:on-server-updated #(swap! updates* conj %)})]
            (is (= :disabled (:status result)))
            (is (= "s" (:name result)))
            (is (= [:disabled] (mapv :status @updates*))
                "only :disabled emitted, no start thread")
            (let [written (#'m.mcp/parse-json-with-comments (slurp config-file))]
              (is (= {"command" "bin" "disabled" true}
                     (get-in written ["mcpServers" "s"]))))))
        (finally
          (fs/delete-tree temp))))))

(deftest remove-server!-test
  (testing "dissocs entry from config file, clears mcp-auth, calls on-server-removed"
    (let [temp (fs/create-temp-dir)
          config-file (io/file (str temp) "config.json")
          _ (spit config-file (json/generate-string {"mcpServers" {"s" {"url" "https://x"}
                                                                   "other" {"command" "o"}}}))
          db* (atom {:workspace-folders []
                     :mcp-auth {"s" {:access-token "tok"}}
                     :mcp-clients {}})
          removed* (atom nil)]
      (try
        (with-redefs [config/global-config-file (constantly config-file)]
          (m.mcp/remove-server! "s" db*
                                {:mcpServers {"s" {:url "https://x"}
                                              "other" {:command "o"}}}
                                {:on-server-updated (constantly nil)
                                 :on-server-removed #(reset! removed* %)}))
        (is (= {:name "s"} @removed*))
        (is (nil? (get-in @db* [:mcp-auth "s"])))
        (let [written (#'m.mcp/parse-json-with-comments (slurp config-file))]
          (is (nil? (get-in written ["mcpServers" "s"])))
          (is (some? (get-in written ["mcpServers" "other"]))
              "sibling entry preserved"))
        (finally
          (fs/delete-tree temp)))))

  (testing "throws when server does not exist"
    (let [db* (atom {:workspace-folders [] :mcp-clients {}})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"does not exist"
           (m.mcp/remove-server! "ghost" db*
                                 {:mcpServers {}}
                                 {:on-server-updated (constantly nil)
                                  :on-server-removed (constantly nil)}))))))

(deftest initialize-server-retries-on-transport-error-test
  (testing "retries with token refresh when needs-reinit? is set during init (e.g. 403)"
    (let [db* (atom {:mcp-auth {"test-server" {:access-token "old-token"
                                                :url "https://example.com/mcp"}}
                      :workspace-folders [{:uri "file:///tmp" :name "test"}]})
          attempt-count* (atom 0)
          refresh-count* (atom 0)
          status-updates* (atom [])
          mock-client (Object.)
          make-transport (fn [_name _config _workspaces _db*]
                           (let [attempt (swap! attempt-count* inc)
                                 needs-reinit?* (atom (<= attempt 2))]
                             {:transport :mock-transport
                              :needs-reinit?* needs-reinit?*}))]
      (with-redefs-fn {#'m.mcp/->transport make-transport
                       #'m.mcp/->client (fn [_name _transport _timeout _workspaces _opts]
                                          mock-client)
                       #'pp/stop-client-transport! (fn [_t _g] nil)
                       #'pmc/get-initialize-result (fn [_client]
                                                     {:serverInfo {:version "1.0"}
                                                      :capabilities {:tools true}})
                       #'pmc/list-tools (fn [_client _opts] [])
                       #'pmc/list-prompts (fn [_client _opts] [])
                       #'pmc/list-resources (fn [_client _opts] [])
                       #'m.mcp/try-refresh-token! (fn [_name _db* _url _metrics _config]
                                                    (swap! refresh-count* inc)
                                                    true)}
        (fn []
          (#'m.mcp/initialize-server! "test-server" db*
           {:mcpServers {"test-server" {:url "https://example.com/mcp"}}
            :mcpTimeoutSeconds 5}
           nil
           (fn [server] (swap! status-updates* conj (:status server))))))

      (is (= 3 @attempt-count*) "should have attempted 3 times (2 failed + 1 success)")
      (is (= 2 @refresh-count*) "should have tried token refresh on each retry")
      (is (= :running (get-in @db* [:mcp-clients "test-server" :status]))
          "server should be running after successful retry")))

  (testing "fails after exhausting all retry attempts"
    (let [db* (atom {:mcp-auth {"fail-server" {:access-token "bad-token"
                                                :url "https://example.com/mcp"}}
                      :workspace-folders [{:uri "file:///tmp" :name "test"}]})
          attempt-count* (atom 0)
          refresh-count* (atom 0)]
      (with-redefs-fn {#'m.mcp/->transport (fn [_name _config _workspaces _db*]
                                             (swap! attempt-count* inc)
                                             {:transport :mock-transport
                                              :needs-reinit?* (atom true)})
                       #'m.mcp/->client (fn [_name _transport _timeout _workspaces _opts]
                                          (Object.))
                       #'pp/stop-client-transport! (fn [_t _g] nil)
                       #'pmc/get-initialize-result (fn [_client]
                                                     {:serverInfo {:version "1.0"}
                                                      :capabilities {:tools true}})
                       #'pmc/list-tools (fn [_client _opts] [])
                       #'pmc/list-prompts (fn [_client _opts] [])
                       #'pmc/list-resources (fn [_client _opts] [])
                       #'m.mcp/try-refresh-token! (fn [_name _db* _url _metrics _config]
                                                    (swap! refresh-count* inc)
                                                    false)}
        (fn []
          (#'m.mcp/initialize-server! "fail-server" db*
           {:mcpServers {"fail-server" {:url "https://example.com/mcp"}}
            :mcpTimeoutSeconds 5}
           nil
           (constantly nil))))

      (is (= 3 @attempt-count*) "should have attempted max-init-retries (3) times")
      (is (= 2 @refresh-count*) "should have tried token refresh on attempts 1 and 2")
      (is (= :failed (get-in @db* [:mcp-clients "fail-server" :status]))
          "server should be in failed state after exhausting retries"))))

(deftest tool->internal-description-fallback-test
  (testing "uses :description when present"
    (is (= "Generate an image"
           (:description (#'m.mcp/tool->internal
                          {:name "create-image"
                           :description "Generate an image"
                           :inputSchema {}})))))

  (testing "falls back to :title when :description is missing"
    (is (= "Create Image"
           (:description (#'m.mcp/tool->internal
                          {:name "create-image"
                           :title "Create Image"
                           :inputSchema {}})))))

  (testing "falls back to :title when :description is empty string"
    (is (= "Create Image"
           (:description (#'m.mcp/tool->internal
                          {:name "create-image"
                           :description ""
                           :title "Create Image"
                           :inputSchema {}})))))

  (testing "synthesizes from :name when both :description and :title are missing"
    (is (= "MCP tool: create-image"
           (:description (#'m.mcp/tool->internal
                          {:name "create-image"
                           :inputSchema {}})))))

  (testing "synthesizes from :name when both :description and :title are empty"
    (is (= "MCP tool: edit-image"
           (:description (#'m.mcp/tool->internal
                          {:name "edit-image"
                           :description ""
                           :title ""
                           :inputSchema {}}))))))

(def ^:private auth-error-message? #'m.mcp/auth-error-message?)

(deftest auth-error-message?-test
  (testing "auth/session failures are flagged (servers that omit an HTTP status)"
    (doseq [m ["Unauthorized"
               "Authentication required"
               "Invalid authorization header"
               "invalid token"
               "access token is no longer valid"
               "token expired"
               "Session expired, please re-authenticate"
               "Upstream returned HTTP 401"
               "got 403 forbidden"]]
      (is (true? (auth-error-message? m)) (str "should flag: " m))))
  (testing "ordinary tool errors are not flagged"
    (doseq [m ["spreadsheet not found"
               "invalid range A1:Z9"
               "rate limit exceeded"
               "internal server hiccup"
               ""
               nil]]
      (is (not (auth-error-message? m)) (str "should not flag: " m)))))

(deftest try-refresh-token!-reuses-persisted-client-test
  (testing "refresh reuses persisted client-id/secret (skips DCR) and preserves them"
    (let [db* (atom {:mcp-auth {"s" {:type :auth/oauth
                                     :url "https://example.com/mcp"
                                     :access-token "old-access"
                                     :refresh-token "refresh-1"
                                     :expires-at 1
                                     :client-id "client-abc"
                                     :client-secret "secret-xyz"}}})
          oauth-info-args* (atom nil)]
      (with-redefs [m.mcp/discover-oauth-info (fn [url cid csecret _port _cname]
                                                (reset! oauth-info-args* {:url url :client-id cid :client-secret csecret})
                                                {:token-endpoint "https://example.com/token"
                                                 :client-id cid
                                                 :client-secret csecret
                                                 :resource url})
                    m.mcp/refresh-token! (fn [_te _cid _rt & _opts]
                                           {:access-token "new-access"
                                            :refresh-token "refresh-2"
                                            :expires-at 9999})
                    db.global-cache/update-global-cache! (fn [_db _metrics] nil)]
        (is (true? (#'m.mcp/try-refresh-token! "s" db* "https://example.com/mcp" nil {})))
        (is (= {:url "https://example.com/mcp"
                :client-id "client-abc"
                :client-secret "secret-xyz"}
               @oauth-info-args*)
            "oauth-info called with the persisted client identity, not a fresh DCR")
        (is (= {:type :auth/oauth
                :url "https://example.com/mcp"
                :access-token "new-access"
                :refresh-token "refresh-2"
                :expires-at 9999
                :client-id "client-abc"
                :client-secret "secret-xyz"}
               (get-in @db* [:mcp-auth "s"]))
            "new tokens merged in, client identity preserved"))))

  (testing "configured clientId/clientSecret take precedence over persisted"
    (let [db* (atom {:mcp-auth {"s" {:type :auth/oauth
                                     :url "https://example.com/mcp"
                                     :refresh-token "refresh-1"
                                     :client-id "persisted-id"
                                     :client-secret "persisted-secret"}}})
          oauth-info-args* (atom nil)]
      (with-redefs [m.mcp/discover-oauth-info (fn [url cid csecret _port _cname]
                                                (reset! oauth-info-args* {:client-id cid :client-secret csecret})
                                                {:token-endpoint "https://example.com/token"
                                                 :client-id cid :client-secret csecret :resource url})
                    m.mcp/refresh-token! (fn [_te _cid _rt & _opts]
                                           {:access-token "a" :refresh-token "r" :expires-at 2})
                    db.global-cache/update-global-cache! (fn [_db _metrics] nil)]
        (#'m.mcp/try-refresh-token! "s" db* "https://example.com/mcp" nil
                                    {:clientId "cfg-id" :clientSecret "cfg-secret"})
        (is (= {:client-id "cfg-id" :client-secret "cfg-secret"} @oauth-info-args*)))))

  (testing "no-op (no DCR) when there is no stored refresh token"
    (let [db* (atom {:mcp-auth {"s" {:type :auth/oauth :access-token "a"}}})
          oauth-called?* (atom false)]
      (with-redefs [m.mcp/discover-oauth-info (fn [& _] (reset! oauth-called?* true) nil)]
        (is (nil? (#'m.mcp/try-refresh-token! "s" db* "https://example.com/mcp" nil {})))
        (is (false? @oauth-called?*) "oauth-info must not run without a refresh token")))))

(deftest do-call-tool-flags-reinit-on-auth-error-test
  (testing "JSON-RPC error without HTTP status but auth-like message flags reinit"
    (let [needs-reinit?* (atom false)]
      (with-redefs [m.mcp/request-tool-call (fn [_client _name _args _request-opts]
                                              {:jsonrpc "2.0" :id "id-1" :error {:message "Unauthorized: token expired"}})]
        (#'m.mcp/do-call-tool (Object.) "tool" {} needs-reinit?* nil))
      (is (true? @needs-reinit?*))))

  (testing "ordinary JSON-RPC error without HTTP status does not flag reinit"
    (let [needs-reinit?* (atom false)]
      (with-redefs [m.mcp/request-tool-call (fn [_client _name _args _request-opts]
                                              {:jsonrpc "2.0" :id "id-1" :error {:message "spreadsheet not found"}})]
        (#'m.mcp/do-call-tool (Object.) "tool" {} needs-reinit?* nil))
      (is (false? @needs-reinit?*))))

  (testing "HTTP 401 still flags reinit"
    (let [needs-reinit?* (atom false)]
      (with-redefs [m.mcp/request-tool-call (fn [_client _name _args _request-opts]
                                              {:jsonrpc "2.0" :id "id-1" :error {:message "boom" :plumcp.core/http-status 401}})]
        (#'m.mcp/do-call-tool (Object.) "tool" {} needs-reinit?* nil))
      (is (true? @needs-reinit?*)))))

(def ^:private reinit-worthy-http-status? #'m.mcp/reinit-worthy-http-status?)

(deftest reinit-worthy-http-status?-test
  (testing "statuses signaling broken session/auth (404 = expired session per Streamable HTTP spec)"
    (doseq [s [401 403 404 500 502 503]]
      (is (true? (reinit-worthy-http-status? s)) (str "should flag: " s))))
  (testing "ordinary statuses are not reinit-worthy"
    (doseq [s [200 202 400 408 429]]
      (is (false? (reinit-worthy-http-status? s)) (str "should not flag: " s)))))

(deftest do-call-tool-timeout-test
  (testing "timeout + silent ping probe (dead connection) flags reinit"
    (let [needs-reinit?* (atom false)]
      (with-redefs [m.mcp/request-tool-call (fn [_client _name _args _request-opts]
                                              :eca.modules.mcp/call-timeout)
                    pmc/ping (fn [_client opts]
                               (:timeout-value opts))]
        (let [result (#'m.mcp/do-call-tool (Object.) "tool" {} needs-reinit?* nil)]
          (is (true? (:error result)))
          (is (string/includes? (-> result :contents first :text) "re-initializing"))))
      (is (true? @needs-reinit?*))))

  (testing "timeout + ping transport exception flags reinit"
    (let [needs-reinit?* (atom false)]
      (with-redefs [m.mcp/request-tool-call (fn [_client _name _args _request-opts]
                                              :eca.modules.mcp/call-timeout)
                    pmc/ping (fn [_client _opts]
                               (throw (java.io.IOException. "Connection reset")))]
        (#'m.mcp/do-call-tool (Object.) "tool" {} needs-reinit?* nil))
      (is (true? @needs-reinit?*))))

  (testing "timeout but server answers ping (slow tool): plain timeout error, no reinit"
    (let [needs-reinit?* (atom false)]
      (with-redefs [m.mcp/request-tool-call (fn [_client _name _args _request-opts]
                                              :eca.modules.mcp/call-timeout)
                    pmc/ping (fn [_client _opts] {})]
        (let [result (#'m.mcp/do-call-tool (Object.) "tool" {} needs-reinit?* nil)]
          (is (true? (:error result)))
          (is (string/includes? (-> result :contents first :text) "timed out after"))
          (is (not (string/includes? (-> result :contents first :text) "re-initializing")))))
      (is (false? @needs-reinit?*))))

  (testing "timeout on stdio server (no needs-reinit atom): plain timeout error, no probe"
    (let [pinged?* (atom false)]
      (with-redefs [m.mcp/request-tool-call (fn [_client _name _args _request-opts]
                                              :eca.modules.mcp/call-timeout)
                    pmc/ping (fn [_client _opts] (reset! pinged?* true) {})]
        (let [result (#'m.mcp/do-call-tool (Object.) "tool" {} nil nil)]
          (is (true? (:error result)))
          (is (string/includes? (-> result :contents first :text) "timed out after"))))
      (is (false? @pinged?*)))))

(deftest request-tool-call-progress-test
  (testing "progress notifications keep a call alive past the timeout and are reported"
    (let [cancelled* (atom nil)
          reported* (atom [])]
      (with-redefs [m.mcp/tool-call-timeout-ms 200
                    pcs/send-request-to-server (fn [_client request callback]
                                                 (let [token (get-in request [:params :_meta :progressToken])]
                                                   (future
                                                     (dotimes [i 5]
                                                       (Thread/sleep 100)
                                                       (#'m.mcp/on-progress-notification
                                                        {:params {:progressToken token :progress i :total 5
                                                                  :message "working" :_meta {}}}))
                                                     (callback {:jsonrpc "2.0" :id (:id request) :result {:content []}}))))
                    pmc/cancel-sent-request (fn [_client id & _] (reset! cancelled* id))]
        (is (= {:content []} (:result (#'m.mcp/request-tool-call (Object.) "tool" {}
                                                                 {:on-progress #(swap! reported* conj %)}))))
        (is (nil? @cancelled*) "a completed call must not be cancelled")
        (is (= (mapv #(hash-map :progress % :total 5 :message "working") (range 5))
               @reported*)))))

  (testing "times out and cancels the request when neither response nor progress arrives"
    (let [sent-id* (atom nil)
          cancelled* (atom nil)]
      (with-redefs [m.mcp/tool-call-timeout-ms 100
                    pcs/send-request-to-server (fn [_client request _callback]
                                                 (reset! sent-id* (:id request)))
                    pmc/cancel-sent-request (fn [_client id & _] (reset! cancelled* id))]
        (is (= :eca.modules.mcp/call-timeout
               (#'m.mcp/request-tool-call (Object.) "tool" {} nil)))
        (is (some? @sent-id*))
        (is (= @sent-id* @cancelled*)))))

  (testing "progress listeners are removed once calls finish"
    (is (empty? @@#'m.mcp/progress-listeners*))))

(deftest request-tool-call-session-id-test
  (let [sent-meta* (atom nil)]
    (with-redefs [pcs/send-request-to-server (fn [_client request callback]
                                               (reset! sent-meta* (get-in request [:params :_meta]))
                                               (callback {:jsonrpc "2.0" :id (:id request) :result {:content []}}))]
      (testing "the chat id is sent as _meta.sessionId next to the progress token"
        (#'m.mcp/request-tool-call (Object.) "tool" {} {:chat-id "chat-1"})
        (is (= #{:progressToken :sessionId} (set (keys @sent-meta*))))
        (is (string? (:progressToken @sent-meta*)))
        (is (= "chat-1" (:sessionId @sent-meta*))))

      (testing "no sessionId is sent without a chat id"
        (#'m.mcp/request-tool-call (Object.) "tool" {} nil)
        (is (= #{:progressToken} (set (keys @sent-meta*))))))))

(deftest reinitialize-server!-refreshes-before-init-test
  (testing "forces a token refresh before re-initializing (heals server-side expiry)"
    (let [calls* (atom [])]
      (with-redefs-fn {#'pp/stop-client-transport! (fn [_t _g] nil)
                       #'m.mcp/try-refresh-token! (fn [name _db* url _metrics _config]
                                                    (swap! calls* conj [:refresh name url])
                                                    true)
                       #'m.mcp/initialize-server! (fn [name _db* _config _metrics _cb]
                                                    (swap! calls* conj [:init name]))}
        (fn []
          (#'m.mcp/reinitialize-server! "s" :old-client (atom {:mcp-clients {"s" {}}})
           {:mcpServers {"s" {:url "https://example.com/mcp"}}}
           nil)))
      (is (= [[:refresh "s" "https://example.com/mcp"] [:init "s"]] @calls*)
          "refresh runs before init, scoped to the server's url"))))

(defn ^:private call-tool-db* [expires-at]
  (atom {:mcp-clients {"s" {:client :client-s
                            :tools [{:name "tool"}]
                            :needs-reinit?* (atom false)}}
         :mcp-auth {"s" {:access-token "old" :expires-at expires-at}}}))

(def ^:private call-tool-ctx-config {:mcpServers {"s" {:url "https://example.com/mcp"}}})

(deftest call-tool!-refreshes-expired-token-test
  (testing "expired token is refreshed in place, then the call runs on the existing client (no reinit)"
    (let [db* (call-tool-db* 0)
          calls* (atom [])]
      (with-redefs [m.mcp/try-refresh-token! (fn [name _db* url _metrics _config]
                                               (swap! calls* conj [:refresh name url])
                                               true)
                    m.mcp/reinitialize-server! (fn [& _] (swap! calls* conj [:reinit]))
                    m.mcp/do-call-tool (fn [client _name _args _nr _request-opts]
                                         (swap! calls* conj [:call client])
                                         {:error false :contents []})]
        (is (= {:error false :contents []}
               (m.mcp/call-tool! "s" "tool" {} {:db @db* :db* db*
                                                :config call-tool-ctx-config :metrics {}})))
        (is (= [[:refresh "s" "https://example.com/mcp"] [:call :client-s]] @calls*)))))

  (testing "expired token whose refresh fails routes to a full reinit"
    (let [db* (call-tool-db* 0)
          calls* (atom [])]
      (with-redefs [m.mcp/try-refresh-token! (fn [_name _db* _url _metrics _config] nil)
                    m.mcp/reinitialize-server! (fn [name _client _db* _config _metrics]
                                                 (swap! calls* conj [:reinit name]))
                    m.mcp/do-call-tool (fn [client _name _args _nr _request-opts]
                                         (swap! calls* conj [:call client])
                                         {:error false :contents []})]
        (m.mcp/call-tool! "s" "tool" {} {:db @db* :db* db*
                                         :config call-tool-ctx-config :metrics {}})
        (is (= [:reinit "s"] (first @calls*)) "refresh failure reinitializes before calling"))))

  (testing "fresh token: no proactive refresh, calls directly"
    (let [db* (call-tool-db* (+ (quot (System/currentTimeMillis) 1000) 3600))
          calls* (atom [])]
      (with-redefs [m.mcp/try-refresh-token! (fn [& _] (swap! calls* conj [:refresh]) true)
                    m.mcp/do-call-tool (fn [client _name _args _nr _request-opts]
                                         (swap! calls* conj [:call client])
                                         {:error false :contents []})]
        (m.mcp/call-tool! "s" "tool" {} {:db @db* :db* db*
                                         :config call-tool-ctx-config :metrics {}})
        (is (= [[:call :client-s]] @calls*) "no refresh when token is not expired")))))

(deftest call-tool!-sends-chat-id-as-session-id-test
  (let [sent* (atom [])
        fail-next-send?* (atom false)
        db {:mcp-clients {"s" {:client :client-s :tools [{:name "tool"}]}}}]
    (with-redefs [pcs/send-request-to-server (fn [client request callback]
                                               (swap! sent* conj [client (-> request :params :_meta (dissoc :progressToken))])
                                               (if (compare-and-set! fail-next-send?* true false)
                                                 (throw (java.io.IOException. "Connection reset"))
                                                 (callback {:jsonrpc "2.0" :id (:id request) :result {:content []}})))
                  ;; A failed send drops its pending request from the client cache.
                  pcs/get-client-cache-atom (constantly nil)
                  pcs/remove-pending-client-request! (constantly nil)]
      (testing "sent on the tools/call request"
        (is (= {:error nil :contents []}
               (m.mcp/call-tool! "s" "tool" {} {:db db :chat-id "chat-1"})))
        (is (= [[:client-s {:sessionId "chat-1"}]] @sent*)))

      (testing "sent again when a transient transport error retries the call"
        (reset! sent* [])
        (reset! fail-next-send?* true)
        (is (= {:error nil :contents []}
               (m.mcp/call-tool! "s" "tool" {} {:db db :chat-id "chat-1"})))
        (is (= [[:client-s {:sessionId "chat-1"}]
                [:client-s {:sessionId "chat-1"}]]
               @sent*)))

      (testing "sent through the re-initialized client"
        (reset! sent* [])
        (let [db* (atom {:mcp-clients {"s" {:client :client-s
                                            :tools [{:name "tool"}]
                                            :needs-reinit?* (atom true)}}})]
          (with-redefs [m.mcp/reinitialize-server! (fn [server-name _client db* _config _metrics]
                                                     (swap! db* assoc-in [:mcp-clients server-name :client] :new-client))]
            (m.mcp/call-tool! "s" "tool" {} {:db @db* :db* db* :config call-tool-ctx-config :metrics {}
                                             :chat-id "chat-1"}))
          (is (= [[:new-client {:sessionId "chat-1"}]] @sent*))))

      (testing "not sent without a chat id"
        (reset! sent* [])
        (m.mcp/call-tool! "s" "tool" {} {:db db})
        (is (= [[:client-s {}]] @sent*))))))

(deftest with-serialized-sends-test
  (testing "concurrent sends never interleave a message's writes"
    (let [out (java.io.StringWriter.)
          ;; Mirrors the stdio transport: the body and the newline are separate writes.
          transport (reify pp/IClientTransport
                      (client-transport-info [_] {:id :stdio})
                      (start-client-transport [_ _] nil)
                      (stop-client-transport! [_ _] nil)
                      (send-message-to-server [_ message]
                        (.write out ^String message)
                        (Thread/sleep 1)
                        (.write out "\n"))
                      (upon-handshake-success [_ _] nil))
          wrapped (#'m.mcp/with-serialized-sends transport)
          messages (mapv #(str "msg-" %) (range 20))]
      (run! deref (mapv #(future (pp/send-message-to-server wrapped %)) messages))
      (is (= (set messages) (set (string/split-lines (str out)))))
      (is (= {:id :stdio} (pp/client-transport-info wrapped))))))

;; OAuth

(defn- make-auth-response [www-authenticate-header]
  {:status 401
   :headers {"www-authenticate" www-authenticate-header}})

(defn- make-json-response [status body]
  {:status status
   :body body})

(deftest oauth-info-prm-discovery-test
  (testing "discovers metadata via resource_metadata from WWW-Authenticate header"
    (let [get-urls (atom [])]
      (with-redefs [http/head (fn [_ _] {:status 200})
                    http/post (fn [url _]
                                (if (string/includes? url "register")
                                  {:status 404}
                                  (make-auth-response
                                   "Bearer realm=\"test\", resource_metadata=\"https://example.com/prm\"")))
                    http/get (fn [url _opts]
                               (swap! get-urls conj url)
                               (cond
                                 (= url "https://example.com/prm")
                                 (make-json-response 200
                                                     {:authorization_servers ["https://auth.example.com"]
                                                      :scopes_supported ["read" "write"]})

                                 (string/includes? url "oauth-authorization-server")
                                 (make-json-response 200
                                                     {:authorization_endpoint "https://auth.example.com/authorize"
                                                      :token_endpoint "https://auth.example.com/token"})
                                 :else
                                 {:status 404}))]
        (let [info (#'m.mcp/discover-oauth-info "https://example.com/mcp")]
          (is (some? info))
          (is (= "https://auth.example.com/token" (:token-endpoint info)))
          (is (= "https://example.com/mcp" (:resource info)))
          (is (string/includes? (:authorization-endpoint info) "scope=read+write"))
          (is (not (string/includes? (:authorization-endpoint info) "scopes=")))
          (is (string/includes? (:authorization-endpoint info)
                                "resource=https%3A%2F%2Fexample.com%2Fmcp"))
          (is (= "https://example.com/prm" (first @get-urls))
              "should fetch resource_metadata URL first")))))

  (testing "falls back to /.well-known/oauth-protected-resource when no resource_metadata in header"
    (let [get-urls (atom [])]
      (with-redefs [http/head (fn [_ _] {:status 200})
                    http/post (fn [url _]
                                (if (string/includes? url "register")
                                  {:status 404}
                                  (make-auth-response "Bearer realm=\"test\"")))
                    http/get (fn [url _]
                               (swap! get-urls conj url)
                               (cond
                                 (= url "https://example.com/.well-known/oauth-protected-resource")
                                 (make-json-response 200
                                                     {:authorization_servers ["https://auth.example.com"]
                                                      :scopes_supported ["all"]})

                                 (string/includes? url "oauth-authorization-server")
                                 (make-json-response 200
                                                     {:authorization_endpoint "https://auth.example.com/authorize"
                                                      :token_endpoint "https://auth.example.com/token"})
                                 :else
                                 {:status 404}))]
        (let [info (#'m.mcp/discover-oauth-info "https://example.com/mcp")]
          (is (some? info))
          (is (= "https://auth.example.com/token" (:token-endpoint info)))
          (is (= "https://example.com/.well-known/oauth-protected-resource"
                 (first @get-urls))
              "should try PRM well-known path first")))))

  (testing "falls back to /.well-known/oauth-authorization-server when PRM not available"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 404}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (cond
                               (string/includes? url "oauth-protected-resource")
                               {:status 404}

                               (= url "https://example.com/.well-known/oauth-authorization-server")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://example.com/authorize"
                                                    :token_endpoint "https://example.com/token"
                                                    :scopes_supported ["api"]})
                               :else
                               {:status 404}))]
      (let [info (#'m.mcp/discover-oauth-info "https://example.com/mcp")]
        (is (some? info))
        (is (= "https://example.com/token" (:token-endpoint info)))))))

(deftest oauth-info-oidc-discovery-test
  (testing "falls back to OIDC discovery when RFC 8414 metadata not available"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 404}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (cond
                               (string/includes? url "oauth-protected-resource")
                               (make-json-response 200
                                                   {:authorization_servers ["https://auth.example.com/oidc"]})

                               (string/includes? url "oauth-authorization-server")
                               {:status 404}

                               (= url "https://auth.example.com/.well-known/openid-configuration")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://auth.example.com/oidc/authorize"
                                                    :token_endpoint "https://auth.example.com/oidc/token"
                                                    :scopes_supported ["openid" "sql"]})
                               :else
                               {:status 404}))]
      (let [info (#'m.mcp/discover-oauth-info "https://example.com/mcp")]
        (is (some? info))
        (is (= "https://auth.example.com/oidc/token" (:token-endpoint info)))
        (is (string/includes? (:authorization-endpoint info) "scope=openid+sql"))))))

(deftest oauth-info-scope-formatting-test
  (testing "formats scopes as space-separated string using 'scope' param"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 404}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (if (string/includes? url "oauth-authorization-server")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://example.com/authorize"
                                                    :token_endpoint "https://example.com/token"
                                                    :scopes_supported ["read" "write" "admin"]})
                               {:status 404}))]
      (let [info (#'m.mcp/discover-oauth-info "https://example.com/mcp")
            auth-url (:authorization-endpoint info)]
        (is (string/includes? auth-url "scope=read+write+admin"))
        (is (not (string/includes? auth-url "scopes="))))))

  (testing "omits scope param when scopes_supported is nil"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 404}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (if (string/includes? url "oauth-authorization-server")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://example.com/authorize"
                                                    :token_endpoint "https://example.com/token"})
                               {:status 404}))]
      (let [info (#'m.mcp/discover-oauth-info "https://example.com/mcp")
            auth-url (:authorization-endpoint info)]
        (is (not (string/includes? auth-url "scope=")))))))

(deftest oauth-info-resource-parameter-test
  (testing "includes resource parameter in authorization URL"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 404}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (if (string/includes? url "oauth-authorization-server")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://example.com/authorize"
                                                    :token_endpoint "https://example.com/token"})
                               {:status 404}))]
      (let [info (#'m.mcp/discover-oauth-info "https://example.com/api/mcp")]
        (is (= "https://example.com/api/mcp" (:resource info)))
        (is (string/includes? (:authorization-endpoint info)
                              "resource=https%3A%2F%2Fexample.com%2Fapi%2Fmcp")))))

  (testing "passes resource in authorize-token!"
    (let [posted-body (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                                (reset! posted-body (:body opts))
                                {:status 200
                                 :body (java.io.ByteArrayInputStream.
                                        (.getBytes "{\"access_token\":\"tok\",\"expires_in\":3600}"))})]
        (#'m.mcp/authorize-token! {:token-endpoint "https://example.com/token"
                                   :verifier "test-verifier"
                                   :client-id "test-client"
                                   :redirect-uri "http://localhost:9999/auth/callback"
                                   :resource "https://example.com/api/mcp"}
                                  "auth-code")
        (let [decoded (ring.util/form-decode @posted-body)]
          (is (= "https://example.com/api/mcp" (get decoded "resource")))))))

  (testing "passes resource in refresh-token!"
    (let [posted-body (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                                (reset! posted-body (:body opts))
                                {:status 200
                                 :body (java.io.ByteArrayInputStream.
                                        (.getBytes "{\"access_token\":\"new-tok\",\"expires_in\":3600}"))})]
        (#'m.mcp/refresh-token! "https://example.com/token"
                                "test-client"
                                "old-refresh-token"
                                :resource "https://example.com/api/mcp")
        (let [decoded (ring.util/form-decode @posted-body)]
          (is (= "https://example.com/api/mcp" (get decoded "resource")))))))

  (testing "omits resource from token request when nil"
    (let [posted-body (atom nil)]
      (with-redefs [http/post (fn [_ opts]
                                (reset! posted-body (:body opts))
                                {:status 200
                                 :body (java.io.ByteArrayInputStream.
                                        (.getBytes "{\"access_token\":\"tok\",\"expires_in\":3600}"))})]
        (#'m.mcp/authorize-token! {:token-endpoint "https://example.com/token"
                                   :verifier "test-verifier"
                                   :client-id "test-client"
                                   :redirect-uri "http://localhost:9999/auth/callback"}
                                  "auth-code")
        (let [decoded (ring.util/form-decode @posted-body)]
          (is (nil? (get decoded "resource"))))))))

(deftest oauth-info-oidc-fallback-in-resource-discovery-test
  (testing "discovers metadata via OIDC when PRM and RFC 8414 both fail on base URL"
    (let [get-urls (atom [])]
      (with-redefs [http/head (fn [_ _] {:status 200})
                    http/post (fn [url _]
                                (if (string/includes? url "register")
                                  {:status 404}
                                  (make-auth-response "Bearer realm=\"test\"")))
                    http/get (fn [url _]
                               (swap! get-urls conj url)
                               (cond
                                 (= url "https://example.com/.well-known/openid-configuration")
                                 (make-json-response 200
                                                     {:authorization_endpoint "https://example.com/oidc/authorize"
                                                      :token_endpoint "https://example.com/oidc/token"
                                                      :scopes_supported ["openid" "sql"]})
                                 :else
                                 {:status 404}))]
        (let [info (#'m.mcp/discover-oauth-info "https://example.com/mcp")]
          (is (some? info))
          (is (= "https://example.com/oidc/token" (:token-endpoint info)))
          (is (string/includes? (:authorization-endpoint info) "scope=openid+sql"))
          (is (some #(= "https://example.com/.well-known/oauth-protected-resource" %) @get-urls)
              "should have tried PRM first")
          (is (some #(= "https://example.com/.well-known/oauth-authorization-server" %) @get-urls)
              "should have tried RFC 8414 second")
          (is (some #(= "https://example.com/.well-known/openid-configuration" %) @get-urls)
              "should have tried OIDC last"))))))

(deftest oauth-info-trailing-slash-auth-server-test
  (testing "discovers auth server metadata when authorization_server URL has trailing slash"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 200 :body {:client_id "miro-client"}}
                                (make-auth-response
                                 "Bearer resource_metadata=\"https://mcp.miro.com/.well-known/oauth-protected-resource\"")))
                  http/get (fn [url _]
                             (cond
                               (= url "https://mcp.miro.com/.well-known/oauth-protected-resource")
                               (make-json-response 200
                                                   {:resource "https://mcp.miro.com/"
                                                    :authorization_servers ["https://mcp.miro.com/"]
                                                    :scopes_supported ["boards:read" "boards:write"]})

                               (= url "https://mcp.miro.com/.well-known/oauth-authorization-server")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://mcp.miro.com/authorize"
                                                    :token_endpoint "https://mcp.miro.com/token"
                                                    :registration_endpoint "https://mcp.miro.com/register"})

                               ;; The trailing-slash variant should NOT be hit
                               (= url "https://mcp.miro.com/.well-known/oauth-authorization-server/")
                               {:status 401}

                               :else
                               {:status 404}))]
      (let [info (#'m.mcp/discover-oauth-info "https://mcp.miro.com/")]
        (is (some? info) "should discover OAuth info for trailing-slash auth server")
        (is (= "https://mcp.miro.com/token" (:token-endpoint info)))
        (is (string/includes? (:authorization-endpoint info) "mcp.miro.com/authorize"))))))

(deftest oauth-info-returns-nil-when-no-auth-challenge-test
  (testing "returns nil when server does not return 401"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [_ _] {:status 200})]
      (is (nil? (#'m.mcp/discover-oauth-info "https://example.com/mcp"))))))

(deftest oauth-info-returns-nil-when-no-metadata-found-test
  (testing "returns nil when 401 is received but no metadata endpoints respond"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [_ _]
                              (make-auth-response "Bearer realm=\"test\""))
                  http/get (fn [_ _] {:status 404})]
      (is (nil? (#'m.mcp/discover-oauth-info "https://example.com/mcp"))))))

(deftest oauth-info-configured-client-id-test
  (testing "uses configured client-id and skips DCR"
    (let [dcr-called? (atom false)]
      (with-redefs [http/head (fn [_ _] {:status 200})
                    http/post (fn [url _]
                                (when (string/includes? url "register")
                                  (reset! dcr-called? true))
                                (if (string/includes? url "register")
                                  {:status 200 :body {:client_id "dcr-client"}}
                                  (make-auth-response "Bearer realm=\"test\"")))
                    http/get (fn [url _]
                               (if (string/includes? url "oauth-authorization-server")
                                 (make-json-response 200
                                                     {:authorization_endpoint "https://example.com/authorize"
                                                      :token_endpoint "https://example.com/token"
                                                      :registration_endpoint "https://example.com/register"})
                                 {:status 404}))]
        (let [info (#'m.mcp/discover-oauth-info "https://example.com/mcp" "my-databricks-client")]
          (is (some? info))
          (is (= "my-databricks-client" (:client-id info)))
          (is (false? @dcr-called?) "DCR should not be attempted when client-id is configured")
          (is (string/includes? (:authorization-endpoint info)
                                "client_id=my-databricks-client"))))))

  (testing "falls back to DCR then hardcoded when no configured client-id"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 200 :body {:client_id "dcr-obtained-id"}}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (if (string/includes? url "oauth-authorization-server")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://example.com/authorize"
                                                    :token_endpoint "https://example.com/token"
                                                    :registration_endpoint "https://example.com/register"})
                               {:status 404}))]
      (let [info (#'m.mcp/discover-oauth-info "https://example.com/mcp")]
        (is (= "dcr-obtained-id" (:client-id info))))))

  (testing "nil configured client-id behaves as unset"
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                {:status 404}
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (if (string/includes? url "oauth-authorization-server")
                               (make-json-response 200
                                                   {:authorization_endpoint "https://example.com/authorize"
                                                    :token_endpoint "https://example.com/token"})
                               {:status 404}))]
      (let [info (#'m.mcp/discover-oauth-info "https://example.com/mcp" nil)]
        (is (= @#'m.mcp/eca-client-id (:client-id info)))))))

(defn ^:private with-auth-server
  "Stubs an OAuth-protected MCP server whose authorization server supports DCR
   and publishes `extra-meta`. Calls `f` with an atom counting DCR attempts."
  [extra-meta f]
  (let [dcr-calls (atom 0)]
    (with-redefs [http/head (fn [_ _] {:status 200})
                  http/post (fn [url _]
                              (if (string/includes? url "register")
                                (do (swap! dcr-calls inc)
                                    {:status 200 :body {:client_id "dcr-obtained-id"}})
                                (make-auth-response "Bearer realm=\"test\"")))
                  http/get (fn [url _]
                             (if (string/includes? url "oauth-authorization-server")
                               (make-json-response 200
                                                   (merge {:authorization_endpoint "https://example.com/authorize"
                                                           :token_endpoint "https://example.com/token"
                                                           :registration_endpoint "https://example.com/register"}
                                                          extra-meta))
                               {:status 404}))]
      (f dcr-calls))))

(defn ^:private auth-url-params [info]
  (ring.util/form-decode (second (string/split (:authorization-endpoint info) #"\?" 2))))

(deftest oauth-info-cimd-test
  (testing "uses ECA's CIMD URL as client_id instead of DCR when the auth server supports it"
    (with-auth-server
      {:client_id_metadata_document_supported true}
      (fn [dcr-calls]
        (let [info (#'m.mcp/discover-oauth-info "https://example.com/mcp")]
          (is (= @#'m.mcp/cimd-client-id (:client-id info)))
          (is (= @#'m.mcp/cimd-client-id (get (auth-url-params info) "client_id")))
          (is (nil? (:client-secret info)) "CIMD client is public")
          (is (re-matches #"http://localhost:\d+/auth/callback" (:redirect-uri info)))
          (is (zero? @dcr-calls) "DCR should not be attempted")))))

  (testing "keeps DCR when the auth server does not support CIMD"
    (with-auth-server
      {:client_id_metadata_document_supported false}
      (fn [dcr-calls]
        (is (= "dcr-obtained-id" (:client-id (#'m.mcp/discover-oauth-info "https://example.com/mcp"))))
        (is (= 1 @dcr-calls)))))

  (testing "any configured client setting keeps the pre-registered client or DCR"
    (doseq [[setting args expected-client-id] [["clientId" ["my-client"] "my-client"]
                                               ["clientSecret" [nil "my-secret"] "dcr-obtained-id"]
                                               ["oauthPort" [nil nil 19284] "dcr-obtained-id"]
                                               ["clientName" [nil nil nil "Claude Code"] "dcr-obtained-id"]]]
      (with-auth-server
        {:client_id_metadata_document_supported true}
        (fn [_]
          (is (= expected-client-id
                 (:client-id (apply #'m.mcp/discover-oauth-info "https://example.com/mcp" args)))
              setting))))))

(deftest published-client-metadata-document-test
  (testing "docs/oauth/client-metadata.json (served at the CIMD URL) matches ECA's OAuth client"
    (is (match? {:client_id @#'m.mcp/cimd-client-id
                 :redirect_uris (m/embeds ["http://localhost/auth/callback"])
                 :grant_types (m/embeds ["authorization_code" "refresh_token"])
                 :token_endpoint_auth_method "none"}
                (json/parse-string (slurp (io/file "docs" "oauth" "client-metadata.json")) true)))))

(deftest url-without-query-test
  (testing "strips query string"
    (is (= "https://mcp.example.com/v1"
           (#'m.mcp/url-without-query "https://mcp.example.com/v1?token=x&debug=true"))))

  (testing "strips fragment"
    (is (= "https://mcp.example.com/v1"
           (#'m.mcp/url-without-query "https://mcp.example.com/v1#section"))))

  (testing "strips both query and fragment"
    (is (= "https://mcp.example.com/v1"
           (#'m.mcp/url-without-query "https://mcp.example.com/v1?a=1#frag"))))

  (testing "returns URL unchanged when no query or fragment"
    (is (= "https://mcp.example.com/v1/mcp"
           (#'m.mcp/url-without-query "https://mcp.example.com/v1/mcp"))))

  (testing "preserves port"
    (is (= "https://mcp.example.com:8443/v1"
           (#'m.mcp/url-without-query "https://mcp.example.com:8443/v1?key=val"))))

  (testing "different paths are distinct"
    (is (not= (#'m.mcp/url-without-query "https://mcp.example.com/v1")
              (#'m.mcp/url-without-query "https://mcp.example.com/v2"))))

  (testing "different hosts are distinct"
    (is (not= (#'m.mcp/url-without-query "https://a.example.com/v1")
              (#'m.mcp/url-without-query "https://b.example.com/v1"))))

  (testing "returns nil for nil input"
    (is (nil? (#'m.mcp/url-without-query nil)))))

(deftest windows-open-url-command-test
  (testing "hands the whole OAuth URL to the shell's URL handler, no cmd parsing of its `&`s"
    (let [url "https://auth.example.com/authorize?response_type=code&client_id=c&redirect_uri=http%3A%2F%2Flocalhost%3A1&state=s"]
      (is (= ["rundll32" "url.dll,FileProtocolHandler" url]
             (#'m.mcp/windows-open-url-command url))))))
