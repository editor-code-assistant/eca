(ns eca.modules.events-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.core :as f.core]
   [eca.modules :as modules]
   [eca.modules.events :as events]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private test-events
  {:test/notified {:kind :notify :accepts {:additional-context "" :stop ""}}
   :test/plain {:kind :notify}
   :test/changed {:kind :transform :accepts {:text "" :block "" :stop "" :additional-context ""}}
   :test/answered {:kind :handle}})

(defmacro ^:private with-modules [modules & body]
  `(with-redefs [modules/built-in-modules
                 (constantly (into [f.core/module {:id :test/events :defines {:events test-events}}] ~modules))]
     ~@body))

(deftest core-events-test
  (testing ":eca/core defines the core events"
    (is (match? {:tool/before-call {:kind :transform :module :eca/core}
                 :chat/compact {:kind :handle}
                 :session/started {:kind :notify}}
                (events/definitions {})))
    (is (= 21 (count events/core-events)))))

(deftest transform-test
  (let [calls* (atom [])
        handler (fn [id changes] (fn [_ctx event] (swap! calls* conj [id (:text event)]) changes))]
    (with-modules [{:id :acme/first :priority 10
                    :event-handlers {:test/changed (handler :first {:text "b" :additional-context "one"})}}
                   {:id :acme/second
                    :event-handlers {:test/changed (handler :second {:additional-context "two" :ignored true})}}
                   {:id :acme/last :priority -1000
                    :event-handlers {:test/changed (handler :last nil)}}]
      (testing "handlers run by priority and see earlier changes; context adds up; unaccepted keys are dropped"
        (is (= {:text "b" :additional-context "one\ntwo"}
               (events/emit! {} :test/changed {:text "a"})))
        (is (= [[:first "a"] [:second "b"] [:last "b"]] @calls*)))))
  (testing ":block ends the chain"
    (let [calls* (atom [])]
      (with-modules [{:id :acme/blocker :priority 5
                      :event-handlers {:test/changed (fn [_ _] (swap! calls* conj :blocker) {:block "no"})}}
                     {:id :acme/after
                      :event-handlers {:test/changed (fn [_ _] (swap! calls* conj :after) nil)}}]
        (is (= {:text "a" :block "no" ::events/ended-by :acme/blocker} (events/emit! {} :test/changed {:text "a"})))
        (is (= [:blocker] @calls*)))))
  (testing "entry priority overrides the module's; a vector holds several handlers"
    (let [calls* (atom [])]
      (with-modules [{:id :acme/a :priority 100
                      :event-handlers {:test/changed (fn [_ _] (swap! calls* conj :a) nil)}}
                     {:id :acme/b
                      :event-handlers {:test/changed [{:priority 200 :handler (fn [_ _] (swap! calls* conj :b1) nil)}
                                                      (fn [_ _] (swap! calls* conj :b2) nil)]}}]
        (events/emit! {} :test/changed {})
        (is (= [:b1 :a :b2] @calls*)))))
  (testing "handlers get the caller's ctx plus their module id"
    (let [ctx* (atom nil)]
      (with-modules [{:id :acme/ctx :event-handlers {:test/changed (fn [ctx _] (reset! ctx* ctx) nil)}}]
        (events/emit! {:chat-id "c1"} :test/changed {})
        (is (= {:chat-id "c1" :module-id :acme/ctx} @ctx*))))))

(deftest notify-test
  (with-modules [{:id :acme/a :event-handlers {:test/notified (fn [_ _] {:additional-context "x" :text "ignored"})}}]
    (is (= {:n 1 :additional-context "x"} (events/emit! {} :test/notified {:n 1}))))
  (testing "a notify event that accepts nothing can't be changed or stopped by a handler"
    (let [seen* (atom [])]
      (with-modules [{:id :acme/a :priority 10
                      :event-handlers {:test/plain (fn [_ e] (swap! seen* conj [:a (:n e)]) {:n 2 :stop "no" :block "no"})}}
                     {:id :acme/b
                      :event-handlers {:test/plain (fn [_ e] (swap! seen* conj [:b (:n e)]) nil)}}]
        (is (= {:n 1} (events/emit! {} :test/plain {:n 1})))
        (is (= [[:a 1] [:b 1]] @seen*) "every handler is notified, with the event as it was")))))

(deftest falsy-stop-and-block-test
  (testing "a nil or false :stop or :block is no change: the chain goes on"
    (let [calls* (atom [])]
      (with-modules [{:id :acme/first :priority 10
                      :event-handlers {:test/changed (fn [_ _] (swap! calls* conj :first) {:stop nil :block false :text "b"})}}
                     {:id :acme/second
                      :event-handlers {:test/changed (fn [_ _] (swap! calls* conj :second) nil)}}]
        (is (= {:text "b"} (events/emit! {} :test/changed {:text "a"})))
        (is (= [:first :second] @calls*))))))

(deftest handle-test
  (testing "the first non-nil answer wins; the rest aren't asked"
    (let [calls* (atom [])]
      (with-modules [{:id :acme/passes :priority 10
                      :event-handlers {:test/answered (fn [_ _] (swap! calls* conj :passes) nil)}}
                     {:id :acme/answers :priority 0
                      :event-handlers {:test/answered (fn [_ _] (swap! calls* conj :answers) {:title "t"})}}
                     {:id :acme/never :priority -10
                      :event-handlers {:test/answered (fn [_ _] (swap! calls* conj :never) {:title "n"})}}]
        (is (= {:title "t"} (events/emit! {} :test/answered {})))
        (is (= [:passes :answers] @calls*)))))
  (testing "a failing handler counts as nil: the next one answers"
    (with-modules [{:id :acme/fails :priority 10
                    :event-handlers {:test/answered (fn [_ _] (throw (ex-info "boom" {})))}}
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
      (is (= {:text "a"} (events/emit! {} :test/changed {:text "a"}))))))

(deftest undefined-events-test
  (testing "an event no module defines works as a notify event that accepts any change"
    (with-modules [{:id :acme/custom :event-handlers {:acme/custom (constantly {:x 1})}}]
      (is (= {:x 1} (events/emit! {} :acme/custom {}))))))
