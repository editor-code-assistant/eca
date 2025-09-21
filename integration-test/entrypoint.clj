(ns entrypoint
  (:require
   [clojure.test :as t]
   [integration.eca :as eca]
   [llm-mock.server :as llm-mock.server]))

(def namespaces
  '[integration.initialize-test
    integration.chat.openai-test
    integration.chat.anthropic-test
    integration.chat.github-copilot-test
    integration.chat.ollama-test
    integration.chat.custom-provider-test])

(defn timeout [timeout-ms callback]
  (let [fut (future (callback))
        ret (deref fut timeout-ms :timed-out)]
    (when (= ret :timed-out)
      (future-cancel fut))
    ret))

(declare ^:dynamic original-report)

(defn log-tail-report [data]
  (original-report data)
  (when (contains? #{:fail :error} (:type data))
    (println "Integration tests failed!")))

(defmacro with-log-tail-report
  "Execute body with modified test reporting functions that prints log tail on failure."
  [& body]
  `(binding [original-report t/report
             t/report log-tail-report]
     ~@body))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn run-all [binary]
  (alter-var-root #'eca/*eca-binary-path* (constantly binary))
  (apply require namespaces)

  (llm-mock.server/start!)

  (let [timeout-minutes (if (re-find #"(?i)win|mac" (System/getProperty "os.name"))
                          10 ;; win and mac ci runs take longer
                          5)
        test-results (timeout (* timeout-minutes 60 1000)
                              #(with-log-tail-report
                                 (apply t/run-tests namespaces)))]

    (llm-mock.server/stop!)

    (when (= test-results :timed-out)
      (println)
      (println (format "Timeout after %d minutes running integration tests!" timeout-minutes))
      (System/exit 1))

    (let [{:keys [fail error]} test-results]
      (System/exit (+ fail error)))))
