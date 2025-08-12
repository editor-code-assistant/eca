(ns integration.fixture
  (:require
   [clojure.java.io :as io]
   [integration.helper :as h]))

(def default-init-options {:pureConfig true})

(defn initialize-request
  ([]
   (initialize-request {:initializationOptions default-init-options}))
  ([params]
   (initialize-request params [{:name "sample-test"
                                :uri (h/file->uri (io/file h/default-root-project-path))}]))
  ([params workspace-folders]
   [:initialize
    (merge (if workspace-folders {:workspace-folders workspace-folders} {})
           params)]))

(defn initialized-notification []
  [:initialized {}])

(defn shutdown-request
  []
  [:shutdown {}])

(defn exit-notification []
  [:exit {}])
