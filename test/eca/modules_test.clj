(ns eca.modules-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.core :as f.core]
   [eca.modules :as modules]
   [eca.modules.built-in :as built-in]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private core
  "`:eca/core` without its config keys, so tests see only their modules' ones."
  (dissoc f.core/module :config-spec))

(defmacro ^:private with-built-ins
  "Runs body with `modules` as the built-in modules, next to :eca/core."
  [modules & body]
  `(with-redefs [modules/built-in-modules (constantly (into [core] ~modules))]
     ~@body))

(defn- active-ids [config]
  (mapv :id (modules/active config)))

(defn- status-of [config id]
  (some #(when (= id (:id %)) %) (modules/statuses config)))

(deftest built-in-modules-test
  (testing "every built-in module is valid"
    (doseq [module built-in/modules]
      (is (= {:errors [] :warnings []} (modules/problems module {:built-in? true}))
          (str (:id module)))))
  (testing "built-in ids are unique and under :eca/"
    (is (apply distinct? (map :id built-in/modules)))
    (is (every? #(= "eca" (namespace (:id %))) built-in/modules)))
  (testing ":eca/core is active by default"
    (is (modules/active? {} :eca/core)))
  (testing "the modules docs list every built-in module once, linked to the file that defines it"
    (let [links (re-seq #"\[`(eca/[a-z0-9-]+)`\]\(https://github\.com/editor-code-assistant/eca/blob/master/([^)\s]+)\)"
                        (slurp "docs/config/modules.md"))
          ids (map second links)]
      (is (= (set (map #(str (symbol (:id %))) built-in/modules)) (set ids)))
      (is (apply distinct? ids))
      (doseq [[_ id path] links]
        (is (and (.exists (java.io.File. ^String path))
                 (string/includes? (slurp path) (str ":id :" id)))
            (str id " links to " path))))))

(deftest problems-test
  (testing "a minimal module is valid"
    (is (= {:errors [] :warnings []} (modules/problems {:id :acme/x} {}))))
  (testing "errors"
    (is (match? {:errors [#":id must be a qualified keyword"]} (modules/problems {:id :x} {})))
    (is (match? {:errors [#"reserved for built-in"]} (modules/problems {:id :eca/x} {})))
    (is (= [] (:errors (modules/problems {:id :eca/x} {:built-in? true}))))
    (is (match? {:errors [#":api 2 is not supported"]} (modules/problems {:id :acme/x :api 2} {})))
    (is (match? {:errors [#":tools \"t\": :handler must be a function"]}
                (modules/problems {:id :acme/x :tools {"t" {:description "d" :parameters {}}}} {})))
    (is (match? {:errors [#":commands \"c\": :handler must be a function"]}
                (modules/problems {:id :acme/x :commands {"c" {}}} {})))
    (is (match? {:errors [#":event-handlers :tool/before-call: must be a function"]}
                (modules/problems {:id :acme/x :event-handlers {:tool/before-call "nope"}} {})))
    (is (match? {:errors [#":rpc is only for built-in modules"]}
                (modules/problems {:id :acme/x :rpc {}} {})))
    (is (match? {:errors [#":config-spec x: :type must be one of"]}
                (modules/problems {:id :acme/x :config-spec {:x {:type :int}}} {}))))
  (testing "warnings"
    (is (match? {:errors [] :warnings [":activation is not supported yet and is ignored"
                                       "Unknown key :foo is ignored"]}
                (modules/problems {:id :acme/x :activation :opt-in :foo 1} {})))))

(deftest resolution-test
  (let [a {:id :acme/a}
        b {:id :acme/b :requires #{:acme/a}}
        c {:id :acme/c :requires #{:acme/b}}
        off {:id :acme/off :default-enabled? false}]
    (with-built-ins [c b a off]
      (testing "start order: requirements first, then priority, then id"
        (is (= [:acme/a :acme/b :acme/c :eca/core] (active-ids {}))))
      (testing "off by default"
        (is (match? {:status :disabled :reason #"Off by default"} (status-of {} :acme/off)))
        (is (modules/active? {:modules {:acme/off {:enabled true}}} :acme/off)))
      (testing "disabled in config: the modules that require it are inactive"
        (let [config {:modules {:acme/a {:enabled false}}}]
          (is (= [:eca/core] (active-ids config)))
          (is (match? {:status :disabled} (status-of config :acme/a)))
          (is (match? {:status :inactive :reason "Requires :acme/a (disabled)"} (status-of config :acme/b)))
          (is (match? {:status :inactive :reason "Requires :acme/b (inactive)"} (status-of config :acme/c)))))
      (testing ":eca/core can't be disabled"
        (is (modules/active? {:modules {:eca/core {:enabled false}}} :eca/core)))
      (testing "session overrides win over config"
        (modules/set-enabled! :acme/off true)
        (is (modules/active? {:modules {:acme/off {:enabled false}}} :acme/off))
        (modules/set-enabled! :acme/a false)
        (is (not (modules/active? {:modules {:acme/a {:enabled true}}} :acme/a))))))
  (testing "priority: higher first, then id; modules.<id>.priority overrides it"
    (with-built-ins [{:id :acme/low :priority -5} {:id :acme/high :priority 5} {:id :acme/mid :priority 0}
                     {:id :acme/also-mid :priority 0}]
      (is (= [:acme/high :acme/also-mid :acme/mid :acme/low :eca/core] (active-ids {})))
      (is (= :acme/low (first (active-ids {:modules {:acme/low {:priority 10}}}))))))
  (testing "a missing requirement"
    (with-built-ins [{:id :acme/b :requires #{:acme/nope}}]
      (is (match? {:status :inactive :reason "Requires :acme/nope (not installed)"} (status-of {} :acme/b)))))
  (testing "a requirement loop"
    (with-built-ins [{:id :acme/x :requires #{:acme/y}} {:id :acme/y :requires #{:acme/x}}]
      (is (= [:eca/core] (active-ids {})))
      (is (match? {:status :inactive} (status-of {} :acme/x)))))
  (testing "an invalid module is refused"
    (with-built-ins [{:id :acme/bad :priority "high"}]
      (is (match? {:status :refused :reason #":priority must be an integer"} (status-of {} :acme/bad)))))
  (testing "config spec conflicts refuse the later module, and the modules requiring it"
    (with-built-ins [{:id :acme/first :priority 10 :config-spec {:shared {:type :string}}}
                     {:id :acme/second :config-spec {:shared {:type :number}}}
                     {:id :acme/third :requires #{:acme/second}}
                     {:id :acme/agrees :config-spec {:shared {:type :string :doc "Same."}}}]
      (is (= [:acme/first :acme/agrees :eca/core] (active-ids {})))
      (is (match? {:status :refused :reason #"shared: :acme/first declares it with another spec"}
                  (status-of {} :acme/second)))
      (is (match? {:status :inactive :reason "Requires :acme/second (refused)"} (status-of {} :acme/third)))))
  (testing "an external module can't take a built-in's id"
    (with-built-ins [{:id :acme/x}]
      (modules/add-external! "/tmp/x.clj" {:id :acme/x :description "impostor"})
      (is (= 1 (count (filter #(= :acme/x (:id %)) (modules/active {})))))
      (is (match? {:status :refused :source "/tmp/x.clj" :reason #"Another module already has id :acme/x"}
                  (some #(when (= "/tmp/x.clj" (:source %)) %) (modules/statuses {}))))))
  (testing "external modules default to priority 0, built-ins to -100"
    (modules/reset-registry!)
    (with-built-ins [{:id :eca/builtin}]
      (modules/add-external! "/tmp/ext.clj" {:id :acme/ext})
      (is (match? [{:id :acme/ext :priority 0 :built-in? false}
                   {:id :eca/builtin :priority -100 :built-in? true}
                   {:id :eca/core :priority -100}]
                  (modules/active {}))))))

(deftest default-config-test
  (testing ":config-spec defaults"
    (with-built-ins [{:id :acme/a :config-spec {:acme {:url {:type :string :default "http://a"}
                                                       :token {:type :string}}}}]
      (is (= {:acme {:url "http://a"}} (modules/default-config {} nil)))))
  (testing ":config-defaults: a module wins over what it requires, then higher priority, then higher id"
    (with-built-ins [{:id :acme/base :priority 50 :config-defaults {:defaultAgent "base" :x "base" :y "base"}}
                     {:id :acme/child :requires #{:acme/base} :config-defaults {:defaultAgent "child"}}
                     {:id :acme/high :priority 10 :config-defaults {:x "high" :z "high"}}
                     {:id :acme/zed :priority 10 :config-defaults {:z "zed"}}]
      (is (= {:defaultAgent "child" :x "base" :y "base" :z "zed"}
             (modules/default-config {} nil)))))
  (testing "entries of a shared map keep module id order"
    (with-built-ins [{:id :acme/b :config-defaults {:agent {"b" {}}}}
                     {:id :acme/a :config-defaults {:agent {"a" {}}}}
                     {:id :acme/c :config-defaults {:agent {"c" {}}}}]
      (is (= ["a" "b" "c"] (keys (:agent (modules/default-config {} nil)))))))
  (testing "a :config-defaults function gets the db"
    (with-built-ins [{:id :acme/fn :config-defaults (fn [{:keys [db]}]
                                                      {:roots (count (:workspace-folders db))})}]
      (is (= {:roots 2} (modules/default-config {} {:workspace-folders [{} {}]})))))
  (testing "disabled modules add nothing"
    (with-built-ins [{:id :acme/a :config-defaults {:a 1}}]
      (is (= {} (modules/default-config {:modules {:acme/a {:enabled false}}} nil))))))

(deftest config-integration-test
  (reset! config/initialization-config* {:pureConfig true})
  (config/clear-cache!)
  (with-built-ins [{:id :acme/a :config-spec {:acmeUrl {:type :string :default "http://a"}}
                    :config-defaults {:agent {"acme" {:mode "primary"}}}}]
    (testing "the active modules' defaults are in the config"
      (is (match? {:acmeUrl "http://a"
                   :agent {"acme" {:mode "primary"}}
                   :chatRetentionDays 30}         ; and the defaults still in initial-config*
                  (config/all (h/db)))))
    (testing "the user's config wins"
      (reset! config/initialization-config* {:pureConfig true :acmeUrl "http://mine"})
      (config/clear-cache!)
      (is (= "http://mine" (:acmeUrl (config/all (h/db))))))
    (testing "disabling the module in config drops its defaults"
      (reset! config/initialization-config* {:pureConfig true :modules {"acme/a" {"enabled" false}}})
      (config/clear-cache!)
      (let [config (config/all (h/db))]
        (is (nil? (:acmeUrl config)))
        (is (nil? (get-in config [:agent "acme"])))
        (is (= {:acme/a {:enabled false}} (:modules config)))))
    (testing "disabling it for the session takes effect without clearing the cache"
      (reset! config/initialization-config* {:pureConfig true})
      (config/clear-cache!)
      (is (= "http://a" (:acmeUrl (config/all (h/db)))))
      (modules/set-enabled! :acme/a false)
      (is (nil? (:acmeUrl (config/all (h/db))))))))

(deftest invoke-test
  (let [external {:id :acme/flaky :module {:id :acme/flaky} :built-in? false}
        built-in {:id :eca/flaky :module {:id :eca/flaky} :built-in? true}
        boom (fn [] (throw (ex-info "boom" {})))]
    (testing "errors are recorded and rethrown"
      (is (thrown? Exception (modules/invoke {} external "test" {} boom)))
      (is (= 1 (modules/failures :acme/flaky)))
      (is (match? [{:what "test" :message "boom"}] (modules/errors :acme/flaky))))
    (testing "a success resets the count"
      (is (= :ok (modules/invoke {} external "test" {} (constantly :ok))))
      (is (= 0 (modules/failures :acme/flaky))))
    (testing "invoke-safe returns nil"
      (is (nil? (modules/invoke-safe {} external "test" {} boom))))
    (testing "an external module fails for the session after 5 failures in a row"
      (with-built-ins []
        (modules/add-external! "/tmp/flaky.clj" {:id :acme/flaky})
        (is (modules/active? {} :acme/flaky))
        (dotimes [_ 4] (modules/invoke-safe {} external "test" {} boom))
        (is (not (modules/active? {} :acme/flaky)))
        (is (match? {:status :failed :reason #"after 5 failures in a row"} (status-of {} :acme/flaky)))))
    (testing "built-in modules are never disabled for failures"
      (with-built-ins [{:id :eca/flaky}]
        (dotimes [_ 6] (modules/invoke-safe {} built-in "test" {} boom))
        (is (= 6 (modules/failures :eca/flaky)))
        (is (modules/active? {} :eca/flaky))))
    (testing "timeouts"
      (is (match? {:message #"Timed out after 50 ms"}
                  (try (modules/invoke {} external "slow" {:timeout-ms 50} #(Thread/sleep 5000))
                       (catch Exception e {:message (ex-message e)}))))
      (testing "external modules get the default; built-ins none"
        (is (= 10 (modules/timeout-for external nil 10)))
        (is (nil? (modules/timeout-for built-in nil 10)))
        (is (= 5 (modules/timeout-for built-in 5 10)))))))

(deftest lifecycle-test
  (let [calls* (atom [])
        module (fn [id & {:as opts}]
                 (merge {:id id
                         :start (fn [ctx] (swap! calls* conj [:start (:module-id ctx)]) {:started id})
                         :stop (fn [ctx] (swap! calls* conj [:stop (:module-id ctx)]))}
                        opts))
        components {:db* (h/db*) :messenger (h/messenger)}]
    (with-built-ins [(module :acme/a) (module :acme/b :requires #{:acme/a}) {:id :acme/plain}]
      (testing "start! starts in start order and keeps each state"
        (modules/start! components {})
        (is (= [[:start :acme/a] [:start :acme/b]] @calls*))
        (is (= {:started :acme/a} (modules/state :acme/a)))
        (is (modules/running? :acme/b))
        (is (not (modules/running? :acme/plain))))
      (testing "registrations are kept while the module runs"
        (modules/register! :acme/b :tools "t" {:handler identity})
        (is (match? [[{:id :acme/b} "t" map?]] (modules/registrations {} :tools))))
      (testing "disabling a module stops it and the modules that require it, in reverse order"
        (reset! calls* [])
        (modules/set-enabled! :acme/a false)
        (modules/reconcile-if-started! components {})
        (is (= [[:stop :acme/b] [:stop :acme/a]] @calls*))
        (is (nil? (modules/state :acme/a)))
        (is (= [] (modules/registrations {} :tools))))
      (testing "enabling it again starts both"
        (reset! calls* [])
        (modules/set-enabled! :acme/a true)
        (modules/reconcile-if-started! components {})
        (is (= [[:start :acme/a] [:start :acme/b]] @calls*)))
      (testing "restart! stops and starts one module"
        (reset! calls* [])
        (modules/restart! components {} :acme/b)
        (is (= [[:stop :acme/b] [:start :acme/b]] @calls*)))
      (testing "stop-all! stops everything in reverse start order"
        (reset! calls* [])
        (modules/stop-all! components)
        (is (= [[:stop :acme/b] [:stop :acme/a]] @calls*))
        (is (empty? (modules/running)))
        (testing "and reconcile does nothing until start! again"
          (modules/reconcile-if-started! components {})
          (is (empty? (modules/running)))))))
  (testing "a failing :start marks the module failed; the modules that require it don't start"
    (let [calls* (atom [])]
      (with-built-ins [{:id :acme/broken :start (fn [_] (throw (ex-info "no db" {})))}
                       {:id :acme/dependent :requires #{:acme/broken}
                        :start (fn [_] (swap! calls* conj :dependent))}]
        (modules/start! {:db* (h/db*) :messenger (h/messenger)} {})
        (is (= [] @calls*))
        (is (match? {:status :failed :reason "Failed to start: no db"} (status-of {} :acme/broken)))
        (is (match? {:status :inactive} (status-of {} :acme/dependent)))
        (is (match? [{:message "ECA module :acme/broken: Failed to start: no db"}]
                    (:show-message (h/messages))))
        (testing "enabling it again clears the failure"
          (modules/set-enabled! :acme/broken true)
          (is (match? {:status :active} (status-of {} :acme/broken)))))))
  (testing "external modules restart when a key of their :config-spec changes"
    (let [starts* (atom [])]
      (with-built-ins []
        (modules/add-external! "/tmp/ext.clj"
                               {:id :acme/ext
                                :config-spec {:extUrl {:type :string}}
                                :start (fn [ctx] (swap! starts* conj (:extUrl (:config ctx))))})
        (let [components {:db* (h/db*) :messenger (h/messenger)}]
          (modules/start! components {:extUrl "a"})
          (modules/reconcile! components {:extUrl "a" :other 1})
          (is (= ["a"] @starts*))
          (modules/reconcile! components {:extUrl "b"})
          (is (= ["a" "b"] @starts*))
          (testing "and when reloaded with a new module map"
            (modules/add-external! "/tmp/ext.clj"
                                   {:id :acme/ext
                                    :config-spec {:extUrl {:type :string}}
                                    :start (fn [_] (swap! starts* conj :reloaded))})
            (modules/reconcile! components {:extUrl "b"})
            (is (= ["a" "b" :reloaded] @starts*))))))))

(deftest prefix-test
  (is (= "eca" (modules/prefix {:module {:id :eca/filesystem} :built-in? true})))
  (is (= "jira" (modules/prefix {:module {:id :eca-plugins/jira} :built-in? false})))
  (is (= "plan" (modules/prefix {:module {:id :eca-plugins/plan-gate :prefix "plan"} :built-in? false}))))
