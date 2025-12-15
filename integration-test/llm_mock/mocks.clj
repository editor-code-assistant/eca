(ns llm-mock.mocks)

(def ^:dynamic *case* nil)

(defn set-case! [case]
  (alter-var-root #'*case* (constantly case)))

(defonce ^:private req-bodies* (atom {}))
(defonce ^:private raw-messages* (atom {}))

(defn set-req-body! [mock-case-id body]
  (swap! req-bodies* assoc mock-case-id body))

(defn get-req-body [mock-case-id]
  (get @req-bodies* mock-case-id))

(defn set-raw-messages! [mock-case-id messages]
  (swap! raw-messages* assoc mock-case-id messages))

(defn get-raw-messages [mock-case-id]
  (get @raw-messages* mock-case-id))

(defn clean-req-bodies! []
  (reset! req-bodies* {})
  (reset! raw-messages* {}))

(def chat-title-generator-str "Title generator")
