(ns eca.modules.repl.dev-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.commands :as f.commands]
   [eca.modules :as modules]
   [eca.modules.events :as events]
   [eca.modules.repl.dev :as m.repl.dev]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private starts (atom 0))

(def ^:private dev-module
  {:id :acme/dev
   :description "From the REPL."
   :start (fn [_] (swap! starts inc) {})
   :tools {"ping" {:description "Ping." :parameters {:type "object"} :handler (fn [_ _] "pong")}}
   :commands {"dev" {:handler (fn [_ _] "dev!")}}})

(defn- command-text [command]
  (get-in (f.commands/handle-command! command [] {:chat-id "chat-1" :config {} :db* (h/db*)})
          [:chats "chat-1" :messages 0 :content 0 :text]))

(defmacro ^:private in-repl
  "Runs body as an evaluation of eca/repl's nREPL does."
  [& body]
  `(with-bindings {#'m.repl.dev/*connection* {:send! (fn [_# _#]) :traced (atom {})}}
     ~@body))

(defmacro ^:private with-session [& body]
  `(with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with))]
     (reset! starts 0)
     (modules/start! {:db* (h/db*) :messenger (h/messenger) :metrics (h/metrics)} {})
     ~@body))

(deftest outside-the-repl-test
  (with-session
    (testing "it works only in an evaluation of eca/repl's nREPL, so modules can't depend on it"
      (doseq [f [#(m.repl.dev/ctx) #(m.repl.dev/modules) #(m.repl.dev/install! dev-module)
                 #(m.repl.dev/db) #(m.repl.dev/trace! "chat-1") #(m.repl.dev/untrace!)]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"eca.repl works only in the nREPL of the eca/repl module"
                              (f)))))))

(deftest before-the-session-test
  (in-repl
   (is (thrown-with-msg? clojure.lang.ExceptionInfo #"session hasn't started" (m.repl.dev/install! dev-module)))
   (is (thrown-with-msg? clojure.lang.ExceptionInfo #"session hasn't started" (m.repl.dev/ctx)))))

(deftest install-test
  (with-session
    (in-repl
     (testing "install! adds a module and starts it"
       (is (match? {:id :acme/dev :status :active :source "repl:acme/dev" :running? true}
                   (m.repl.dev/install! dev-module)))
       (is (= 1 @starts))
       (is (= "dev!" (command-text "dev"))))
     (testing "the client gets its tools"
       (is (match? (m/embeds [{:type :module :name "dev" :module "acme/dev"}])
                   (:tool-server-update (h/messages)))))
     (testing "modules lists it"
       (is (match? (m/embeds [{:id :acme/dev :status :active :running? true}]) (m.repl.dev/modules))))
     (testing "installing it again replaces it"
       (m.repl.dev/install! (assoc dev-module :description "Again."))
       (is (= "Again." (get-in (some #(when (= :acme/dev (:id %)) %) (modules/statuses {})) [:module :description]))))
     (testing "reload! restarts it"
       (let [before @starts]
         (is (match? {:status :active} (m.repl.dev/reload! :acme/dev)))
         (is (= (inc before) @starts))))
     (testing "uninstall! removes it"
       (is (nil? (m.repl.dev/uninstall! :acme/dev)))
       (is (not-any? #(= :acme/dev (:id %)) (m.repl.dev/modules))))
     (testing "an invalid module is refused"
       (is (thrown-with-msg? clojure.lang.ExceptionInfo #"qualified keyword :id" (m.repl.dev/install! {:id :nope})))))))

(deftest uninstall-file-module-test
  (with-session
    (in-repl
     (modules/add-external! "/tmp/file-module.clj" {:id :acme/file})
     (testing "a module from a file only turns off for the session"
       (m.repl.dev/uninstall! :acme/file)
       (is (match? (m/embeds [{:id :acme/file :status :disabled}]) (m.repl.dev/modules)))))))

(deftest ctx-test
  (with-session
    (in-repl
     (swap! (h/db*) assoc-in [:chats "chat-1"] {:id "chat-1"})
     (is (match? {:db* (h/db*) :messenger (h/messenger) :module-id m/absent :chat-id m/absent} (m.repl.dev/ctx)))
     (is (match? {:module-id :acme/x :chat-id "chat-1"} (m.repl.dev/ctx {:module :acme/x :chat-id "chat-1"})))
     (is (thrown-with-msg? clojure.lang.ExceptionInfo #"There's no chat" (m.repl.dev/ctx {:chat-id "nope"})))
     (is (thrown-with-msg? clojure.lang.ExceptionInfo #"qualified keyword" (m.repl.dev/ctx {:module "acme/x"}))))))

(deftest state-test
  (with-session
    (in-repl
     (swap! (h/db*) assoc-in [:chats "chat-1"] {:id "chat-1"})
     (is (= {:id "chat-1"} (m.repl.dev/chat-raw "chat-1")))
     (is (nil? (m.repl.dev/chat-raw "nope")))
     (is (= (h/db) (m.repl.dev/db)))
     (testing "config-raw is the config before :config/resolve"
       (is (= (config/merged-layers {:workspace-folders (:workspace-folders (h/db))})
              (m.repl.dev/config-raw)))))))

(deftest trace-line-test
  (let [trace-line #'m.repl.dev/trace-line]
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
