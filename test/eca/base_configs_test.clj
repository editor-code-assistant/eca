(ns eca.base-configs-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.base-configs :as base-configs]
   [eca.config :as config]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]
   [org.httpkit.server :as http-server]))

(h/reset-components-before-test)

(defn ^:private with-server
  "Calls `f` with the URL of a local server answering each path with its
   response in `responses*`, or a function returning it, recording the
   requests in `requests*`."
  [responses* requests* f]
  (let [server (http-server/run-server
                (fn [{:keys [uri headers]}]
                  (swap! requests* conj {:uri uri :headers headers})
                  (let [response (get @responses* uri {:status 404 :body "not found"})]
                    (if (fn? response) (response) response)))
                {:port 0 :legacy-return-value? false})]
    (try
      (f (str "http://localhost:" (http-server/server-port server)))
      (finally
        (http-server/server-stop! server)))))

(defn ^:private warnings []
  (mapv :message (:show-message (h/messages))))

(defn ^:private session-config!
  "Makes `base-config-entries` the session's `baseConfigs`."
  [base-config-entries]
  (reset! config/initialization-config* {:pureConfig true :baseConfigs base-config-entries})
  (config/clear-cache!))

(deftest sources-test
  (testing "URLs and {url, headers} maps, in order, trimmed, without repeats"
    (is (= [{:url "https://a.example/base.json"}
            {:url "https://b.example/base.json" :headers {"Authorization" "Bearer t0k" "X-Count" "1"}}]
           (base-configs/sources {:baseConfigs [" https://a.example/base.json "
                                                {:url "https://b.example/base.json"
                                                 :headers {"Authorization" "Bearer t0k" :X-Count 1}}
                                                "https://a.example/base.json"]}))))
  (testing "a single URL"
    (is (= [{:url "https://a.example/base.json"}]
           (base-configs/sources {:baseConfigs "https://a.example/base.json"}))))
  (testing "plain HTTP only for localhost"
    (is (= ["http://localhost:5550/base.json" "http://127.0.0.1/base.json"]
           (map :url (base-configs/sources {:baseConfigs ["http://localhost:5550/base.json"
                                                          "http://127.0.0.1/base.json"
                                                          "http://evil.example/base.json"]})))))
  (testing "entries that aren't HTTPS URLs are left out"
    (is (= [] (base-configs/sources {:baseConfigs ["file:///etc/eca.json" "base.json" "not a url"
                                                   {:headers {"A" "b"}} 42]})))))

(deftest display-url-test
  (testing "without user info, query and fragment, which may hold secrets"
    (is (= "https://b.example:8443/base.json?…"
           (base-configs/display-url "https://user:pass@b.example:8443/base.json?token=s3cr3t#top"))))
  (is (= "https://a.example/base.json" (base-configs/display-url "https://a.example/base.json"))))

(deftest refresh-test
  (let [responses* (atom {"/base.json" {:status 200 :body "{\n  // shared defaults\n  \"defaultModel\": \"base/model\"\n}"}})
        requests* (atom [])]
    (with-server
      responses* requests*
      (fn [url]
        (let [base-url (str url "/base.json")
              config {:baseConfigs [{:url base-url :headers {"Authorization" "Bearer t0k"}}]}]
          (session-config! (:baseConfigs config))
          (testing "a fetched config is merged, comments allowed"
            (base-configs/refresh! config (h/messenger))
            (is (= [{"defaultModel" "base/model"}] @config/base-config-layers*))
            (is (= "base/model" (:defaultModel (config/all (h/db)))))
            (is (= "Bearer t0k" (get-in (last @requests*) [:headers "authorization"])))
            (is (= [] (warnings))))
          (testing "a failed fetch keeps the last good copy, warning once"
            (swap! responses* assoc "/base.json" {:status 503 :body "down"})
            (base-configs/refresh! config (h/messenger))
            (base-configs/refresh! config (h/messenger))
            (is (= [{"defaultModel" "base/model"}] @config/base-config-layers*))
            (is (match? [#"Could not refresh the base config http://localhost:\d+/base.json \(HTTP status 503\), using its copy from "]
                        (warnings)))
            (is (string/includes? (base-configs/status-text config) "the last fetch failed: HTTP status 503")))
          (testing "once fetched again, a later failure warns again"
            (swap! responses* assoc "/base.json" {:status 200 :body "{\"defaultModel\": \"base/new\"}"})
            (base-configs/refresh! config (h/messenger))
            (is (= [{"defaultModel" "base/new"}] @config/base-config-layers*))
            (swap! responses* assoc "/base.json" {:status 500 :body "boom"})
            (base-configs/refresh! config (h/messenger))
            (is (= 2 (count (warnings)))))
          (testing "a new session starts with the copy saved on disk, without fetching"
            (let [request-count (count @requests*)]
              (base-configs/reset-state!)
              (is (= [] @config/base-config-layers*))
              (base-configs/load-cached! config)
              (is (= [{"defaultModel" "base/new"}] @config/base-config-layers*))
              (is (= request-count (count @requests*)))
              (is (string/includes? (base-configs/status-text config) "not fetched yet"))))
          (testing "a base config no longer listed is no longer merged"
            (base-configs/refresh! {:baseConfigs []} (h/messenger))
            (is (= [] @config/base-config-layers*))))))))

(deftest refresh-failures-test
  (let [responses* (atom {"/500.json" {:status 500 :body "boom"}
                          "/invalid.json" {:status 200 :body "{not json"}
                          "/array.json" {:status 200 :body "[1, 2]"}})
        requests* (atom [])]
    (with-server
      responses* requests*
      (fn [url]
        (let [config {:baseConfigs (mapv #(str url %) ["/500.json" "/invalid.json" "/array.json"])}]
          (base-configs/refresh! config (h/messenger))
          (base-configs/refresh! config (h/messenger))
          (testing "nothing is merged"
            (is (= [] @config/base-config-layers*)))
          (testing "each failure warns once, saying ECA goes on without it"
            (is (match? [#"/500.json \(HTTP status 500\), ECA goes on without it"
                         #"/invalid.json \(invalid JSON: .+\), ECA goes on without it"
                         #"/array.json \(the response is not a JSON object\), ECA goes on without it"]
                        (warnings)))))))))

(deftest invalid-entries-test
  (testing "an entry that isn't an HTTPS URL is skipped, warning once"
    (base-configs/refresh! {:baseConfigs ["http://evil.example/base.json"]} (h/messenger))
    (base-configs/refresh! {:baseConfigs ["http://evil.example/base.json"]} (h/messenger))
    (is (= ["Skipping the baseConfigs entry http://evil.example/base.json: only https:// URLs are supported (http:// only for localhost)."]
           (warnings)))
    (is (= [] @config/base-config-layers*))))

(deftest on-config-changed-test
  (let [responses* (atom {"/base.json" {:status 200 :body "{\"defaultModel\": \"base/model\"}"}})
        requests* (atom [])]
    (with-server
      responses* requests*
      (fn [url]
        (let [config {:baseConfigs [(str url "/base.json")]}]
          (testing "nothing to do for the first config, or the same list"
            (is (nil? (base-configs/on-config-changed! nil config (h/messenger))))
            (is (nil? (base-configs/on-config-changed! config config (h/messenger)))))
          (testing "a base config added while ECA runs is fetched right away"
            @(base-configs/on-config-changed! {:baseConfigs []} config (h/messenger))
            (is (= [{"defaultModel" "base/model"}] @config/base-config-layers*)))
          (testing "one removed stops being merged"
            @(base-configs/on-config-changed! config {:baseConfigs []} (h/messenger))
            (is (= [] @config/base-config-layers*))))))))

(deftest start-test
  (with-redefs-fn {#'base-configs/start-refresh-loop! (fn [& _])}
    (fn []
      (let [gate (promise)
            responses* (atom {"/base.json" (fn []
                                             (deref gate 5000 nil)
                                             {:status 200 :body "{\"defaultModel\": \"base/fresh\"}"})})
            requests* (atom [])]
        (with-server
          responses* requests*
          (fn [url]
            (let [base-url (str url "/base.json")]
              (session-config! [base-url])
              (testing "with a copy loaded, the session goes on while the fetch updates it"
                (#'base-configs/save-copy! base-url {"defaultModel" "base/cached"} 1000)
                (base-configs/load-cached! (config/all (h/db)))
                (base-configs/start! (h/db*) (h/messenger))
                (is (= "base/cached" (:defaultModel (config/all (h/db)))))
                (deliver gate true)
                (is (= "base/fresh" (loop [tries 50]
                                      (let [model (:defaultModel (config/all (h/db)))]
                                        (if (or (= "base/fresh" model) (zero? tries))
                                          model
                                          (do (Thread/sleep 100) (recur (dec tries)))))))))
              (testing "without a copy, it waits for the fetch"
                (base-configs/reset-state!)
                (swap! responses* assoc "/base.json" (fn []
                                                       (Thread/sleep 300)
                                                       {:status 200 :body "{\"defaultModel\": \"base/first\"}"}))
                (base-configs/start! (h/db*) (h/messenger))
                (is (= "base/first" (:defaultModel (config/all (h/db)))))))))))))

(deftest status-text-test
  (testing "nothing without base configs"
    (is (nil? (base-configs/status-text {:baseConfigs []}))))
  (testing "each base config and how it stands, without its secrets"
    (swap! base-configs/state* assoc "https://a.example/base.json?token=s3cr3t"
           {:config {} :status :stale :fetched-at 0 :error "HTTP status 503"})
    (let [text (base-configs/status-text
                {:baseConfigs [{:url "https://a.example/base.json?token=s3cr3t"
                                :headers {"Authorization" "Bearer s3cr3t"}}
                               "https://b.example/base.json"
                               "http://evil.example/base.json"]})]
      (is (string/starts-with? text "Base configs:\n"))
      (is (re-find #"https://a\.example/base\.json\?…: using its copy from .+, the last fetch failed: HTTP status 503" text))
      (is (string/includes? text "https://b.example/base.json: not fetched yet"))
      (is (string/includes? text "http://evil.example/base.json: skipped, only https:// URLs are supported"))
      (is (not (string/includes? text "s3cr3t"))))))
