(ns eca.features.repl-test
  (:require
   [babashka.fs :as fs]
   [bencode.core :as bencode]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.api.debug :as debug]
   [eca.api.dev :as dev]
   [eca.config :as config]
   [eca.features.commands :as f.commands]
   [eca.features.repl :as f.repl]
   [eca.modules :as modules]
   [eca.modules.events :as events]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]])
  (:import
   [java.io InputStream OutputStream PushbackInputStream]
   [java.net Socket]))

(set! *warn-on-reflection* true)

(h/reset-components-before-test)

(defn- components []
  {:db* (h/db*) :messenger (h/messenger) :metrics (h/metrics)})

(defn- repl-config [& [config]]
  (merge {:modules {:eca/repl {:enabled true}}} config))

(defmacro ^:private with-repl
  "Runs body with the session's modules started under `config`, just
   :eca/core and :eca/repl, and stops them after."
  [config & body]
  `(with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with :eca/repl))]
     (modules/start! (components) ~config)
     (try
       ~@body
       (finally
         (modules/stop-all! (components))))))

(defn- repl-state [] (modules/state :eca/repl))

;; --- A small nREPL client

(defn- ->text [v]
  (cond
    (bytes? v) (String. ^bytes v "UTF-8")
    (map? v) (update-vals v ->text)
    (sequential? v) (mapv ->text v)
    :else v))

(defn- connect! [port]
  (let [socket (doto (Socket. "127.0.0.1" (int port))
                 (.setSoTimeout 20000))]
    {:socket socket
     :in (PushbackInputStream. (.getInputStream socket))
     :out (.getOutputStream socket)}))

(defn- read-response [{:keys [in]}]
  (->text (bencode/read-bencode in)))

(defn- request! [{:keys [^OutputStream out] :as conn} msg]
  (bencode/write-bencode out msg)
  (.flush out)
  (loop [responses []]
    (let [response (read-response conn)
          responses (conj responses response)]
      (if (some #{"done"} (get response "status"))
        responses
        (recur responses)))))

(defn- eval! [conn code]
  (let [responses (request! conn {"op" "eval" "code" code "id" (str (random-uuid))})]
    {:value (last (keep #(get % "value") responses))
     :out (apply str (keep #(get % "out") responses))
     :err (apply str (keep #(get % "err") responses))}))

(defn- command-text [command config]
  (get-in (f.commands/handle-command! command [] {:chat-id "chat-1" :config config :db* (h/db*)})
          [:chats "chat-1" :messages 0 :content 0 :text]))

(deftest off-by-default-test
  (with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with :eca/repl))]
    (is (not (modules/active? {} :eca/repl)))
    (is (match? {:status :disabled :reason #"Off by default"}
                (some #(when (= :eca/repl (:id %)) %) (modules/statuses {}))))))

(deftest repl-test
  (with-repl (repl-config)
    (let [{:keys [host port port-file]} (repl-state)]
      (testing "it listens on a free loopback port and tells the user"
        (is (= "127.0.0.1" host))
        (is (pos? port))
        (is (match? (m/embeds [{:type :info :message (str "ECA's nREPL is listening on 127.0.0.1:" port
                                                          ": /repl shows how to use it.")}])
                    (:show-message (h/messages)))))
      (testing "it writes its port in its folder of the workspace's cache"
        (is (= (str (f.repl/port-file (h/db))) port-file))
        (is (= (str port) (slurp port-file))))
      (testing "/repl shows the address"
        (let [text (command-text "repl" (repl-config))]
          (is (string/includes? text (str "ECA's nREPL is listening on 127.0.0.1:" port ".")))
          (is (string/includes? text (str "Port file: " port-file)))))
      (let [conn (connect! port)]
        (testing "it evaluates code in the modules' SCI context"
          (is (= {:value "3" :out "" :err ""} (eval! conn "(+ 1 2)")))
          (is (match? {:value "true" :out "hi\n"}
                      (eval! conn (str "(require '[eca.api.dev :as dev])"
                                       "(println \"hi\")"
                                       "(boolean (some #(= :eca/repl (:id %)) (dev/modules)))")))))
        (testing "errors come back as errors"
          (is (match? {:err #"Divide by zero"} (eval! conn "(/ 1 0)"))))
        (testing "eca.api works on the live session through dev/ctx"
          (eval! conn "(require '[eca.api :as eca]) (eca/notify! (dev/ctx) \"Hello from the REPL\")")
          (is (match? (m/embeds [{:type :info :message "Hello from the REPL"}])
                      (:show-message (h/messages)))))
        (testing "eca.api.debug reads ECA's state"
          (swap! (h/db*) assoc-in [:chats "chat-1"] {:id "chat-1" :messages [{:role "user"}]})
          (is (= {:value "\"chat-1\""}
                 (select-keys (eval! conn "(require '[eca.api.debug :as debug]) (:id (debug/chat-raw \"chat-1\"))")
                              [:value]))))
        (testing "stopping it disconnects clients and removes the port file"
          (modules/stop-all! (components))
          (is (not (fs/exists? port-file)))
          (is (= -1 (.read ^InputStream (:in conn))))
          (is (thrown? java.net.ConnectException (connect! port))))))))

(deftest trace-test
  (with-repl (repl-config)
    (let [conn (connect! (:port (repl-state)))
          config (repl-config)]
      (eval! conn "(require '[eca.api.debug :as debug]) (debug/trace! \"chat-1\")")
      (testing "the chat's events print to the REPL that traces it"
        (events/emit! {:config config :chat-id "chat-1"} :chat/message-added
                      {:chat-id "chat-1" :agent "code" :message {:role "user" :content "hi"}})
        (is (= "[trace chat-1] :chat/message-added {:message {:role \"user\", :content \"hi\"}}\n"
               (get (read-response conn) "out"))))
      (testing "other chats' events don't"
        (events/emit! {:config config :chat-id "chat-2"} :chat/message-added {:chat-id "chat-2" :message {}})
        (eval! conn "(debug/untrace! \"chat-1\")")
        (events/emit! {:config config :chat-id "chat-1"} :chat/message-added {:chat-id "chat-1" :message {}})
        (is (= {:value "1"} (select-keys (eval! conn "1") [:value]))
            "the next message is the eval's, not a trace line")))))

(deftest trace-line-test
  (let [trace-line #'debug/trace-line]
    (testing "a transform shows what changed, and who ended it"
      (is (= "[trace c] :tool/before-call {:tool \"t\", :approval :ask} => {:approval :deny, :block \"no\", :ended-by :acme/x}"
             (trace-line "c" :tool/before-call :transform
                         {:chat-id "c" :tool "t" :approval :ask}
                         {:chat-id "c" :tool "t" :approval :deny :block "no" ::events/ended-by :acme/x}))))
    (testing "a handle event shows the answer"
      (is (= "[trace c] :chat/title {} => {:title \"T\"}"
             (trace-line "c" :chat/title :handle {:chat-id "c"} {:title "T"})))
      (is (= "[trace c] :chat/title {} => no answer"
             (trace-line "c" :chat/title :handle {:chat-id "c"} nil))))
    (testing "long lines are cut"
      (is (= 2001 (count (trace-line "c" :chat/message-added :notify
                                     {:message (apply str (repeat 3000 "x"))}
                                     {:message (apply str (repeat 3000 "x"))})))))))

(deftest debug-api-test
  (testing "it works only while :eca/repl runs"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only while the eca/repl module runs" (debug/db)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only while the eca/repl module runs" (debug/trace! "c"))))
  (with-repl (repl-config)
    (swap! (h/db*) assoc-in [:chats "chat-1"] {:id "chat-1"})
    (is (= {:id "chat-1"} (debug/chat-raw "chat-1")))
    (is (nil? (debug/chat-raw "nope")))
    (is (= (h/db) (debug/db)))
    (testing "config-raw is the config before :config/resolve"
      (is (= (config/merged-layers {:workspace-folders (:workspace-folders (h/db))})
             (debug/config-raw))))))

(deftest dev-ctx-test
  (testing "it needs the session"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"session hasn't started" (dev/ctx))))
  (with-repl (repl-config)
    (swap! (h/db*) assoc-in [:chats "chat-1"] {:id "chat-1"})
    (is (match? {:db* (h/db*) :messenger (h/messenger) :module-id m/absent :chat-id m/absent} (dev/ctx)))
    (is (match? {:module-id :acme/x :chat-id "chat-1"} (dev/ctx {:module :acme/x :chat-id "chat-1"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"There's no chat" (dev/ctx {:chat-id "nope"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"qualified keyword" (dev/ctx {:module "acme/x"})))))

(deftest loopback-only-test
  (with-repl (repl-config {:repl {:host "8.8.8.8"}})
    (testing "a host that isn't a loopback address fails to start"
      (is (not (modules/running? :eca/repl)))
      (is (match? {:status :failed :reason #"repl.host must be a loopback address"}
                  (some #(when (= :eca/repl (:id %)) %) (modules/statuses (repl-config))))))))

(deftest bad-port-test
  (with-repl (repl-config {:repl {:port 70000}})
    (is (match? {:status :failed :reason #"repl.port must be a port number"}
                (some #(when (= :eca/repl (:id %)) %) (modules/statuses (repl-config)))))))
