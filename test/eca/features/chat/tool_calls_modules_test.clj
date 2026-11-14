(ns eca.features.chat.tool-calls-modules-test
  "Tool approval goes through the :tool/before-call event: :eca/approval sets
   the approval from the config rules, then other modules may change it."
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat.tool-calls :as tc]
   [eca.features.tools :as f.tools]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.modules.hooks :as f.hooks]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private tool-call {:id "call-1" :full-name "eca__test_tool" :arguments {"path" "/tmp/x"}})

(def ^:private all-tools [{:name "test_tool" :full-name "eca__test_tool" :origin :native :server {:name "eca"}}])

(defn- decide [config]
  (with-redefs [f.hooks/trigger-if-matches! (fn [_ _ _ _ _] nil)]
    (#'tc/decide-tool-call-action tool-call all-tools (h/db) config "code" "chat-1")))

(defn- with-module [module f]
  (with-redefs [modules/built-in-modules (constantly (conj (h/built-in-modules-with :eca/approval) module))]
    (f)))

(defn- before-call [priority handler]
  {:id :eca/test-approval
   :event-handlers {:tool/before-call {:priority priority :handler handler}}})

(def ^:private config-by-default
  (fn [by-default] {:toolCall {:approval {:byDefault by-default}}}))

(deftest config-approval-test
  (testing ":eca/approval decides from the config rules"
    (is (match? {:decision :allow :reason {:code :user-config-allow}} (decide (config-by-default "allow"))))
    (is (match? {:decision :ask} (decide (config-by-default "ask"))))
    (is (match? {:decision :deny :reason {:code :user-config-deny}} (decide (config-by-default "deny")))))
  (testing "without :eca/approval, calls ask"
    (is (match? {:decision :ask}
                (decide (assoc (config-by-default "allow") :modules {:eca/approval {:enabled false}})))))
  (testing "the config rules decide twice per call, as before modules: for the log and the deny check, and in :eca/approval"
    (let [calls* (atom 0)
          approval-decision f.tools/approval-decision]
      (with-redefs [f.tools/approval-decision (fn [& args] (swap! calls* inc) (apply approval-decision args))]
        (is (match? {:decision :deny} (decide (config-by-default "deny"))))
        (is (= 2 @calls*))
        (reset! calls* 0)
        (is (match? {:decision :allow} (decide (config-by-default "allow"))))
        (is (= 2 @calls*)))))
  (testing "a call that asks logs no warning: :eca/approval sets no :reason then"
    (let [warnings* (atom [])]
      (with-redefs [logger/warn (fn [& args] (swap! warnings* conj args))]
        (is (match? {:decision :ask} (decide (config-by-default "ask")))))
      (is (= [] @warnings*)))))

(deftest module-approval-test
  (testing "a module running after :eca/approval sees its decision and may change it"
    (let [seen* (atom nil)]
      (with-module (before-call 500 (fn [_ctx {:keys [approval] :as event}]
                                      (reset! seen* (select-keys event [:tool :server :approval :tool-call-id]))
                                      (when (= :ask approval)
                                        {:approval :allow :reason {:code :module-allow :text "Allowed by test"}})))
        #(is (match? {:decision :allow :reason {:code :module-allow :text "Allowed by test"}}
                     (decide (config-by-default "ask")))))
      (is (= {:tool "eca__test_tool" :server "eca" :approval :ask :tool-call-id "call-1"} @seen*))))
  (testing "a deny from the user's config stays deny"
    (with-module (before-call 500 (constantly {:approval :allow}))
      #(is (match? {:decision :deny :reason {:code :user-config-deny}}
                   (decide (config-by-default "deny"))))))
  (testing "a module may deny with its reason"
    (with-module (before-call 500 (constantly {:approval :deny :reason {:code :module-deny :text "No"}}))
      #(is (match? {:decision :deny :reason {:code :module-deny}} (decide (config-by-default "allow"))))))
  (testing "a module's :block rejects the call, with its reason for the model"
    (with-module (before-call 500 (constantly {:block "Secrets inside."}))
      #(is (match? {:decision :deny
                    :tool-call-rejected-by-hook? true
                    :reason {:code :hook-rejected :text "Secrets inside."}}
                   (decide (config-by-default "allow"))))))
  (testing "a module's :stop ends the turn"
    (with-module (before-call 500 (constantly {:stop "Budget reached."}))
      #(is (match? {:decision :deny
                    :stop-turn? true
                    :stop-reason "Budget reached."
                    :stop-hook-name ":eca/test-approval"
                    :reason {:code :hook-stopped}}
                   (decide (config-by-default "allow"))))))
  (testing "a module may change the arguments"
    (with-module (before-call 500 (fn [_ctx {:keys [arguments]}] {:arguments (assoc arguments "path" "/tmp/y")}))
      #(is (match? {:decision :allow :arguments {"path" "/tmp/y"} :arguments-modified? true}
                   (decide (config-by-default "allow"))))))
  (testing "the config rules still deny the arguments a module changes them to"
    (let [config {:toolCall {:approval {:byDefault "allow"
                                        :deny {"eca__test_tool" {:argsMatchers {"path" ["/etc/.*"]}}}}}}]
      (with-module (before-call 500 (fn [_ctx {:keys [arguments]}] {:arguments (assoc arguments "path" "/etc/passwd")}))
        #(is (match? {:decision :deny :reason {:code :user-config-deny}} (decide config))))))
  (testing "a module running before :eca/approval and changing the arguments gets them decided again"
    (let [config {:toolCall {:approval {:byDefault "allow"
                                        :deny {"eca__test_tool" {:argsMatchers {"path" ["/etc/.*"]}}}}}}]
      (with-module (before-call 2000 (fn [_ctx {:keys [arguments]}] {:arguments (assoc arguments "path" "/etc/passwd")}))
        #(is (match? {:decision :deny :reason {:code :user-config-deny}} (decide config))))))
  (testing "changes of the wrong type are ignored instead of breaking the call"
    (with-module (before-call 500 (constantly {:approval "allow"}))
      #(is (match? {:decision :ask} (decide (config-by-default "allow")))
           "an approval that isn't :allow, :ask or :deny asks"))
    (with-module (before-call 500 (constantly {:arguments "rm -rf /"}))
      #(is (match? {:decision :allow :arguments {"path" "/tmp/x"} :arguments-modified? false}
                   (decide (config-by-default "allow")))))
    (with-module (before-call 500 (constantly {:approval :deny :reason "no"}))
      #(is (match? {:decision :deny :reason {:code :user-config-deny}} (decide (config-by-default "allow"))))))
  (testing "a nil stop doesn't stop the turn"
    (with-module (before-call 500 (constantly {:stop nil}))
      #(is (match? {:decision :allow :stop-turn? false} (decide (config-by-default "allow"))))))
  (testing "changed arguments can't escape a config deny"
    (let [config {:toolCall {:approval {:byDefault "allow"
                                        :deny {"eca__test_tool" {:argsMatchers {"path" ["/tmp/x"]}}}}}}]
      (with-module (before-call 2000 (constantly {:arguments {"path" "/tmp/y"}}))
        #(is (match? {:decision :deny} (decide config)))))))
