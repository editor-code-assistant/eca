(ns eca.features.chat.debug-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat.debug :as sut]))

(def ^:private sample-chat
  {:id "chat-1"
   :title "Fix the Login Bug!"
   :status :idle
   :model "anthropic/claude-sonnet-4-6"
   :variant "high"
   :last-api :anthropic
   :trust false
   :user-prompt-count 2
   :messages [{:role "user" :content "secret user text here" :created-at 1}
              {:role "assistant" :content [{:type :text :text "secret assistant reply"}] :created-at 2}
              {:role "reason" :content {:id "r1" :external-id "sig-secret-aBcDeF0123456789aBcDeF0123456789"
                                        :text "secret chain of thought" :total-time-ms 10 :api :anthropic} :created-at 3}
              {:role "tool_call" :content {:id "t1" :name "read_file" :full-name "eca__read_file"
                                           :origin :native :arguments {:path "/etc/passwd"} :api :anthropic} :created-at 4}
              {:role "tool_call_output" :content {:id "t1" :name "read_file" :full-name "eca__read_file"
                                                  :origin :native :error false
                                                  :arguments {:path "/etc/passwd"}
                                                  :output {:error false :contents [{:type :text :text "root:x:0:0:secret"}]}
                                                  :api :anthropic} :created-at 5}
              {:role "user" :content "secret user text here" :created-at 6}]
   :tool-calls {"t1" {:status :completed :name "read_file" :full-name "eca__read_file"
                      :origin :native :start-time 100
                      :approved?* (promise) :future-cleanup-complete?* (promise) :future (future 1)
                      :arguments {:path "/etc/passwd"}}}})

(def ^:private sample-db
  {:chats {"chat-1" sample-chat}
   :models {"anthropic/claude-sonnet-4-6" {:reason? true :tools true :max-output-tokens 8192
                                           :limit {:context 200000 :output 8192}}}
   :mcp-clients {"my-server" {:status :running :tools [{:name "do-thing" :description "x" :parameters {}}]}}
   :auth {"anthropic" {:type :auth/oauth :access-token "SECRET-TOKEN" :refresh-token "SECRET-REFRESH" :expires-at 999}
          "openai" {}}})

(def ^:private all-tools
  [{:name "read_file" :full-name "eca__read_file" :origin :native :parameters {:big "schema"}}
   {:name "do-thing" :full-name "my-server__do-thing" :origin :mcp :server "my-server"}])

(deftest obfuscate-str-test
  (testing "same input reuses a label, different inputs differ"
    (let [obf (sut/make-obfuscator)
          a1 (sut/obfuscate-str obf "hello")
          b (sut/obfuscate-str obf "world")
          a2 (sut/obfuscate-str obf "hello")]
      (is (= a1 a2))
      (is (not= a1 b))
      (is (string/starts-with? a1 "A"))
      (is (string/starts-with? b "B"))))
  (testing "longer strings produce longer (bucketed) tokens"
    (let [obf (sut/make-obfuscator)
          short-s (sut/obfuscate-str obf "ab")
          long-s (sut/obfuscate-str obf (apply str (repeat 5000 "x")))]
      (is (< (count short-s) (count long-s)))))
  (testing "blank strings pass through untouched"
    (is (= "" (sut/obfuscate-str (sut/make-obfuscator) "")))))

(deftest build-dump-test
  (let [dump (sut/build-dump sample-db "chat-1" all-tools)
        chat (:chat dump)
        msgs (:messages chat)]
    (testing "send-affecting metadata preserved verbatim"
      (is (= "anthropic/claude-sonnet-4-6" (:model chat)))
      (is (= "high" (:variant chat)))
      (is (= :idle (:status chat)))
      (is (= :anthropic (:last-api chat)))
      (is (= 2 (:user-prompt-count chat)))
      (is (= 6 (:message-count chat))))
    (testing "message roles and order preserved"
      (is (= ["user" "assistant" "reason" "tool_call" "tool_call_output" "user"]
             (mapv :role msgs))))
    (testing "tool name/id kept, arguments obfuscated but keys kept"
      (let [tc (:content (nth msgs 3))]
        (is (= "read_file" (:name tc)))
        (is (= "eca__read_file" (:full-name tc)))
        (is (= :native (:origin tc)))
        (is (contains? (:arguments tc) :path))
        (is (not= "/etc/passwd" (get-in tc [:arguments :path])))))
    (testing "reasoning internal id kept but external-id obfuscated"
      (let [r (:content (nth msgs 2))]
        (is (= "r1" (:id r)))
        (is (some? (:external-id r)))
        (is (not= "sig-secret-aBcDeF0123456789aBcDeF0123456789" (:external-id r)))))
    (testing "tool output text obfuscated but error/shape kept"
      (let [out (get-in (nth msgs 4) [:content :output])]
        (is (= false (:error out)))
        (is (not= "root:x:0:0:secret" (-> out :contents first :text)))))
    (testing "duplicate user text shares the same label"
      (is (= (:content (first msgs)) (:content (last msgs)))))
    (testing "no original secret text leaks into the EDN"
      (is (not (string/includes? (sut/dump->edn dump) "secret"))))
    (testing "title is obfuscated in the content"
      (is (not= "Fix the Login Bug!" (:title chat))))
    (testing "live tool-call promises/futures/arguments stripped"
      (let [tc (get-in chat [:tool-calls "t1"])]
        (is (= :completed (:status tc)))
        (is (= "read_file" (:name tc)))
        (is (not (contains? tc :approved?*)))
        (is (not (contains? tc :future-cleanup-complete?*)))
        (is (not (contains? tc :future)))
        (is (not (contains? tc :arguments)))))
    (testing "auth summary exposes presence/expiry but no secrets"
      (let [auth (:auth-summary dump)]
        (is (= #{"anthropic"} (set (keys auth))))
        (is (= :auth/oauth (get-in auth ["anthropic" :type])))
        (is (= 999 (get-in auth ["anthropic" :expires-at])))
        (is (not (string/includes? (pr-str auth) "SECRET")))))
    (testing "available tools listed by name without schemas"
      (is (= #{"read_file" "do-thing"} (set (map :name (:available-tools dump)))))
      (is (not-any? :parameters (:available-tools dump))))
    (testing "model capabilities preserved verbatim"
      (is (= true (get-in dump [:model-capabilities :reason?]))))
    (testing "the dump round-trips as EDN"
      (is (map? (edn/read-string (sut/dump->edn dump)))))))

(deftest default-filepath-test
  (testing "slugifies the title"
    (is (string/includes? (sut/default-filepath {:title "Fix the Login Bug!" :id "abc"})
                          "eca-chat-debug-fix-the-login-bug")))
  (testing "falls back to chat id when there is no title"
    (is (string/includes? (sut/default-filepath {:id "abc-123"})
                          "eca-chat-debug-abc-123")))
  (testing "uses the .edn extension"
    (is (string/ends-with? (sut/default-filepath {:id "x"}) ".edn"))))

(deftest dump-chat!-test
  (testing "writes an EDN file and reports the message count"
    (let [tmp (str (fs/path (fs/temp-dir) (str "eca-debug-test-" (random-uuid) ".edn")))
          {:keys [path message-count error]} (sut/dump-chat! {:db sample-db :chat-id "chat-1"
                                                              :all-tools all-tools :filepath tmp})]
      (is (nil? error))
      (is (= 6 message-count))
      (is (fs/exists? path))
      (is (map? (edn/read-string (slurp path))))
      (fs/delete-if-exists path)))
  (testing "missing chat returns an error"
    (is (:error (sut/dump-chat! {:db sample-db :chat-id "nope" :all-tools []})))))
