(ns eca.api-runtime-test
  "eca.api's runtime: background jobs, data folders, shell commands, LLM calls
   and their metering, and HTTP requests."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.api :as eca]
   [eca.db.chats :as db.chats]
   [eca.features.chat.ops :as f.chat.ops]
   [eca.llm-api :as llm-api]
   [eca.modules :as modules]
   [eca.modules.core :as m.core]
   [eca.modules.data :as modules.data]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]
   [org.httpkit.server :as http-server]))

(h/reset-components-before-test)

(def ^:private worker
  {:id :acme/worker
   :start (fn [_] {:started true})})

(defn- components []
  {:db* (h/db*) :messenger (h/messenger) :metrics (h/metrics)})

(defn- ctx []
  (merge (components) {:module-id :acme/worker :chat-id "chat-1" :config {}}))

(defmacro ^:private with-worker
  "Runs body with `worker` started, next to :eca/core."
  [& body]
  `(with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with))]
     (modules/add-external! "/tmp/worker.clj" worker)
     (modules/start! (components) {})
     ~@body))

(deftest background-test
  (with-worker
    (testing "a job runs on the module's own threads"
      (let [job (eca/background! (ctx) #(.getName (Thread/currentThread)))]
        (is (string/starts-with? (deref job 5000 :timeout) "eca-module-acme/worker-default-"))))
    (testing "named pools"
      (let [job (eca/background! (ctx) {:pool :summaries :size 2} #(.getName (Thread/currentThread)))]
        (is (string/starts-with? (deref job 5000 :timeout) "eca-module-acme/worker-summaries-"))))
    (testing "a failing job is logged and counted as a failure, and gives nil"
      (is (nil? (deref (eca/background! (ctx) #(throw (ex-info "boom" {}))) 5000 :timeout)))
      (is (= 1 (modules/failures :acme/worker))))
    (testing "jobs are interrupted when the module stops"
      (let [interrupted (promise)
            started (promise)
            job (eca/background! (ctx) (fn []
                                         (deliver started true)
                                         (try (Thread/sleep 10000)
                                              (catch InterruptedException _ (deliver interrupted true)))))]
        (deref started 5000 :timeout)
        (modules/stop-all! (components))
        (is (true? (deref interrupted 5000 :timeout)))
        ;; the job returns right after it sees the interrupt: wait for it
        (is (not= :timeout (deref job 5000 :timeout)))))))

(deftest data-dir-test
  (swap! (h/db*) assoc-in [:chats "chat-1"] {:id "chat-1"})
  (let [module-dir (eca/data-dir (ctx))
        chat-dir (eca/data-dir (ctx) {:chat? true})]
    (testing "folders in the workspace's cache, made on first use"
      (is (fs/directory? module-dir))
      (is (string/ends-with? module-dir (str (fs/path "modules" "acme" "worker"))))
      (is (= (str (fs/path module-dir "chats" "chat-1")) chat-dir))
      (is (fs/directory? chat-dir)))
    (testing "the chat's folders go with the chat"
      (spit (str (fs/path chat-dir "notes.md")) "notes")
      (f.chat.ops/delete-chat {:chat-id "chat-1"} (h/db*) (h/messenger) {} (h/metrics))
      (is (not (fs/exists? chat-dir)))
      (is (fs/directory? module-dir)))
    (testing "also when old chats are cleaned up"
      (swap! (h/db*) assoc-in [:chats "old"] {:id "old" :updated-at 1})
      (let [old-dir (eca/data-dir (assoc (ctx) :chat-id "old") {:chat? true})]
        (db.chats/cleanup-old-chats! (h/db*) (h/metrics) 1)
        (is (not (fs/exists? old-dir)))))
    (testing "ids that aren't file names are made safe"
      (is (string/ends-with? (str (modules.data/chat-dir (h/db) :acme/x "../../etc")) "_.._etc")))))

;; needs a POSIX shell; on Windows the test doesn't exist (one without assertions fails)
(when-not h/windows?
  (deftest shell-test
    (testing "runs a command and waits for it"
      (is (= {:exit 0 :out "hi\n" :err ""} (eca/shell! (ctx) "echo hi"))))
    (testing "env, input and cwd"
      (let [dir (str (fs/canonicalize (fs/create-temp-dir)))]
        (try
          (is (match? {:exit 0 :out (str "x " dir "\nfrom stdin")}
                      (eca/shell! (ctx) "echo \"$ACME_X $(pwd)\"; cat" {:env {"ACME_X" "x"} :input "from stdin" :cwd dir})))
          (finally (fs/delete-tree dir)))))
    (testing "a command that takes too long is killed"
      (is (match? {:exit nil :timeout? true :err "Timed out after 200 ms"}
                  (eca/shell! (ctx) "sleep 5" {:timeout-ms 200}))))
    (testing ":shell/before-exec handlers may rewrite it"
      (with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with))]
        (modules/add-external! "/tmp/rewriter.clj"
                               {:id :acme/rewriter
                                :event-handlers {:shell/before-exec
                                                 (fn [_ {:keys [command]}] {:command (str command " world")})}})
        (is (= "hello world\n" (:out (eca/shell! (ctx) "echo hello"))))))))

(deftest complete-test
  (swap! (h/db*) assoc :models {"anthropic/claude-haiku-4-5" {:tools true :reason? true
                                                              :input-token-cost 0.000001
                                                              :output-token-cost 0.000005}})
  (swap! (h/db*) assoc-in [:chats "chat-1"] {:id "chat-1" :model "anthropic/claude-haiku-4-5"})
  (let [requests (atom [])]
    (with-redefs [llm-api/sync-prompt! (fn [request]
                                         (swap! requests conj request)
                                         {:output-text "A summary." :usage {:input-tokens 1000 :output-tokens 200}})]
      (testing "one call, with the chat's model by default"
        (is (= {:text "A summary." :usage {:input-tokens 1000 :output-tokens 200}}
               (eca/complete! (ctx) {:instructions "Summarize."
                                     :messages [{:role "user" :content "first"}
                                                {:role "assistant" :content "reply"}]
                                     :prompt "Summarize the chat."})))
        (is (match? {:provider "anthropic" :model "claude-haiku-4-5" :instructions "Summarize."
                     :model-capabilities {:tools false :reason? false}
                     :past-messages [{:role "user" :content [{:type :text :text "first"}]}
                                     {:role "assistant" :content [{:type :text :text "reply"}]}]
                     :user-messages [{:role "user" :content [{:type :text :text "Summarize the chat."}]}]}
                    (first @requests))))
      (testing "metered for the module, in the session and in the chat"
        (eca/complete! (ctx) {:messages [{:role "user" :content "again"}] :max-output-tokens 50})
        (is (= 50 (get-in (second @requests) [:model-capabilities :max-output-tokens])))
        (is (match? {:calls 2 :input-tokens 2000 :output-tokens 400 :cost 0.004}
                    (get-in (h/db) [:module-usage :acme/worker])))
        (is (match? {:calls 2 :cost 0.004} (get-in (h/db) [:chats "chat-1" :module-usage :acme/worker]))))
      (testing "/costs shows a line per module"
        (is (= ["\nModules' own LLM calls, not in the totals above:"
                "- `acme/worker`: 2 calls, 2000 input tokens, 400 output tokens, $0.004"]
               (m.core/module-usage-lines (get-in (h/db) [:chats "chat-1" :module-usage])))))
      (testing "/modules shows the session's calls"
        (with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with))]
          (modules/add-external! "/tmp/worker.clj" worker)
          (is (string/includes? (#'m.core/modules-text {} (h/db)) "2 LLM calls ($0.004)"))))
      (testing "mistakes"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown model \"acme/nope\""
                              (eca/complete! (ctx) {:model "acme/nope" :prompt "x"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs the user's message last"
                              (eca/complete! (ctx) {:messages [{:role "assistant" :content "x"}]})))))
    (testing "a failed call throws"
      (with-redefs [llm-api/sync-prompt! (constantly {:error {:message "rate limited"}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"The LLM call failed: rate limited"
                              (eca/complete! (ctx) {:prompt "x"})))))))

(deftest models-test
  (swap! (h/db*) assoc :models {"anthropic/claude-haiku-4-5" {:tools true}})
  (is (= {"anthropic/claude-haiku-4-5" {:tools true}} (eca/models (ctx)))))

(deftest http-test
  (let [server (http-server/run-server
                (fn [{:keys [request-method uri query-string headers body]}]
                  {:status (if (= "/missing" uri) 404 200)
                   :headers {"Content-Type" "application/json"}
                   :body (json/generate-string {:method (name request-method)
                                                :uri uri
                                                :query query-string
                                                :auth (get headers "authorization")
                                                :body (some-> body slurp)})})
                {:port 0 :legacy-return-value? false})
        url (str "http://localhost:" (http-server/server-port server))]
    (try
      (is (match? {:status 200 :body {:method "get" :uri "/issue" :query "key=ECA-1" :auth "Bearer t0k"}}
                  (eca/http {:url (str url "/issue") :query-params {:key "ECA-1"} :oauth-token "t0k" :as :json})))
      (is (match? {:status 200 :body {:method "post" :body "{\"a\":1}"}}
                  (eca/http {:method :post :url url :json {:a 1} :as :json})))
      (testing "the body is text by default"
        (is (string? (:body (eca/http {:url url})))))
      (testing "error statuses don't throw"
        (is (= 404 (:status (eca/http {:url (str url "/missing")})))))
      (finally
        (http-server/server-stop! server)))))
