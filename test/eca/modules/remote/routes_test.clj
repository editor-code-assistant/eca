(ns eca.modules.remote.routes-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.modules.remote.routes :as m.remote.routes]
   [eca.server :as server]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(defn- app []
  (let [c (h/components)]
    (m.remote.routes/create-handler (assoc c
                                           :config (config/all @(:db* c))
                                           :call-method (partial #'server/call-method c))
                                    {:token "tok" :host* (atom "localhost:7777") :sse-connections* (atom #{})})))

(defn- request [method uri & [body]]
  (cond-> {:request-method method :uri uri :headers {"authorization" "Bearer tok"}}
    body (assoc :body (java.io.ByteArrayInputStream. (.getBytes ^String (json/generate-string body) "UTF-8")))))

(deftest web-client-routes-test
  (swap! (h/db*) assoc-in [:chats "c1"] {:id "c1"
                                         :status :running
                                         :messages [{:role "user" :content [{:type :text :text "hi"}] :content-id "u1"}]})
  (let [handler (app)
        status (fn [& args] (:status (handler (apply request args))))]
    (testing "the routes web.eca.dev calls reach their handlers"
      (is (= 204 (status :post "/api/v1/chats/c1/steer" {:message "x"})))
      (is (= 204 (status :delete "/api/v1/chats/c1/steer")))
      (is (= 204 (status :post "/api/v1/chats/c1/flag" {:contentId "u1" :text "f"})))
      (is (= 204 (status :delete "/api/v1/chats/c1/flag/f1")))
      (is (= 200 (status :post "/api/v1/chats/c1/fork" {:contentId "u1"})))
      (is (= 200 (status :get "/api/v1/jobs")))
      (is (= 200 (status :get "/api/v1/jobs/job-1/output")))
      (is (= 200 (status :post "/api/v1/jobs/job-1/kill"))))
    (testing "other methods on those paths are unknown routes"
      (is (= 404 (status :get "/api/v1/chats/c1/steer")))
      (is (= 404 (status :post "/api/v1/chats/c1/flag/f1")))
      (is (= 404 (status :get "/api/v1/jobs/job-1/kill"))))
    (testing "they need the token"
      (is (= 401 (:status (handler (dissoc (request :get "/api/v1/jobs") :headers))))))))
