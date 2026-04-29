(ns eca.features.tools.mcp-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.core.memoize :as memoize]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.tools.mcp :as mcp]
   [eca.network :as network]
   [plumcp.core.api.mcp-client :as pmc]
   [plumcp.core.client.http-client-transport :as phct]
   [plumcp.core.protocol :as pp]
   [plumcp.core.support.http-client :as phc]))

(deftest all-tools-test
  (testing "empty db"
    (is (= []
           (mcp/all-tools {}))))

  (testing "db with no mcp-clients"
    (is (= []
           (mcp/all-tools {:some-other-key "value"}))))

  (testing "db with empty mcp-clients"
    (is (= []
           (mcp/all-tools {:mcp-clients {}}))))

  (testing "db with mcp-clients but no tools"
    (is (= []
           (mcp/all-tools {:mcp-clients {"server1" {}}}))))

  (testing "db with single server with tools"
    (let [tools [{:name "tool1" :description "desc1" :server {:name "server1" :version "1.0.0"}}
                 {:name "tool2" :description "desc2" :server {:name "server1" :version "1.0.0"}}]]
      (is (= tools
             (mcp/all-tools {:mcp-clients {"server1" {:version "1.0.0"
                                                      :tools tools}}})))))

  (testing "db with multiple servers with tools"
    (let [tools1 [{:name "tool1" :description "desc1" :server {:name "server1" :version nil}}]
          tools2 [{:name "tool2" :description "desc2" :server {:name "server2" :version nil}}
                  {:name "tool3" :description "desc3" :server {:name "server2" :version nil}}]]
      (is (= (concat tools1 tools2)
             (mcp/all-tools {:mcp-clients {"server1" {:tools tools1}
                                           "server2" {:tools tools2}}}))))))

(deftest all-prompts-test
  (testing "empty db"
    (is (= []
           (mcp/all-prompts {}))))

  (testing "db with no mcp-clients"
    (is (= []
           (mcp/all-prompts {:some-other-key "value"}))))

  (testing "db with empty mcp-clients"
    (is (= []
           (mcp/all-prompts {:mcp-clients {}}))))

  (testing "db with mcp-clients but no prompts"
    (is (= []
           (mcp/all-prompts {:mcp-clients {"server1" {}}}))))

  (testing "db with single server with prompts"
    (let [prompts [{:name "prompt1" :description "desc1"}
                   {:name "prompt2" :description "desc2"}]
          expected (mapv #(assoc % :server "server1") prompts)]
      (is (= expected
             (mcp/all-prompts {:mcp-clients {"server1" {:prompts prompts}}})))))

  (testing "db with multiple servers with prompts"
    (let [prompts1 [{:name "prompt1" :description "desc1"}]
          prompts2 [{:name "prompt2" :description "desc2"}
                    {:name "prompt3" :description "desc3"}]
          expected (concat
                    (mapv #(assoc % :server "server1") prompts1)
                    (mapv #(assoc % :server "server2") prompts2))]
      (is (= expected
             (mcp/all-prompts {:mcp-clients {"server1" {:prompts prompts1}
                                             "server2" {:prompts prompts2}}}))))))

(deftest all-resources-test
  (testing "empty db"
    (is (= []
           (mcp/all-resources {}))))

  (testing "db with no mcp-clients"
    (is (= []
           (mcp/all-resources {:some-other-key "value"}))))

  (testing "db with empty mcp-clients"
    (is (= []
           (mcp/all-resources {:mcp-clients {}}))))

  (testing "db with mcp-clients but no resources"
    (is (= []
           (mcp/all-resources {:mcp-clients {"server1" {}}}))))

  (testing "db with single server with resources"
    (let [resources [{:uri "file://test1" :name "resource1"}
                     {:uri "file://test2" :name "resource2"}]
          expected (mapv #(assoc % :server "server1") resources)]
      (is (= expected
             (mcp/all-resources {:mcp-clients {"server1" {:resources resources}}})))))

  (testing "db with multiple servers with resources"
    (let [resources1 [{:uri "file://test1" :name "resource1"}]
          resources2 [{:uri "file://test2" :name "resource2"}
                      {:uri "file://test3" :name "resource3"}]
          expected (concat
                    (mapv #(assoc % :server "server1") resources1)
                    (mapv #(assoc % :server "server2") resources2))]
      (is (= expected
             (mcp/all-resources {:mcp-clients {"server1" {:resources resources1}
                                               "server2" {:resources resources2}}}))))))

(deftest shutdown!-test
  (testing "shutdown with no clients"
    (let [db* (atom {})]
      (mcp/shutdown! db*)
      (is (= {:mcp-clients {}} @db*))))

  (testing "shutdown with empty mcp-clients"
    (let [db* (atom {:mcp-clients {}})]
      (mcp/shutdown! db*)
      (is (= {:mcp-clients {}} @db*))))

  (testing "shutdown preserves other db data"
    (let [db* (atom {:mcp-clients {}
                     :workspace-folders []
                     :other-key "value"})]
      (mcp/shutdown! db*)
      (is (= {:mcp-clients {}
              :workspace-folders []
              :other-key "value"} @db*)))))

(def ^:private ->transport #'mcp/->transport)

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

(def ^:private walk-server-leaves #'mcp/walk-server-leaves)
(def ^:private rj-assoc-server-entry #'mcp/rj-assoc-server-entry)

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
        (#'mcp/update-config-file! file
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
          (let [parsed (#'mcp/parse-json-with-comments after)]
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
        (#'mcp/update-config-file! file
                                   #(rj-assoc-server-entry % "s"
                                                           {:command "new-bin"
                                                            :args ["-a"]}))
        (let [parsed (#'mcp/parse-json-with-comments (slurp file))]
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
           (mcp/add-server! "existing" {:command "bin"} {} db* config nil
                            {:on-server-updated (constantly nil)})))))

  (testing "rejects both :command and :url"
    (let [db* (atom {:workspace-folders []})
          config {:mcpServers {}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"must not specify both"
           (mcp/add-server! "s" {:command "bin" :url "https://x"} {} db* config nil
                            {:on-server-updated (constantly nil)})))))

  (testing "rejects neither :command nor :url"
    (let [db* (atom {:workspace-folders []})
          config {:mcpServers {}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"must specify :command.*or :url"
           (mcp/add-server! "s" {:disabled true} {} db* config nil
                            {:on-server-updated (constantly nil)})))))

  (testing "rejects blank name"
    (let [db* (atom {:workspace-folders []})
          config {:mcpServers {}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"name must be non-blank"
           (mcp/add-server! "" {:command "bin"} {} db* config nil
                            {:on-server-updated (constantly nil)})))))

  (testing "rejects :workspace scope without :workspace-uri"
    (let [db* (atom {:workspace-folders []})
          config {:mcpServers {}}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #":workspace scope requires :workspace-uri"
           (mcp/add-server! "s" {:command "bin"} {:scope :workspace} db* config nil
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
          (memoize/memo-clear! config/all)
          (let [result (mcp/add-server! "s"
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
            (let [written (#'mcp/parse-json-with-comments (slurp config-file))]
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
          (mcp/remove-server! "s" db*
                              {:mcpServers {"s" {:url "https://x"}
                                            "other" {:command "o"}}}
                              {:on-server-updated (constantly nil)
                               :on-server-removed #(reset! removed* %)}))
        (is (= {:name "s"} @removed*))
        (is (nil? (get-in @db* [:mcp-auth "s"])))
        (let [written (#'mcp/parse-json-with-comments (slurp config-file))]
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
           (mcp/remove-server! "ghost" db*
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
      (with-redefs-fn {#'mcp/->transport make-transport
                       #'mcp/->client (fn [_name _transport _timeout _workspaces _opts]
                                        mock-client)
                       #'pp/stop-client-transport! (fn [_t _g] nil)
                       #'pmc/get-initialize-result (fn [_client]
                                                     {:serverInfo {:version "1.0"}
                                                      :capabilities {:tools true}})
                       #'pmc/list-tools (fn [_client _opts] [])
                       #'pmc/list-prompts (fn [_client _opts] [])
                       #'pmc/list-resources (fn [_client _opts] [])
                       #'mcp/try-refresh-token! (fn [_name _db* _url _metrics _config]
                                                  (swap! refresh-count* inc)
                                                  true)}
        (fn []
          (#'mcp/initialize-server! "test-server" db*
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
      (with-redefs-fn {#'mcp/->transport (fn [_name _config _workspaces _db*]
                                           (swap! attempt-count* inc)
                                           {:transport :mock-transport
                                            :needs-reinit?* (atom true)})
                       #'mcp/->client (fn [_name _transport _timeout _workspaces _opts]
                                        (Object.))
                       #'pp/stop-client-transport! (fn [_t _g] nil)
                       #'pmc/get-initialize-result (fn [_client]
                                                     {:serverInfo {:version "1.0"}
                                                      :capabilities {:tools true}})
                       #'pmc/list-tools (fn [_client _opts] [])
                       #'pmc/list-prompts (fn [_client _opts] [])
                       #'pmc/list-resources (fn [_client _opts] [])
                       #'mcp/try-refresh-token! (fn [_name _db* _url _metrics _config]
                                                  (swap! refresh-count* inc)
                                                  false)}
        (fn []
          (#'mcp/initialize-server! "fail-server" db*
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
           (:description (#'mcp/tool->internal
                          {:name "create-image"
                           :description "Generate an image"
                           :inputSchema {}})))))

  (testing "falls back to :title when :description is missing"
    (is (= "Create Image"
           (:description (#'mcp/tool->internal
                          {:name "create-image"
                           :title "Create Image"
                           :inputSchema {}})))))

  (testing "falls back to :title when :description is empty string"
    (is (= "Create Image"
           (:description (#'mcp/tool->internal
                          {:name "create-image"
                           :description ""
                           :title "Create Image"
                           :inputSchema {}})))))

  (testing "synthesizes from :name when both :description and :title are missing"
    (is (= "MCP tool: create-image"
           (:description (#'mcp/tool->internal
                          {:name "create-image"
                           :inputSchema {}})))))

  (testing "synthesizes from :name when both :description and :title are empty"
    (is (= "MCP tool: edit-image"
           (:description (#'mcp/tool->internal
                          {:name "edit-image"
                           :description ""
                           :title ""
                           :inputSchema {}}))))))
