(ns eca.nrepl
  (:require
   [borkdude.dynaload :refer [dynaload]]
   [eca.logger :as logger]))

(set! *warn-on-reflection* true)

(def ^:private start-server (dynaload 'nrepl.server/start-server))

(def ^:private cider-nrepl-handler (dynaload 'cider.nrepl/cider-nrepl-handler))

(defn ^:private repl-port []
  (:port (start-server :handler cider-nrepl-handler)))

(defn setup-nrepl []
  (try
    (when-let [port (repl-port)]
      (logger/info "[nrepl-debug] nrepl server started on port" port)
      port)
    (catch Throwable _
      nil)))
