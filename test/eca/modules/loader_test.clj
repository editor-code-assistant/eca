(ns eca.modules.loader-test
  {:clj-kondo/config '{:lint-as {eca.modules.loader-test/with-dirs clojure.core/fn}}}
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.commands :as f.commands]
   [eca.modules :as modules]
   [eca.modules.events :as events]
   [eca.modules.loader :as loader]
   [eca.shared :as shared]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn- write!
  "Writes `content` to `rel` under `dir`, returning the file's canonical path."
  [dir rel content]
  (let [file (fs/path dir rel)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) content)
    (str (fs/canonicalize file))))

(defn- canonical [path]
  (str (fs/canonicalize path)))

(defmacro ^:private with-dirs
  "Runs body with `global` as ECA's global config folder and `workspace` as
   the only workspace folder, both empty temp folders."
  [[global workspace] & body]
  `(let [~global (str (fs/create-temp-dir {:prefix "eca-modules-global"}))
         ~workspace (str (fs/create-temp-dir {:prefix "eca-modules-workspace"}))]
     (try
       (swap! (h/db*) assoc :workspace-folders [{:uri (shared/filename->uri ~workspace)}])
       (with-redefs [shared/global-config-dir (constantly (fs/file ~global))]
         ~@body)
       (finally
         (fs/delete-tree ~global)
         (fs/delete-tree ~workspace)))))

(defn- sync! [config]
  (loader/sync! config (h/db)))

(defn- status-of [config id]
  (some #(when (= id (:id %)) %) (modules/statuses config)))

(defn- chat-ctx [config]
  {:chat-id "chat-1" :db* (h/db*) :messenger (h/messenger) :metrics (h/metrics) :config config})

(defn- command-text [result]
  (get-in result [:chats "chat-1" :messages 0 :content 0 :text]))

(def ^:private hello-module
  "(ns acme.hello
     (:require [clojure.string :as str]
               [eca.api :as eca]))

   (def module
     {:id :acme/hello
      :description \"Says hello.\"
      :commands {\"hello\" {:description \"Say hello.\"
                           :arguments [{:name \"who\"}]
                           :handler (fn [ctx {:keys [who]}]
                                      (str \"Hello \" (str/upper-case who) \" from \" (eca/module-id ctx)))}}})")

(deftest file-module-test
  (with-dirs [global _workspace]
    (let [path (write! global "modules/hello.clj" hello-module)]
      (testing "a file in the global modules folder loads"
        (is (= [{:path path :status :loaded :id :acme/hello}] (sync! {})))
        (is (modules/active? {} :acme/hello))
        (is (match? {:source path :status :active :kind :global} (status-of {} :acme/hello))))
      (testing "its command runs, with eca.api"
        (is (= "Hello ANA from :acme/hello"
               (command-text (f.commands/handle-command! "hello" ["ana"] (chat-ctx {}))))))
      (testing "nothing changes while the file doesn't"
        (is (= [] (sync! {}))))
      (testing "a change reloads it"
        (spit path (string/replace hello-module "Says hello." "Says hi."))
        (is (= [{:path path :status :loaded :id :acme/hello}] (sync! {})))
        (is (= "Says hi." (get-in (status-of {} :acme/hello) [:module :description]))))
      (testing "removing the file removes the module"
        (fs/delete path)
        (is (= [{:path path :status :removed}] (sync! {})))
        (is (nil? (status-of {} :acme/hello)))))))

(deftest folder-module-test
  (with-dirs [global _workspace]
    (let [dir (canonical (fs/create-dirs (fs/path global "modules" "greeter")))]
      (write! dir "acme/greeter/words.clj"
              "(ns acme.greeter.words) (defn greeting [] \"Hello\")")
      (write! dir "module.clj"
              "(ns acme.greeter (:require [acme.greeter.words :as words]))
               (def module
                 {:id :acme/greeter
                  :defines {:events {:acme/greet {:kind :handle :doc \"Greet someone.\"}}}
                  :event-handlers {:acme/greet (fn [_ctx {:keys [who]}] (str (words/greeting) \", \" who))}})")
      (testing "module.clj loads, and its namespaces load from the folder"
        (is (= [{:path dir :status :loaded :id :acme/greeter}] (sync! {})))
        (is (= "Hello, Ana" (events/emit! {:config {}} :acme/greet {:who "Ana"}))))
      (testing "a change to any of its files reloads them"
        (write! dir "acme/greeter/words.clj"
                "(ns acme.greeter.words) (defn greeting [] \"Hi\")")
        (is (= [{:path dir :status :loaded :id :acme/greeter}] (sync! {})))
        (is (= "Hi, Ana" (events/emit! {:config {}} :acme/greet {:who "Ana"})))))))

(deftest modules-require-each-other-test
  (with-dirs [global _workspace]
    (write! global "modules/a.clj"
            "(ns acme.a) (defn shout [s] (str s \"!\")) (def module {:id :acme/a})")
    (write! global "modules/b.clj"
            "(ns acme.b (:require [acme.a :as a]))
             (def module {:id :acme/b :system-prompts [{:id :acme/b-section :content (a/shout \"Hi\")}]})")
    (is (match? (m/in-any-order [{:status :loaded :id :acme/a} {:status :loaded :id :acme/b}]) (sync! {})))
    (is (= "Hi!" (-> (modules/active-entry {} :acme/b) :module :system-prompts first :content)))))

(deftest load-errors-test
  (with-dirs [global _workspace]
    (let [syntax (write! global "modules/syntax.clj" "(ns acme.syntax)\n(def module {:id :acme/syntax")
          no-ns (write! global "modules/no_ns.clj" "(def module {:id :acme/no-ns})")
          no-module (write! global "modules/no_module.clj" "(ns acme.no-module) (def x 1)")
          not-map (write! global "modules/not_map.clj" "(ns acme.not-map) (def module [:id])")
          runtime (write! global "modules/runtime.clj" "(ns acme.runtime)\n\n(def module (undefined-fn))")
          changes (sync! {})]
      (testing "each failure says why"
        (is (match? (m/in-any-order
                     [{:path syntax :status :failed :error #"EOF while reading"}
                      {:path no-ns :status :failed :error "no_ns.clj must start with an ns form"}
                      {:path no-module :status :failed :error "Namespace acme.no-module defines no module var"}
                      {:path not-map :status :failed :error "acme.not-map/module must be a map"}
                      {:path runtime :status :failed :error #"Unable to resolve symbol: undefined-fn \(runtime.clj:3:"}])
                    changes)))
      (testing "/modules lists them as failed"
        (is (match? {:status :failed :source runtime :reason #"^Failed to load: Unable to resolve symbol"}
                    (some #(when (= runtime (:source %)) %) (modules/statuses {}))))))
    (testing "an invalid module loads, but is refused"
      (let [path (write! global "modules/invalid.clj" "(ns acme.invalid) (def module {:id :invalid})")]
        (is (match? [{:path path :status :loaded}] (filter #(= path (:path %)) (sync! {}))))
        (is (match? {:status :refused :reason #":id must be a qualified keyword"}
                    (some #(when (= path (:source %)) %) (modules/statuses {}))))))))

(deftest configured-source-test
  (with-dirs [global workspace]
    (let [dev-dir (canonical (fs/create-dirs (fs/path global "dev")))
          path (write! dev-dir "hello.clj" hello-module)]
      (testing "modules.<id>.source loads a file from anywhere"
        (let [config {:modules {:acme/hello {:source path}}}]
          (is (= [{:path path :status :loaded :id :acme/hello}] (sync! config)))
          (is (match? {:status :active :kind :config} (status-of config :acme/hello)))))
      (testing "the source must define the id the config names"
        (let [config {:modules {:acme/other {:source path}}}]
          (is (match? [{:path path :status :failed :id :acme/other
                        :error "It defines :acme/hello, not :acme/other, the id modules.acme/other.source names"}]
                      (sync! config)))
          (is (match? {:status :failed} (status-of config :acme/other)))))
      (testing "a relative source resolves against the first workspace folder"
        (let [rel-path (write! workspace "tools/hello.clj" hello-module)
              config {:modules {:acme/hello {:source "tools/hello.clj"}}}]
          (is (match? [{:path rel-path :status :loaded :id :acme/hello}]
                      (filter #(= :loaded (:status %)) (sync! config))))))
      (testing "a missing source fails"
        (let [config {:modules {:acme/gone {:source (str dev-dir "/gone.clj")}}}]
          (is (match? [{:status :failed :id :acme/gone :error "File not found"}]
                      (filter #(= :failed (:status %)) (sync! config)))))))))

(deftest source-kinds-test
  (with-dirs [global workspace]
    (let [global-path (write! global "modules/hello.clj" hello-module)
          workspace-path (write! workspace ".eca/modules/ws.clj" "(ns acme.ws) (def module {:id :acme/ws})")
          plugin-dir (canonical (fs/create-dirs (fs/path global "plugin" "modules")))
          plugin-path (write! plugin-dir "p.clj" "(ns acme.p) (def module {:id :acme/p})")
          cli-dir (canonical (fs/create-dirs (fs/path global "cli")))
          cli-path (write! cli-dir "hello.clj" (string/replace hello-module "acme.hello" "acme.hello-dev"))]
      (reset! config/plugin-components* {:modules [{:path plugin-path :plugin "acme-plugin"}]})
      (loader/set-cli-paths! [cli-path])
      (testing "sources, by precedence"
        (is (= [{:path cli-path :kind :cli}
                {:path workspace-path :kind :workspace}
                {:path global-path :kind :global}
                {:path plugin-path :kind :plugin :plugin "acme-plugin"}]
               (loader/sources {} (h/db)))))
      (testing "when two sources define one id, the first keeps it"
        (sync! {})
        (is (match? {:status :active :source cli-path :kind :cli} (status-of {} :acme/hello)))
        (is (match? {:status :refused :reason #"Another module already has id :acme/hello"}
                    (some #(when (= global-path (:source %)) %) (modules/statuses {})))))
      (testing "workspace and plugin modules load too"
        (is (modules/active? {} :acme/ws))
        (is (modules/active? {} :acme/p)))
      (testing "pureConfig keeps only --module paths and configured sources"
        (is (= [{:path cli-path :kind :cli}]
               (loader/sources {:pureConfig true} (h/db))))))))

(deftest same-namespace-test
  (with-dirs [global _workspace]
    (let [installed (write! global "modules/hello.clj" hello-module)
          dev-dir (canonical (fs/create-dirs (fs/path global "dev")))
          dev (write! dev-dir "hello.clj" (string/replace hello-module "Says hello." "Says hello, dev."))]
      (loader/set-cli-paths! [dev])
      (testing "a --module copy of an installed module wins, and the installed one isn't evaluated"
        (is (match? (m/in-any-order
                     [{:path dev :status :loaded :id :acme/hello}
                      {:path installed :status :refused
                       :reason (str "Not loaded: " dev " already loads namespace acme.hello")}])
                    (sync! {})))
        (is (= "Says hello, dev." (get-in (status-of {} :acme/hello) [:module :description])))
        (is (match? {:status :refused :reason #"^Not loaded: "}
                    (some #(when (= installed (:source %)) %) (modules/statuses {}))))))))

(deftest reload-test
  (with-dirs [global _workspace]
    (write! global "modules/counter.clj"
            "(ns acme.counter)
             (defonce loads (atom 0))
             (swap! loads inc)
             (def module {:id :acme/counter :description (str \"load \" @loads)})")
    (sync! {})
    (is (= "load 1" (get-in (status-of {} :acme/counter) [:module :description])))
    (testing "reload! evaluates the source again, even unchanged"
      (is (match? [{:status :loaded :id :acme/counter}] (loader/reload! :acme/counter {} (h/db))))
      (is (= "load 2" (get-in (status-of {} :acme/counter) [:module :description]))))
    (testing "only external modules reload"
      (is (nil? (loader/reload! :eca/core {} (h/db)))))))

(deftest lifecycle-and-config-test
  (with-dirs [global _workspace]
    (write! global "modules/counter.clj"
            "(ns acme.stateful (:require [eca.api :as eca]))
             (def module
               {:id :acme/stateful
                :config-spec {:acmeGreeting {:type :string :default \"Hey\"}}
                :start (fn [ctx] {:greeting (:acmeGreeting (eca/config ctx))})
                :commands {\"greet\" {:handler (fn [ctx _]
                                                (str (:greeting (eca/state ctx)) \" from \"
                                                     (count (eca/workspaces ctx)) \" workspace\"))}}})")
    (sync! {})
    (let [config (config/all (h/db))]
      (testing "the module's config key gets its default"
        (is (= "Hey" (:acmeGreeting config))))
      (testing ":start runs, and its value is the module state"
        (modules/start! {:db* (h/db*) :messenger (h/messenger) :metrics (h/metrics)} config)
        (is (modules/running? :acme/stateful))
        (is (= "Hey from 1 workspace"
               (command-text (f.commands/handle-command! "greet" [] (chat-ctx config)))))))))

(deftest output-goes-to-the-log-test
  (with-dirs [global _workspace]
    (write! global "modules/noisy.clj"
            "(ns acme.noisy) (println \"loading noisy\") (def module {:id :acme/noisy})")
    (testing "a module's prints never reach stdout, the JSON-RPC channel"
      (is (= "" (with-out-str (sync! {}))))
      (is (modules/active? {} :acme/noisy)))))
