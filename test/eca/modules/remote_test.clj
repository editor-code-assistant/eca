(ns eca.modules.remote-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.modules :as modules]
   [eca.modules.remote :as m.remote]
   [eca.modules.remote.server :as m.remote.server]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private ask-question-path [:client-capabilities :code-assistant :chat-capabilities :ask-question])

(defn- config
  "ECA's config without the user's files, with `extra` from the client."
  [& [extra]]
  (reset! config/initialization-config* (merge {:pureConfig true} extra))
  (config/clear-cache!)
  (config/all (h/db)))

(defn- fake-start!
  "Stands for eca.modules.remote.server/start!, recording its calls in `calls*`."
  [calls*]
  (fn [components sse-connections*]
    (swap! calls* conj [:start sse-connections*])
    (swap! (:db* components) assoc-in ask-question-path true)
    {:server ::jetty
     :connect-url "https://web.eca.dev?host=h:7777"
     :host "h:7777"
     :token "t"
     :private-host? false}))

(defn- fake-stop! [calls*]
  (fn [rs] (swap! calls* conj [:stop (:server rs)])))

(deftest activation-test
  (testing "off by default, the remote config keeping its default"
    (let [cfg (config)]
      (is (not (modules/active? cfg :eca/remote)))
      (is (= {:enabled false} (:remote cfg)))))
  (testing "remote.enabled turns it on, as it turned on the server before modules"
    (is (modules/active? (config {:remote {:enabled true}}) :eca/remote)))
  (testing "modules.eca/remote.enabled wins"
    (is (not (modules/active? (config {:remote {:enabled true} :modules {:eca/remote {:enabled false}}})
                              :eca/remote)))
    (is (modules/active? (config {:modules {:eca/remote {:enabled true}}}) :eca/remote))))

(deftest server-lifecycle-test
  (let [calls* (atom [])
        sse* (atom #{})
        components {:db* (h/db*) :messenger {:sse-connections* sse*}}]
    (with-redefs [m.remote.server/start! (fake-start! calls*)
                  m.remote.server/stop! (fake-stop! calls*)]
      (try
        (testing "start-server! starts it once, with the messenger's SSE connections, and keeps its details for /remote"
          (is (match? {:connect-url "https://web.eca.dev?host=h:7777"} (m.remote/start-server! components)))
          (m.remote/start-server! components)
          (is (= [[:start sse*]] @calls*))
          (is (match? {:remote-connect-url "https://web.eca.dev?host=h:7777" :remote-token "t" :remote-host "h:7777"}
                      (h/db)))
          (is (true? (get-in (h/db) ask-question-path))))
        (testing "stop-server! stops it and undoes what starting it changed"
          (m.remote/stop-server! components)
          (is (= [:stop ::jetty] (last @calls*)))
          (is (nil? (m.remote/running)))
          (is (nil? (:remote-connect-url (h/db))))
          (is (nil? (get-in (h/db) ask-question-path))))
        (testing "a client that can ask questions itself keeps that"
          (swap! (h/db*) assoc-in ask-question-path true)
          (m.remote/start-server! components)
          (m.remote/stop-server! components)
          (is (true? (get-in (h/db) ask-question-path))))
        (finally
          (m.remote/stop-server! components))))))

(deftest module-test
  (let [calls* (atom [])
        components {:db* (h/db*) :messenger {:sse-connections* (atom #{})} :metrics (h/metrics)}]
    (with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with :eca/remote))
                  m.remote.server/start! (fake-start! calls*)
                  m.remote.server/stop! (fake-stop! calls*)]
      (try
        (modules/start! components {})
        (testing "off by default, nothing starts"
          (is (empty? @calls*)))
        (testing "turning it on for the session starts the server"
          (modules/set-enabled! :eca/remote true)
          (modules/reconcile! components {})
          (is (= :start (ffirst @calls*)))
          (is (some? (m.remote/running))))
        (testing "turning it off stops it"
          (modules/set-enabled! :eca/remote false)
          (modules/reconcile! components {})
          (is (= [:stop ::jetty] (last @calls*)))
          (is (nil? (m.remote/running))))
        (finally
          (modules/stop-all! components)
          (m.remote/stop-server! components))))))
