(ns integration.eca
  (:require
   [babashka.process :as p]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.test :refer [use-fixtures]]
   [integration.client :as client]))

(def ^:dynamic *eca-binary-path* nil)
(def ^:dynamic *eca-process* nil)
(def ^:dynamic *mock-client* nil)

(defn start-server
  ([binary]
   (start-server binary []))
  ([binary args]
   (p/process (into [(.getCanonicalPath (io/file binary)) "server" "--log-level" "debug"] args)
              {:dir "integration-test/sample-test/"})))

(defn start-process! []
  (let [server (start-server *eca-binary-path*)
        client (client/client (:in server) (:out server))]
    (client/start client nil)
    (async/go-loop []
      (when-let [log (async/<! (:log-ch client))]
        (println log)
        (recur)))
    (alter-var-root #'*eca-process* (constantly server))
    (alter-var-root #'*mock-client* (constantly client))))

(defn clean! []
  (flush)
  (some-> *mock-client* client/shutdown)
  (some-> *eca-process* deref) ;; wait for shutdown of client to shutdown server
  (alter-var-root #'*eca-process* (constantly nil))
  (alter-var-root #'*mock-client* (constantly nil)))

(defn clean-after-test []
  (use-fixtures :each (fn [f] (clean!) (f)))
  (use-fixtures :once (fn [f] (f) (clean!))))

(defn notify! [[method body]]
  (client/send-notification *mock-client* method body))

(defn request! [[method body]]
  (client/request-and-await-server-response! *mock-client* method body))

(defn client-awaits-server-notification [method]
  (client/await-server-notification *mock-client* method))

(defn client-awaits-server-request [method]
  (client/await-server-request *mock-client* method))

(defn mock-response [method resp]
  (client/mock-response *mock-client* method resp))
