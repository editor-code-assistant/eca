(ns integration.modules-test
  "Modules in the real server: an external module, interpreted with SCI,
   loaded from modules.<id>.source, its tools sent to the client and its
   command run; :eca/repl, an nREPL a client evaluates code with; and the
   create agent, its tools and its guide."
  (:require
   [babashka.fs :as fs]
   [bencode.core :as bencode]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [integration.helper :refer [match-content]]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]])
  (:import
   [java.io PushbackInputStream]
   [java.net Socket]))

(eca/clean-after-test)

(def ^:private module-source
  "(ns acme.hello
     (:require [clojure.string :as str]
               [eca.api :as eca]))

   (def module
     {:id :acme/hello
      :description \"Says hello.\"
      :tools {\"greet\" {:description \"Greet someone.\"
                        :parameters {:type \"object\" :properties {\"name\" {:type \"string\"}}}
                        :handler (fn [_ctx {:strs [name]}] (str \"Hello \" name))}}
      :commands {\"hello\" {:description \"Say hello.\"
                           :arguments [{:name \"who\"}]
                           :handler (fn [ctx {:keys [who]}]
                                      (str \"Hello \" (str/upper-case who) \" from \" (eca/module-id ctx)))}}})")

(defn- module-server
  "The first tool/serverUpdated for a module, skipping ECA's own."
  []
  (some (fn [_]
          (let [server (eca/client-awaits-server-notification :tool/serverUpdated)]
            (when (= "module" (:type server)) server)))
        (range 5)))

(deftest external-module
  (let [path (str (fs/path eca/*eca-out-dir* "modules-test" "hello.clj"))]
    (fs/create-dirs (fs/parent path))
    (spit path module-source)
    (eca/start-process!)
    (eca/request! (fixture/initialize-request
                   {:initializationOptions (merge fixture/default-init-options
                                                  {:modules {"acme/hello" {:source path}}})
                    :capabilities {:codeAssistant {:chat {}}}}))
    (eca/notify! (fixture/initialized-notification))

    (testing "its tools reach the client on their own server"
      (is (match? {:type "module"
                   :name "hello"
                   :module "acme/hello"
                   :status "running"
                   :tools [{:name "greet" :description "Greet someone."}]}
                  (module-server))))

    (testing "its command is listed"
      (is (match? {:commands (m/embeds [{:name "hello" :description "Say hello." :arguments [{:name "who"}]}])}
                  (eca/request! (fixture/chat-query-commands-request {:query "hello"})))))

    (testing "and runs"
      (let [resp (eca/request! (fixture/chat-prompt-request {:message "/hello ana"}))
            chat-id (:chatId resp)]
        (is (match? {:chatId string? :status "prompting"} resp))
        (match-content chat-id "user" {:type "text" :text "/hello ana\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Loading config"})
        (match-content chat-id "system" {:type "text" :text (m/pred #(string/includes? % "Hello ANA from :acme/hello"))})
        (match-content chat-id "system" {:type "progress" :state "finished"})))))

(defn- repl-port
  "The port in the server's \"nREPL is listening\" message, skipping others."
  []
  (some (fn [_]
          (some->> (:message (eca/client-awaits-server-notification :$/showMessage))
                   (re-find #"nREPL is listening on 127\.0\.0\.1:(\d+)")
                   second
                   parse-long))
        (range 5)))

(defn- ->text [v]
  (cond
    (bytes? v) (String. ^bytes v "UTF-8")
    (map? v) (update-vals v ->text)
    (sequential? v) (mapv ->text v)
    :else v))

(defn- nrepl-eval
  "Evaluates `code` in a new connection to the nREPL on `port`: the last
   value and the output."
  [port code]
  (with-open [socket (doto (Socket. "127.0.0.1" (int port)) (.setSoTimeout 60000))]
    (let [out (.getOutputStream socket)
          in (PushbackInputStream. (.getInputStream socket))]
      (bencode/write-bencode out {"op" "eval" "code" code "id" "1"})
      (.flush out)
      (loop [result {:value nil :out ""}]
        (let [response (->text (bencode/read-bencode in))
              result (cond-> result
                       (get response "value") (assoc :value (get response "value"))
                       (get response "out") (update :out str (get response "out"))
                       (get response "err") (assoc :err (get response "err")))]
          (if (some #{"done"} (get response "status"))
            result
            (recur result)))))))

(deftest repl
  (eca/start-process!)
  (eca/request! (fixture/initialize-request
                 {:initializationOptions (merge fixture/default-init-options
                                                {:modules {"eca/repl" {:enabled true}}})
                  :capabilities {:codeAssistant {:chat {}}}}))
  (eca/notify! (fixture/initialized-notification))

  (let [port (repl-port)]
    (testing "it tells the user its port"
      (is (pos-int? port)))

    (testing "a client evaluates code through it, with eca.api on the live session"
      (is (match? {:value ":active" :out "hi\n"}
                  (nrepl-eval port (str "(require '[eca.api :as eca] '[eca.api.dev :as dev])"
                                        "(println \"hi\")"
                                        "(eca/notify! (dev/ctx) \"Hello from the REPL\")"
                                        "(:status (some #(when (= :eca/repl (:id %)) %) (dev/modules)))"))))
      (is (match? {:message "Hello from the REPL"}
                  (eca/client-awaits-server-notification :$/showMessage))))

    (testing "Java interop on SCI's default classes works as on the JVM, also in the native binary"
      (doseq [[code value] [["(.toUpperCase \"abc\")" "\"ABC\""]
                            ["(.length \"abc\")" "3"]
                            ["(String/valueOf 3)" "\"3\""]
                            ["(Integer/parseInt \"42\")" "42"]
                            ["Integer/MAX_VALUE" "2147483647"]
                            ["(Double/parseDouble \"1.5\")" "1.5"]
                            ["(.intValue 3.7)" "3"]
                            ["(.getMessage (Exception. \"boom\"))" "\"boom\""]
                            ["(try (throw (java.lang.IllegalArgumentException. \"bad\")) (catch Exception e (.getMessage e)))"
                             "\"bad\""]
                            ["(.getMessage (ex-info \"x\" {}))" "\"x\""]
                            ["(let [w (java.io.StringWriter.)] (.write w \"hi\") (str w))" "\"hi\""]]]
        (is (= value (:value (nrepl-eval port code))) code)))

    (testing "/repl is listed"
      (is (match? {:commands (m/embeds [{:name "repl"}])}
                  (eca/request! (fixture/chat-query-commands-request {:query "repl"})))))))

(defn- await-turn-end
  "Consumes the chat's content notifications until its turn finishes."
  [chat-id]
  (loop [n 0]
    (let [{:keys [content] :as notification} (eca/client-awaits-server-notification :chat/contentReceived)]
      (when-not (or (and (= chat-id (:chatId notification))
                         (= "progress" (:type content))
                         (= "finished" (:state content)))
                    (> n 50))
        (recur (inc n))))))

(deftest create-agent
  (eca/start-process!)
  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))

  (testing "clients list it after code and plan"
    (is (match? {:chat {:agents ["code" "plan" "create"]}}
                (eca/client-awaits-server-notification :config/updated))))

  (testing "its chats get its prompt and tools, and its guide is ECA's docs"
    (llm.mocks/set-case! :create-guide-0)
    (let [resp (eca/request! (fixture/chat-prompt-request
                              {:model "anthropic/claude-sonnet-4-6"
                               :agent "create"
                               :message "How do modules work?"}))]
      (await-turn-end (:chatId resp))
      (is (match? {:system (m/pred (fn [blocks]
                                     (some #(string/includes? (:text %) "ECA — Editor Code Assistant (Create)")
                                           blocks)))
                   :tools (m/embeds [{:name "create__guide"} {:name "create__modules"} {:name "create__eval"}])
                   :messages (m/embeds [{:role "user"
                                         :content [{:type "tool_result"
                                                    :tool_use_id "create-1"
                                                    :content #"^# Modules\n"}]}])}
                  (llm.mocks/get-req-body :create-guide-0))))))
