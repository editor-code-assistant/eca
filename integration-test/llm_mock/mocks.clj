(ns llm-mock.mocks)

(def ^:dynamic *case* nil)

(defn set-case! [case]
  (alter-var-root #'*case* (constantly case)))

(def ^:dynamic *last-req-body* nil)

(defn set-last-req-body! [body]
  (alter-var-root #'*last-req-body* (constantly body)))

(def chat-title-generator-str "Title generator")
