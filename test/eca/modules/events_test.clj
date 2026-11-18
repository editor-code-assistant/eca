(ns eca.modules.events-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.modules :as modules]
   [eca.modules.core :as m.core]
   [eca.modules.events :as events]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private test-events
  {:test/notified {:kind :notify :accepts {:additional-context "" :stop ""}}
   :test/plain {:kind :notify}
   :test/changed {:kind :transform :accepts {:text "" :block "" :stop "" :additional-context ""}}
   :test/answered {:kind :handle}})

(defmacro ^:private with-modules
  "Runs body with `modules` as the built-in ones, and the test events as
   ECA's."
  [modules & body]
  `(with-redefs [events/core-events (merge events/core-events test-events)
                 modules/built-in-modules (constantly (into [m.core/module] ~modules))]
     ~@body))

(defn- prioritized
  "A handler entry with its own `priority`."
  [priority handler]
  {:priority priority :handler handler})

(deftest core-events-test
  (testing "the events ECA emits"
    (is (match? {:tool/before-call {:kind :transform}
                 :chat/compact {:kind :handle}
                 :session/started {:kind :notify}}
                events/core-events))
    (is (= 27 (count events/core-events)))))

(deftest transform-test
  (let [calls* (atom [])
        handler (fn [id changes] (fn [_ctx event] (swap! calls* conj [id (:text event)]) changes))]
    (with-modules [{:id :acme/first
                    :event-handlers {:test/changed (prioritized 10 (handler :first {:text "b" :additional-context "one"}))}}
                   {:id :acme/second
                    :event-handlers {:test/changed (handler :second {:additional-context "two" :ignored true})}}
                   {:id :acme/last
                    :event-handlers {:test/changed (prioritized -1000 (handler :last nil))}}]
      (testing "handlers run by priority and see earlier changes; context adds up; unaccepted keys are dropped"
        (is (= {:text "b" :additional-context "one\ntwo"
                ::events/contexts [{:module :acme/first :content "one"} {:module :acme/second :content "two"}]}
               (events/emit! {} :test/changed {:text "a"})))
        (is (= [[:first "a"] [:second "b"] [:last "b"]] @calls*)))))
  (testing ":block ends the chain"
    (let [calls* (atom [])]
      (with-modules [{:id :acme/blocker
                      :event-handlers {:test/changed (prioritized 5 (fn [_ _] (swap! calls* conj :blocker) {:block "no"}))}}
                     {:id :acme/after
                      :event-handlers {:test/changed (fn [_ _] (swap! calls* conj :after) nil)}}]
        (is (= {:text "a" :block "no" ::events/ended-by :acme/blocker} (events/emit! {} :test/changed {:text "a"})))
        (is (= [:blocker] @calls*)))))
  (testing "a handler's :priority wins over its module's, modules.<id>.priority; a vector holds several handlers"
    (let [calls* (atom [])]
      (with-modules [{:id :acme/a
                      :event-handlers {:test/changed (fn [_ _] (swap! calls* conj :a) nil)}}
                     {:id :acme/b
                      :event-handlers {:test/changed [(prioritized 200 (fn [_ _] (swap! calls* conj :b1) nil))
                                                      (fn [_ _] (swap! calls* conj :b2) nil)]}}]
        (events/emit! {:config {:modules {:acme/a {:priority 100}}}} :test/changed {})
        (is (= [:b1 :a :b2] @calls*)))))
  (testing "external handlers run before the last built-in ones, in their own order among themselves"
    (let [calls* (atom [])
          handler (fn [id] (fn [_ _] (swap! calls* conj id) nil))]
      (with-modules [{:id :eca/last :event-handlers {:test/changed (prioritized -1000 (handler :built-in-last))}}]
        (modules/add-external! "/tmp/acme-low.clj" {:id :acme/low
                                                    :event-handlers {:test/changed (prioritized -1000 (handler :low))}})
        (modules/add-external! "/tmp/acme-lower.clj" {:id :acme/lower
                                                      :event-handlers {:test/changed (prioritized -2000 (handler :lower))}})
        (modules/add-external! "/tmp/acme-mid.clj" {:id :acme/mid
                                                    :event-handlers {:test/changed (prioritized -950 (handler :mid))}})
        (events/emit! {} :test/changed {})
        (is (= [:mid :low :lower :built-in-last] @calls*)))))
  (testing "handlers get the caller's ctx plus their module id"
    (let [ctx* (atom nil)]
      (with-modules [{:id :acme/ctx :event-handlers {:test/changed (fn [ctx _] (reset! ctx* ctx) nil)}}]
        (events/emit! {:chat-id "c1"} :test/changed {})
        (is (= {:chat-id "c1" :module-id :acme/ctx} @ctx*))))))

(deftest notify-test
  (with-modules [{:id :acme/a :event-handlers {:test/notified (fn [_ _] {:additional-context "x" :text "ignored"})}}]
    (is (= {:n 1 :additional-context "x" ::events/contexts [{:module :acme/a :content "x"}]}
           (events/emit! {} :test/notified {:n 1}))))
  (testing "a notify event that accepts nothing can't be changed or stopped by a handler"
    (let [seen* (atom [])]
      (with-modules [{:id :acme/a
                      :event-handlers {:test/plain (prioritized 10 (fn [_ e]
                                                                     (swap! seen* conj [:a (:n e)])
                                                                     {:n 2 :stop "no" :block "no"}))}}
                     {:id :acme/b
                      :event-handlers {:test/plain (fn [_ e] (swap! seen* conj [:b (:n e)]) nil)}}]
        (is (= {:n 1} (events/emit! {} :test/plain {:n 1})))
        (is (= [[:a 1] [:b 1]] @seen*) "every handler is notified, with the event as it was")))))

(def ^:private seen* (atom nil))

(deftest contexts-test
  (with-modules [{:id :acme/first
                  :event-handlers {:test/changed (prioritized 10 (constantly {:additional-context {:text "one" :data {:n 1}}}))}}
                 {:id :acme/second
                  :event-handlers {:test/changed (prioritized 5 (constantly {:additional-context "two"}))}}
                 {:id :acme/third
                  :event-handlers {:test/changed (prioritized -10 (constantly {:additional-context {:text "three"
                                                                                                    :data {:f (fn [])}}}))}}
                 {:id :acme/blank
                  :event-handlers {:test/changed (prioritized -20 (fn [_ event]
                                                                    (reset! seen* event)
                                                                    {:additional-context {:text "  " :data {:n 4}}}))}}]
    (let [event (events/emit! {} :test/changed {})]
      (testing "the contexts join, a map giving its :text"
        (is (= "one\ntwo\nthree" (:additional-context event))))
      (testing "and each module's is kept, with its :data; data that isn't plain data is left out"
        (is (= [{:module :acme/first :content "one" :data {:n 1}}
                {:module :acme/second :content "two"}
                {:module :acme/third :content "three"}]
               (::events/contexts event))))
      (testing "on the result only: handlers see the joined text"
        (is (= {:additional-context "one\ntwo\nthree"} @seen*)))))
  (testing "an event that takes no context gets none"
    (with-modules [{:id :acme/a :event-handlers {:test/plain (constantly {:additional-context "x"})}}]
      (is (= {} (events/emit! {} :test/plain {}))))))

(deftest falsy-stop-and-block-test
  (testing "a nil or false :stop or :block is no change: the chain goes on"
    (let [calls* (atom [])]
      (with-modules [{:id :acme/first
                      :event-handlers {:test/changed (prioritized 10 (fn [_ _]
                                                                       (swap! calls* conj :first)
                                                                       {:stop nil :block false :text "b"}))}}
                     {:id :acme/second
                      :event-handlers {:test/changed (fn [_ _] (swap! calls* conj :second) nil)}}]
        (is (= {:text "b"} (events/emit! {} :test/changed {:text "a"})))
        (is (= [:first :second] @calls*))))))

(deftest handle-test
  (testing "the first non-nil answer wins; the rest aren't asked"
    (let [calls* (atom [])]
      (with-modules [{:id :acme/passes
                      :event-handlers {:test/answered (prioritized 10 (fn [_ _] (swap! calls* conj :passes) nil))}}
                     {:id :acme/answers
                      :event-handlers {:test/answered (prioritized 0 (fn [_ _] (swap! calls* conj :answers) {:title "t"}))}}
                     {:id :acme/never
                      :event-handlers {:test/answered (prioritized -10 (fn [_ _] (swap! calls* conj :never) {:title "n"}))}}]
        (is (= {:title "t"} (events/emit! {} :test/answered {})))
        (is (= [:passes :answers] @calls*)))))
  (testing "a failing handler counts as nil: the next one answers"
    (with-modules [{:id :acme/fails
                    :event-handlers {:test/answered (prioritized 10 (fn [_ _] (throw (ex-info "boom" {}))))}}
                   {:id :acme/answers :event-handlers {:test/answered (constantly :answer)}}]
      (is (= :answer (events/emit! {} :test/answered {})))
      (is (= 1 (modules/failures :acme/fails)))))
  (testing "no handler: nil"
    (with-modules []
      (is (nil? (events/emit! {} :test/answered {}))))))

(deftest match-test
  (let [seen* (atom [])]
    (with-modules [{:id :acme/m
                    :event-handlers {:test/changed [{:match {:tool "eca__grep"}
                                                     :handler (fn [_ e] (swap! seen* conj [:value (:tool e)]) nil)}
                                                    {:match {:tool #{"eca__grep" "eca__read_file"}}
                                                     :handler (fn [_ e] (swap! seen* conj [:set (:tool e)]) nil)}
                                                    {:match {:tool #"eca__(write|edit)_file"}
                                                     :handler (fn [_ e] (swap! seen* conj [:regex (:tool e)]) nil)}]}}]
      (doseq [tool ["eca__grep" "eca__read_file" "eca__edit_file" "jira__issue"]]
        (events/emit! {} :test/changed {:tool tool}))
      (is (= [[:value "eca__grep"] [:set "eca__grep"] [:set "eca__read_file"] [:regex "eca__edit_file"]]
             @seen*)))))

(deftest external-handlers-test
  (testing "external handlers time out after their :timeout-ms and count as nil"
    (with-modules []
      (modules/add-external! "/tmp/slow.clj"
                             {:id :acme/slow
                              :event-handlers {:test/changed {:timeout-ms 50
                                                              :handler (fn [_ _] (Thread/sleep 5000) {:text "late"})}}})
      (is (= {:text "a"} (events/emit! {} :test/changed {:text "a"})))
      (is (match? [{:message "Timed out after 50 ms"}] (modules/errors :acme/slow)))))
  (testing "a non-map change is ignored"
    (with-modules [{:id :acme/odd :event-handlers {:test/changed (constantly "a string")}}]
      (is (= {:text "a"} (events/emit! {} :test/changed {:text "a"})))))
  (testing "an external handler may take a compaction's request, add a context without its label and name who acted"
    (with-modules []
      (modules/add-external! "/tmp/acme-guard.clj"
                             {:id :acme/guard
                              :event-handlers {:test/changed {:compaction? true
                                                              :handler (constantly {:additional-context {:text "raw" :label? false}
                                                                                    :stop "halt"
                                                                                    :by "acme's guard"})}}})
      (let [event (events/emit! {} :test/changed {:compaction? true})]
        (is (match? {:stop "halt" ::events/ended-by :acme/guard ::events/by "acme's guard"} event))
        (is (= [{:content "raw"}] (::events/contexts event))))))
  (testing "the functions in an external module's answer to a handle event are guarded like its handler"
    (with-modules []
      (modules/add-external! "/tmp/acme-compact.clj"
                             {:id :acme/compact
                              :event-handlers {:test/answered (constantly {:request {:prompt (fn [] (throw (ex-info "boom" {})))
                                                                                     :done? (constantly true)}})}})
      (let [{{:keys [prompt done?]} :request} (events/emit! {} :test/answered {})]
        (is (true? (done?)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom" (prompt)))
        (is (= 1 (modules/failures :acme/compact)))
        (is (match? [{:what "answer to :test/answered :request :prompt" :message "boom"}]
                    (modules/errors :acme/compact)))))))

(deftest unknown-events-test
  (testing "an event that isn't one of ECA's works as a notify event that accepts any change"
    (with-modules [{:id :acme/custom :event-handlers {:acme/custom (constantly {:x 1})}}]
      (is (= {:x 1} (events/emit! {} :acme/custom {}))))))
