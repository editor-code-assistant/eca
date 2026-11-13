(ns eca.api.dev-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.api.dev :as dev]
   [eca.features.commands :as f.commands]
   [eca.modules :as modules]
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

(defmacro ^:private with-session [& body]
  `(with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with))]
     (reset! starts 0)
     (modules/start! {:db* (h/db*) :messenger (h/messenger) :metrics (h/metrics)} {})
     ~@body))

(deftest before-the-session-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"session hasn't started" (dev/install! dev-module))))

(deftest install-test
  (with-session
    (testing "install! adds a module and starts it"
      (is (match? {:id :acme/dev :status :active :source "repl:acme/dev" :running? true} (dev/install! dev-module)))
      (is (= 1 @starts))
      (is (= "dev!" (command-text "dev"))))
    (testing "the client gets its tools"
      (is (match? (m/embeds [{:type :module :name "dev" :module "acme/dev"}])
                  (:tool-server-update (h/messages)))))
    (testing "modules lists it, with what it registered"
      (is (match? (m/embeds [{:id :acme/dev :status :active :tools [] :commands []}]) (dev/modules))))
    (testing "installing it again replaces it"
      (dev/install! (assoc dev-module :description "Again."))
      (is (match? (m/embeds [{:id :acme/dev}]) (dev/modules)))
      (is (= "Again." (get-in (some #(when (= :acme/dev (:id %)) %) (modules/statuses {})) [:module :description]))))
    (testing "reload! restarts it"
      (let [before @starts]
        (is (match? {:status :active} (dev/reload! :acme/dev)))
        (is (= (inc before) @starts))))
    (testing "uninstall! removes it"
      (is (nil? (dev/uninstall! :acme/dev)))
      (is (not-any? #(= :acme/dev (:id %)) (dev/modules))))
    (testing "an invalid module is refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"qualified keyword :id" (dev/install! {:id :nope}))))))

(deftest uninstall-file-module-test
  (with-session
    (modules/add-external! "/tmp/file-module.clj" {:id :acme/file})
    (testing "a module from a file only turns off for the session"
      (dev/uninstall! :acme/file)
      (is (match? (m/embeds [{:id :acme/file :status :disabled}]) (dev/modules))))))

(deftest sci-ctx-test
  (with-session
    (testing "only built-in modules get the SCI context"
      (is (some? (dev/sci-ctx {:module-id :eca/core :config {}})))
      (dev/install! dev-module)
      (is (nil? (dev/sci-ctx {:module-id :acme/dev :config {}}))))))
