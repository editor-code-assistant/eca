(ns eca.features.modules-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.core :as f.core]
   [eca.features.modules :as f.modules]
   [eca.modules :as modules]
   [eca.modules.loader :as loader]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private test-modules
  [f.core/module
   {:id :eca/sample
    :description "A sample module."
    :config-spec {:sampleUrl {:type :string :default "http://sample"}}
    :tools {"lookup" {:description "Look up." :parameters {:type "object"} :handler (fn [_ _] "ok")}}
    :commands {"sample" {:description "Sample." :handler (fn [_ _] "ok")}}
    :event-handlers {:tool/before-call (fn [_ _] nil)}}
   {:id :eca/needs-sample :requires #{:eca/sample}}
   {:id :eca/optional :description "Off by default." :default-enabled? false}])

(defn- chat-ctx []
  {:db* (h/db*) :messenger (h/messenger) :metrics (h/metrics) :config (config/all (h/db))})

(deftest parse-id-test
  (is (= :eca/mcp (f.modules/parse-id "eca/mcp")))
  (is (= :eca/mcp (f.modules/parse-id ":eca/mcp")))
  (is (= :eca-plugins/jira (f.modules/parse-id " eca-plugins/jira ")))
  (is (nil? (f.modules/parse-id "mcp")))
  (is (nil? (f.modules/parse-id nil))))

(deftest modules-text-test
  (reset! config/initialization-config* {:pureConfig true})
  (config/clear-cache!)
  (with-redefs [modules/built-in-modules (constantly test-modules)]
    (let [text (f.modules/handle! [] (chat-ctx))]
      (testing "active modules come first, with what they contribute"
        (is (string/starts-with? text "## Modules (3 active)"))
        (is (string/includes? text "✅ `:eca/sample` — A sample module."))
        (is (string/includes? text "tools `eca__lookup`"))
        (is (string/includes? text "commands `/sample`"))
        (is (string/includes? text "handles `:tool/before-call`"))
        (is (string/includes? text "config `sampleUrl`")))
      (testing "then the others, with why"
        (is (string/includes? text "⏸️ `:eca/optional` — Off by default."))
        (is (string/includes? text "disabled: Off by default"))))))

(deftest enable-disable-test
  (reset! config/initialization-config* {:pureConfig true})
  (config/clear-cache!)
  (with-redefs [modules/built-in-modules (constantly test-modules)]
    (testing "disable takes effect at once, and says which dependents went inactive"
      (is (= "Disabled `:eca/sample` for this session.\nAlso inactive now: `:eca/needs-sample`."
             (f.modules/handle! ["disable" "eca/sample"] (chat-ctx))))
      (is (not (modules/active? (config/all (h/db)) :eca/sample)))
      (is (nil? (:sampleUrl (config/all (h/db))))))
    (testing "enable"
      (is (= "Enabled `:eca/sample` for this session."
             (f.modules/handle! ["enable" "eca/sample"] (chat-ctx))))
      (is (= "http://sample" (:sampleUrl (config/all (h/db)))))
      (is (= "Enabled `:eca/optional` for this session."
             (f.modules/handle! ["enable" ":eca/optional"] (chat-ctx)))))
    (testing "reload"
      (is (= "Reloaded `:eca/sample`." (f.modules/handle! ["reload" "eca/sample"] (chat-ctx)))))
    (testing "errors"
      (is (= "`:eca/core` can't be disabled." (f.modules/handle! ["disable" "eca/core"] (chat-ctx))))
      (is (= "No module `:eca/nope`. `/modules` lists them." (f.modules/handle! ["enable" "eca/nope"] (chat-ctx))))
      (is (match? #"Which module\?" (f.modules/handle! ["enable"] (chat-ctx))))
      (is (match? #"Unknown action `frob`" (f.modules/handle! ["frob" "eca/sample"] (chat-ctx)))))))

(deftest external-modules-test
  (reset! config/initialization-config* {:pureConfig true})
  (config/clear-cache!)
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:prefix "eca-modules-cmd"})))
        good (str (fs/path dir "good.clj"))
        broken (str (fs/path dir "broken.clj"))]
    (try
      (spit good "(ns acme.good) (defonce loads (atom 0)) (swap! loads inc)
                  (def module {:id :acme/good :version \"1.0.0\" :description (str \"Load \" @loads)})")
      (spit broken "(ns acme.broken) (def module (nope))")
      (loader/set-cli-paths! [good broken])
      (with-redefs [modules/built-in-modules (constantly test-modules)]
        (loader/sync! (config/all (h/db)) (h/db))
        (let [text (f.modules/handle! [] (chat-ctx))]
          (testing "an external module shows where it comes from"
            (is (string/includes? text "✅ `:acme/good` 1.0.0 — Load 1"))
            (is (string/includes? text (str "from `" good "`"))))
          (testing "a module that didn't load shows its file and why"
            (is (string/includes? text "❌ `broken.clj`"))
            (is (string/includes? text "failed: Failed to load: Unable to resolve symbol: nope"))))
        (testing "reload evaluates an external module again"
          (is (= "Reloaded `:acme/good`." (f.modules/handle! ["reload" "acme/good"] (chat-ctx))))
          (is (string/includes? (f.modules/handle! [] (chat-ctx)) "— Load 2"))))
      (finally
        (fs/delete-tree dir)))))

(deftest config-problems-test
  (with-redefs [modules/built-in-modules (constantly test-modules)]
    (is (= [] (f.modules/config-problems {:sampleUrl "http://x"})))
    (is (= ["`sampleUrl` should be a string, got a number"] (f.modules/config-problems {:sampleUrl 1})))))
