(ns eca.modules
  "The module runtime: which modules exist, which are active, their lifecycle
   and guarded calls into them. A module is a plain map (`:id`, `:tools`,
   `:commands`, `:event-handlers`, `:config-spec`...); see the Modular ECA
   design, #699.

   Built-in modules are compiled and listed in `eca.modules.built-in`.
   External modules are loaded at runtime. What config resolution depends on
   (external modules, session overrides, failed modules) lives in `registry*`,
   like `eca.config/plugin-components*`, and bumps `version` when it changes,
   so the config cache can key on it."
  (:require
   [clojure.set :as set]
   [clojure.string :as string]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.modules.config-spec :as config-spec]
   [eca.shared :as shared]
   [sci.lang])
  (:import
   [java.util.concurrent
    Callable
    CancellationException
    ExecutionException
    ExecutorService
    Executors
    Future
    ThreadFactory
    TimeUnit
    TimeoutException]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MODULES]")

(def api-version
  "The module API version this ECA implements."
  1)

(def ^:private supported-api-versions #{1})

(def built-in-priority -100)
(def external-priority 0)

(def default-handler-timeout-ms 10000)
(def default-start-timeout-ms 30000)

(def max-failures
  "Failures in a row after which an external module is disabled for the session."
  5)

(def ^:private v1-keys
  #{:id :description :version :api :requires :priority :default-enabled?
    :config-spec :start :stop :tools :prefix :commands :system-prompts
    :providers :apis :config-defaults :event-handlers :defines})

(def ^:private built-in-only-keys #{:rpc :agents})

(def ^:private later-keys #{:activation :strategies :skills :rules :contexts :capabilities :ui})

;; --- Registry

(defonce ^:private registry* (atom {:version 0}))

(defn version
  "Changes whenever something that decides the active modules changes."
  []
  (:version @registry*))

(defn ^:private swap-inputs! [f & args]
  (swap! registry* (fn [registry]
                     (-> (apply f registry args)
                         (update :version inc)))))

(defn reset-registry!
  "Back to a fresh runtime: no external modules, overrides, state or
   failures, and the background pools stopped. For tests."
  []
  (let [[old] (swap-vals! registry* (fn [{:keys [version]}] {:version (inc version)}))]
    (doseq [[_ pools] (:pools old)
            [_ ^ExecutorService executor] pools]
      (.shutdownNow executor))))

(defn built-in-modules
  "The compiled modules, resolved lazily: `eca.modules.built-in` requires the
   feature namespaces, which require this one."
  []
  @(requiring-resolve 'eca.modules.built-in/modules))

(defn add-external!
  "Adds or replaces the external module loaded from `source`, its path.
   `opts`: `:kind`, where the source was found; `:rank`, its precedence
   (lower first: when two sources define the same id, the first keeps it);
   `:expected-id`, the id the config gives it; `:plugin`; and `:error` when
   the source didn't load, with a nil `module` (`:refused?` when it wasn't
   loaded on purpose, e.g. another source loads its namespace)."
  ([source module] (add-external! source module {}))
  ([source module opts]
   (swap-inputs! assoc-in [:external source] (merge opts {:module module :source source}))))

(defn remove-external!
  [source]
  (swap-inputs! update :external dissoc source))

(defn external-modules
  "Loaded external modules: source → {:module :source :kind :rank
   :expected-id :plugin :error}."
  []
  (:external @registry*))

;; --- Validation

(defn fn-like?
  "Whether `x` can be called as a module function: a function, a var (also
   an SCI one, from an external module) or a multimethod."
  [x]
  (or (fn? x) (var? x) (instance? clojure.lang.MultiFn x) (instance? sci.lang.Var x)))

(defn ^:private qualified-kw? [x] (qualified-keyword? x))

(defn ^:private map-of-problems
  "Problems of a map of name → entry, with `entry-problems` for each entry."
  [k m key-pred key-desc entry-problems]
  (cond
    (nil? m) []
    (not (map? m)) [(format "%s must be a map" k)]
    :else (vec (mapcat (fn [[n entry]]
                         (if-not (key-pred n)
                           [(format "%s: %s must be %s" k (pr-str n) key-desc)]
                           (map #(format "%s %s: %s" k (pr-str n) %) (entry-problems entry))))
                       m))))

(defn tool-problems
  "What's wrong with a tool entry, as strings."
  [tool]
  (if-not (map? tool)
    ["must be a map"]
    (cond-> []
      (not (fn-like? (:handler tool))) (conj ":handler must be a function")
      (not (or (string? (:description tool)) (fn-like? (:description tool)))) (conj ":description must be a string or a function")
      (not (map? (:parameters tool))) (conj ":parameters must be a JSON Schema map"))))

(defn command-problems
  "What's wrong with a command entry, as strings."
  [command]
  (if-not (map? command)
    ["must be a map"]
    (cond-> []
      (not (fn-like? (:handler command))) (conj ":handler must be a function")
      (and (some? (:description command)) (not (string? (:description command)))) (conj ":description must be a string")
      (and (some? (:arguments command)) (not (sequential? (:arguments command)))) (conj ":arguments must be a vector"))))

(defn ^:private handler-spec-problems [spec]
  (letfn [(one [h]
            (cond
              (fn-like? h) []
              (map? h) (cond-> []
                         (not (fn-like? (:handler h))) (conj ":handler must be a function")
                         (and (some? (:priority h)) (not (integer? (:priority h)))) (conj ":priority must be an integer")
                         (and (some? (:timeout-ms h)) (not (pos-int? (:timeout-ms h)))) (conj ":timeout-ms must be a positive integer")
                         (and (some? (:match h)) (not (map? (:match h)))) (conj ":match must be a map"))
              :else ["must be a function, a map with :handler, or a vector of those"]))]
    (if (sequential? spec) (vec (mapcat one spec)) (one spec))))

(defn ^:private section-problems [sections]
  (cond
    (nil? sections) []
    (not (sequential? sections)) [":system-prompts must be a vector of sections"]
    :else (vec (mapcat (fn [section]
                         (if-not (map? section)
                           [":system-prompts: each section must be a map"]
                           (cond-> []
                             (not (qualified-kw? (:id section))) (conj ":system-prompts: each section needs a qualified keyword :id")
                             (not (or (string? (:content section)) (fn-like? (:content section))))
                             (conj (format ":system-prompts %s: :content must be a string or a function" (:id section))))))
                       sections))))

(defn ^:private defines-problems [defines]
  (cond
    (nil? defines) []
    (not (map? defines)) [":defines must be a map"]
    :else (map-of-problems ":defines :events" (:events defines) qualified-kw? "a qualified keyword"
                           (fn [definition]
                             (cond
                               (not (map? definition)) ["must be a map"]
                               (not (#{:notify :transform :handle} (:kind definition)))
                               [":kind must be :notify, :transform or :handle"]
                               :else [])))))

(defn problems
  "What's wrong with `module`: `{:errors [..] :warnings [..]}`. A module with
   errors is refused."
  [module {:keys [built-in?]}]
  (if-not (map? module)
    {:errors ["A module must be a map"] :warnings []}
    (let [{:keys [id api requires priority default-enabled? start stop prefix
                  tools commands providers apis config-defaults event-handlers]} module
          ks (set (keys module))
          errors (concat
                  (cond
                    (not (qualified-kw? id)) [":id must be a qualified keyword, e.g. :acme/example"]
                    (and (not built-in?) (= "eca" (namespace id))) [":eca/* ids are reserved for built-in modules"]
                    :else [])
                  (when (and (some? api) (not (contains? supported-api-versions api)))
                    [(format ":api %s is not supported; this ECA supports %s" (pr-str api)
                             (string/join ", " (sort supported-api-versions)))])
                  (when (and (some? requires) (not (and (coll? requires) (every? qualified-kw? requires))))
                    [":requires must be a set of module ids"])
                  (when (and (some? priority) (not (integer? priority))) [":priority must be an integer"])
                  (when (and (some? default-enabled?) (not (boolean? default-enabled?))) [":default-enabled? must be a boolean"])
                  (when (and (some? start) (not (fn-like? start))) [":start must be a function"])
                  (when (and (some? stop) (not (fn-like? stop))) [":stop must be a function"])
                  (cond
                    (and (some? prefix) (not (and (string? prefix) (re-matches #"[A-Za-z0-9_-]+" prefix))))
                    [":prefix must be a string of letters, digits, _ or -"]

                    (and (not built-in?) (= "eca" prefix))
                    [":prefix eca is reserved for built-in modules; a tool takes over a built-in one with :replaces"]

                    (and (not built-in?) (nil? prefix) (qualified-kw? id) (= "eca" (name id)))
                    [":prefix defaults to the name in :id, and eca is reserved for built-in modules: set another :prefix"]

                    :else [])
                  (when (and (some? (:description module)) (not (string? (:description module)))) [":description must be a string"])
                  (when (and (some? (:version module)) (not (string? (:version module)))) [":version must be a string"])
                  (if (and built-in? (fn-like? tools))
                    []
                    (map-of-problems ":tools" tools string? "a string" tool-problems))
                  (map-of-problems ":commands" commands string? "a string" command-problems)
                  (section-problems (:system-prompts module))
                  (map-of-problems ":providers" providers string? "a string" #(if (map? %) [] ["must be a map"]))
                  (map-of-problems ":apis" apis string? "a string"
                                   #(if (and (map? %) (fn-like? (:handler %))) [] ["must be a map with a :handler function"]))
                  (when (and (some? config-defaults) (not (or (map? config-defaults) (fn-like? config-defaults))))
                    [":config-defaults must be a map or a function"])
                  (when (and (some? (:agents module)) (not (fn-like? (:agents module))))
                    [":agents must be a function"])
                  (map-of-problems ":event-handlers" event-handlers qualified-kw? "a qualified keyword" handler-spec-problems)
                  (defines-problems (:defines module))
                  (when (and (not built-in?) (some built-in-only-keys ks))
                    [(format "%s is only for built-in modules" (string/join ", " (filter built-in-only-keys ks)))])
                  (map #(str ":config-spec " %) (config-spec/problems (:config-spec module))))
          warnings (concat
                    (for [k (sort (filter later-keys ks))]
                      (format "%s is not supported yet and is ignored" k))
                    (for [k (sort (remove (set/union v1-keys built-in-only-keys later-keys) ks))]
                      (format "Unknown key %s is ignored" k)))]
      {:errors (vec errors) :warnings (vec warnings)})))

;; --- Resolution: which modules are active

(defn modules-config
  "The config's `modules` key: module id → {:enabled :source :priority}."
  [config]
  (when (map? config)
    (let [m (:modules config)]
      (when (map? m) m))))

(defn ^:private effective-priority [{:keys [id module built-in?]} modules-config]
  (let [configured (get-in modules-config [id :priority])]
    (cond
      (number? configured) (long configured)
      (integer? (:priority module)) (:priority module)
      built-in? built-in-priority
      :else external-priority)))

(defn ^:private requirements [entry]
  (set (:requires (:module entry))))

(defn ^:private disabled-reason [{:keys [id module]} modules-config {:keys [overrides failed]}]
  (let [configured (get-in modules-config [id :enabled])]
    (cond
      (= :eca/core id) nil
      (contains? failed id) {:status :failed :reason (get-in failed [id :reason])}
      (contains? overrides id) (when-not (get overrides id)
                                 {:status :disabled :reason "Disabled for this session with /modules disable."})
      (boolean? configured) (when-not configured
                              {:status :disabled :reason "Disabled by modules.<id>.enabled in config."})
      (false? (:default-enabled? module)) {:status :disabled
                                           :reason "Off by default: enable it with modules.<id>.enabled or /modules enable."}
      :else nil)))

(defn ^:private pick-next
  "Kahn's step: the first entry, by `compare-fn`, whose requirements are all in
   `placed-ids`."
  [pending placed-ids compare-fn]
  (first (sort compare-fn (filter #(set/subset? (requirements %) placed-ids) pending))))

(defn ^:private by-priority-then-id
  "Higher priority first, then id."
  [a b]
  (let [c (compare (:priority b) (:priority a))]
    (if (zero? c) (compare (:id a) (:id b)) c)))

(defn ^:private validate-candidates
  "Splits candidates into valid ones and refused ones (errors, or an id
   another module already has; built-ins come first, so they keep theirs)."
  [candidates]
  (reduce (fn [{:keys [sources] :as acc} entry]
            (let [{:keys [errors warnings]} (problems (:module entry) entry)
                  entry (assoc entry :warnings warnings)]
              (cond
                (seq errors)
                (update acc :refused conj (assoc entry :status :refused :reason (string/join "; " errors)))

                (contains? sources (:id entry))
                (update acc :refused conj (assoc entry :status :refused
                                                 :reason (format "Another module already has id %s (%s)"
                                                                 (:id entry) (get sources (:id entry)))))

                :else
                (-> acc
                    (update :valid conj entry)
                    (assoc-in [:sources (:id entry)] (:source entry))))))
          {:valid [] :refused [] :sources {}}
          candidates))

(defn ^:private place
  "Orders the enabled entries for start: each after the modules it requires;
   among ready ones, higher priority first, then id. A module whose config
   spec conflicts with an already placed one is refused."
  [enabled]
  (loop [pending (vec enabled)
         placed-ids #{}
         acc {:active [] :declared {} :conflicting {}}]
    (if-let [next-entry (pick-next pending placed-ids by-priority-then-id)]
      (let [pending (filterv #(not= (:id %) (:id next-entry)) pending)
            spec (:config-spec (:module next-entry))
            conflicts (config-spec/conflicts (:declared acc) spec)]
        (if (seq conflicts)
          (recur pending placed-ids
                 (assoc-in acc [:conflicting (:id next-entry)]
                           (assoc next-entry :status :refused
                                  :reason (str "Config spec conflict: " (string/join "; " conflicts)))))
          (recur pending
                 (conj placed-ids (:id next-entry))
                 (-> acc
                     (update :active conj (assoc next-entry :status :active))
                     ;; a key's first owner stays its owner
                     (update :declared #(merge (into {} (map (fn [[path s]] [path {:spec s :module (:id next-entry)}]))
                                                     (config-spec/leaves spec))
                                               %))))))
      acc)))

(defn ^:private resolve*
  [modules-config registry built-ins]
  (let [{not-loaded true loaded false} (group-by (comp boolean :error)
                                                 (sort-by (juxt #(:rank % Long/MAX_VALUE) :source)
                                                          (vals (:external registry))))
        candidates (concat (map (fn [m] {:id (:id m) :module m :source :built-in :built-in? true}) built-ins)
                           (map (fn [{:keys [module source kind]}]
                                  {:id (when (map? module) (:id module)) :module module :source source :kind kind
                                   :built-in? false})
                                loaded))
        load-failed (for [{:keys [source kind expected-id error refused?]} not-loaded]
                      {:id expected-id :module nil :source source :kind kind :built-in? false
                       :status (if refused? :refused :failed)
                       :reason (if refused? error (str "Failed to load: " error))})
        {:keys [valid refused]} (validate-candidates candidates)
        entries (map #(assoc % :priority (effective-priority % modules-config)) valid)
        {enabled false disabled true} (group-by #(boolean (disabled-reason % modules-config registry)) entries)
        disabled (map #(merge % (disabled-reason % modules-config registry)) disabled)
        {:keys [active declared conflicting]} (place enabled)
        active-ids (set (map :id active))
        status-by-id (merge (into {} (map (juxt :id :status)) (filter :id load-failed))
                            (into {} (map (juxt :id :status)) refused)
                            (into {} (map (juxt :id :status)) disabled)
                            (update-vals conflicting :status))
        unplaced (for [entry enabled
                       :when (not (or (contains? active-ids (:id entry))
                                      (contains? conflicting (:id entry))))
                       :let [missing (sort (remove active-ids (requirements entry)))]]
                   (assoc entry
                          :status :inactive
                          :missing-requirements (vec missing)
                          :reason (str "Requires "
                                       (string/join ", " (for [id missing]
                                                           (format "%s (%s)" id (if-let [status (get status-by-id id)]
                                                                                  (name status)
                                                                                  (if (some #(= id (:id %)) enabled)
                                                                                    "inactive"
                                                                                    "not installed"))))))))]
    {:active active
     :active-ids active-ids
     :declared declared
     :statuses (vec (sort-by (juxt (comp str :id) (comp str :source))
                             (concat active disabled (vals conflicting) unplaced refused load-failed)))}))

(def ^:private resolution-cache* (atom nil))

(defn resolution
  "Which modules are active under `config` (only its `modules` key matters):
   `{:active [entries in start order] :active-ids #{..} :declared {path {:spec :module}}
     :statuses [every module with :status and :reason]}`. An entry is
   `{:id :module :source :built-in? :priority :status}`."
  [config]
  (let [mc (modules-config config)
        registry @registry*
        built-ins (built-in-modules)
        k [mc (:version registry)]
        cached @resolution-cache*]
    (if (and cached (= k (:key cached)) (identical? built-ins (:built-ins cached)))
      (:value cached)
      (let [value (resolve* mc registry built-ins)]
        (reset! resolution-cache* {:key k :built-ins built-ins :value value})
        value))))

(defn active
  "The active modules under `config`, in start order."
  [config]
  (:active (resolution config)))

(defn active?
  [config id]
  (contains? (:active-ids (resolution config)) id))

(defn active-entry
  [config id]
  (first (filter #(= id (:id %)) (active config))))

(defn contributions
  "`[entry value]` for each active module that has key `k`, in start order."
  [config k]
  (for [entry (active config)
        :let [v (get (:module entry) k)]
        :when (some? v)]
    [entry v]))

(defn find-contribution
  "The first active module that contributes `contribution-id` under `k`, e.g.
   `:apis` \"anthropic\": `[entry value]`, or nil."
  [config k contribution-id]
  (some (fn [entry]
          (when-let [v (get-in (:module entry) [k contribution-id])]
            [entry v]))
        (active config)))

(defn prefix
  "The prefix of a module's tools and commands: `:prefix`, else `eca` for
   built-ins and the name part of the id for external modules."
  [{:keys [module built-in?]}]
  (or (:prefix module)
      (if built-in? "eca" (name (:id module)))))

;; --- Guarded calls

(def ^:private executor
  (delay
    (Executors/newCachedThreadPool
     (reify ThreadFactory
       (newThread [_ runnable]
         (doto (Thread. ^Runnable runnable "eca-module-call")
           (.setDaemon true)))))))

(defn ^:private run-with-timeout [timeout-ms f]
  (let [context (logger/capture-context)
        task (bound-fn [] (logger/with-context context (f)))
        ^Future fut (.submit ^ExecutorService @executor ^Callable task)]
    (try
      (.get fut (long timeout-ms) TimeUnit/MILLISECONDS)
      (catch TimeoutException _
        (.cancel fut true)
        (throw (ex-info (format "Timed out after %d ms" timeout-ms) {::timeout-ms timeout-ms})))
      (catch ExecutionException e
        (throw (or (.getCause e) e)))
      (catch InterruptedException e
        (.cancel fut true)
        (throw e)))))

(defn module-ctx
  "The ctx a module's function gets: the caller's ctx plus the module, and the
   session's components (`:db*`, `:messenger`, `:metrics`) when the caller
   didn't pass them, so `eca.api` works wherever a module runs."
  [ctx entry]
  (assoc (merge (:components @registry*) ctx) :module-id (:id entry)))

(declare fail!)

(defn ^:private record-success! [id]
  (when (pos? (get-in @registry* [:failures id] 0))
    (swap! registry* assoc-in [:failures id] 0)))

(defn ^:private record-failure! [ctx entry what ^Throwable e]
  (let [id (:id entry)
        message (or (ex-message e) (str e))
        registry (swap! registry* (fn [registry]
                                    (-> registry
                                        (update-in [:failures id] (fnil inc 0))
                                        (update-in [:errors id]
                                                   (fn [errors]
                                                     (vec (take-last 5 (conj (vec errors)
                                                                             {:what what
                                                                              :message message
                                                                              :at (System/currentTimeMillis)}))))))))
        failures (get-in registry [:failures id])]
    (logger/error logger-tag (format "Module %s failed in %s: %s" id what message) e)
    (when (and (not (:built-in? entry)) (>= failures max-failures))
      (fail! ctx id (format "Disabled for this session after %d failures in a row. Last error, in %s: %s"
                            failures what message)))))

(defn invoke
  "Calls `(apply f args)` on behalf of module `entry`. Errors are logged,
   recorded for `/modules` and counted as failures, then rethrown; after
   `max-failures` in a row an external module is disabled for the session.
   With a `timeout-ms`, the call runs on another thread and fails after it."
  [ctx entry what {:keys [timeout-ms]} f & args]
  (try
    (let [v (if (and timeout-ms (pos? timeout-ms))
              (run-with-timeout timeout-ms #(apply f args))
              (apply f args))]
      (record-success! (:id entry))
      v)
    (catch InterruptedException e (throw e))
    (catch CancellationException e (throw e))
    (catch Throwable e
      (record-failure! ctx entry what e)
      (throw e))))

(defn invoke-safe
  "Like `invoke`, but returns nil when the call fails."
  [ctx entry what opts f & args]
  (try
    (apply invoke ctx entry what opts f args)
    (catch InterruptedException e (throw e))
    (catch CancellationException e (throw e))
    (catch Throwable _ nil)))

(defn timeout-for
  "The timeout of a call into `entry`: `explicit` when set; otherwise
   `default-ms` for external modules and none for built-ins, which run on the
   calling thread like the code they replace."
  [entry explicit default-ms]
  (or explicit (when-not (:built-in? entry) default-ms)))

;; --- Background jobs (eca.api/background!)

(def default-pool-size
  "Threads of a module's background pool when it doesn't say."
  4)

(def ^:private pools-lock (Object.))

(defn ^:private pool-thread-factory ^ThreadFactory [id pool-name]
  (let [n (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable (format "eca-module-%s-%s-%d" (symbol id) (name pool-name) (swap! n inc)))
          (.setDaemon true))))))

(defn ^:private pool! ^ExecutorService [id pool-name size]
  (locking pools-lock
    (or (get-in @registry* [:pools id pool-name])
        (let [executor (Executors/newFixedThreadPool (int size) (pool-thread-factory id pool-name))]
          (swap! registry* assoc-in [:pools id pool-name] executor)
          executor))))

(defn ^:private shutdown-pools!
  "Stops the background pools of module `id`, interrupting their jobs."
  [id]
  (let [pools (locking pools-lock
                (let [pools (get-in @registry* [:pools id])]
                  (swap! registry* update :pools dissoc id)
                  pools))]
    (doseq [[_ ^ExecutorService executor] pools]
      (.shutdownNow executor))))

(defn background!
  "Runs `(f)` on a thread of module `id`'s background pool `:pool` (`:default`
   when not given), guarded like any call into the module. A pool gets
   `:size` threads (`default-pool-size`) when first used; its jobs queue
   when they're all busy. The pools stop, interrupting their jobs, when the
   module stops. Returns the job's java Future."
  [ctx id {:keys [pool size] :or {pool :default size default-pool-size}} f]
  (let [entry (or (get-in @registry* [:running id :entry])
                  (when (map? (:config ctx)) (active-entry (:config ctx) id))
                  (throw (ex-info (str "Module " id " isn't active") {:module id})))
        executor (pool! id pool size)
        context (logger/capture-context)
        job (fn []
              (logger/with-context context
                (invoke-safe ctx entry (str "background job in pool " (name pool)) {} f)))]
    (.submit executor ^Callable job)))

;; --- Registrations made at runtime (register-tool!, register-command!)

(defn register!
  "Records a registration of module `id`, undone when the module stops."
  [id kind reg-name value]
  (swap! registry* assoc-in [:registrations id kind reg-name] value))

(defn unregister!
  [id kind reg-name]
  (swap! registry* update-in [:registrations id kind] dissoc reg-name))

(defn registrations
  "`[entry name value]` for each runtime registration of `kind` by an active
   module."
  [config kind]
  (let [regs (:registrations @registry*)]
    (for [entry (active config)
          [reg-name value] (sort-by key (get-in regs [(:id entry) kind]))]
      [entry reg-name value])))

;; --- Lifecycle

(defn state
  "The value `:start` returned for module `id`."
  [id]
  (get-in @registry* [:state id]))

(defn swap-state!
  "Swaps module `id`'s state."
  [id f & args]
  (get-in (swap! registry* (fn [registry] (apply update-in registry [:state id] f args)))
          [:state id]))

(defn running
  "The modules whose `:start` ran, in start order: [{:entry :spec-values}]."
  []
  (let [{:keys [running start-order]} @registry*]
    (keep #(get running %) start-order)))

(defn running? [id]
  (contains? (:running @registry*) id))

(defn ^:private spec-values-of
  "The configured values of the keys `entry` declares."
  [entry config]
  (into {} (for [[path _] (config-spec/leaves (:config-spec (:module entry)))]
             [path (when (map? config) (get-in config path))])))

(defn ^:private stop-module! [components {:keys [entry]}]
  (let [id (:id entry)
        stop (:stop (:module entry))]
    (when stop
      (let [ctx (module-ctx components entry)]
        (invoke-safe ctx entry "stop" {:timeout-ms (timeout-for entry nil default-start-timeout-ms)} stop ctx)))
    (shutdown-pools! id)
    (swap! registry* (fn [registry]
                       (-> registry
                           (update :running dissoc id)
                           (update :start-order (fn [order] (filterv #(not= id %) order)))
                           (update :state dissoc id)
                           (update :registrations dissoc id))))
    (logger/info logger-tag (format "Stopped module %s" id))))

(defn ^:private start-module! [components config entry]
  (let [id (:id entry)
        start (:start (:module entry))
        ctx (module-ctx (assoc components :config config) entry)]
    (try
      (let [value (when start
                    (invoke ctx entry "start" {:timeout-ms (timeout-for entry nil default-start-timeout-ms)} start ctx))]
        (swap! registry* (fn [registry]
                           (-> registry
                               (assoc-in [:state id] value)
                               (assoc-in [:running id] {:entry entry :spec-values (spec-values-of entry config)})
                               (update :start-order (fnil conj []) id))))
        (logger/info logger-tag (format "Started module %s" id))
        true)
      (catch Throwable e
        (fail! components id (str "Failed to start: " (or (ex-message e) (str e))) {:reconcile? false})
        false))))

(def ^:private lifecycle-lock (Object.))

(defn reconcile!
  "Makes the running modules match the active ones under `config`: stops the
   ones no longer active (and drops their registrations), starts the active
   ones with a `:start` that aren't running, and restarts external modules
   that were reloaded or whose `:config-spec` keys changed. Modules that
   require a stopped module stop too, and start again with it."
  [components config]
  (locking lifecycle-lock
    (loop [attempts 0]
      (let [{:keys [active active-ids]} (resolution config)
            active-by-id (into {} (map (juxt :id identity)) active)
            stale (filter (fn [{:keys [entry spec-values]}]
                            (let [current (get active-by-id (:id entry))]
                              (or (nil? current)
                                  (not (identical? (:module current) (:module entry)))
                                  ;; built-ins apply config changes themselves, as before modules
                                  (and (not (:built-in? entry))
                                       (not= spec-values (spec-values-of current config))))))
                          (reverse (running)))]
        (doseq [r stale]
          (stop-module! components r))
        (swap! registry* update :registrations select-keys active-ids)
        ;; modules without :start never run, so they don't stop: drop their pools here
        (doseq [id (keys (:pools @registry*))
                :when (not (contains? active-ids id))]
          (shutdown-pools! id))
        (let [started-ok? (every? (fn [entry]
                                    (or (running? (:id entry))
                                        (nil? (:start (:module entry)))
                                        (start-module! components config entry)))
                                  active)]
          (when (and (not started-ok?) (< attempts 50))
            ;; a start failed: the active set changed, so its dependents must not run
            (recur (inc attempts))))))))

(defn start!
  "Starts the session's modules, and keeps its components for `module-ctx`.
   Until then, `reconcile-if-started!` does nothing, so config changes can't
   start modules early."
  [components config]
  (swap! registry* assoc
         :session-started? true
         :components (select-keys components [:db* :messenger :metrics]))
  (reconcile! components config))

(defn started?
  "Whether `start!` ran, and `stop-all!` didn't since."
  []
  (boolean (:session-started? @registry*)))

(defn components
  "The session's components `start!` got: `{:db* :messenger :metrics}`, or nil
   before it ran."
  []
  (:components @registry*))

(defn reconcile-if-started!
  "`reconcile!`, once `start!` ran."
  [components config]
  (when (started?)
    (reconcile! components config)))

(defn stop-all!
  "Stops every running module, in reverse start order."
  [components]
  (locking lifecycle-lock
    (swap! registry* assoc :session-started? false)
    (doseq [r (reverse (running))]
      (stop-module! components r))
    (doseq [id (keys (:pools @registry*))]
      (shutdown-pools! id))))

(defn fail!
  "Marks module `id` as failed for the session: it becomes inactive, with
   `reason` in `/modules`, and modules that require it stop too."
  ([ctx id reason] (fail! ctx id reason {}))
  ([ctx id reason {:keys [reconcile?] :or {reconcile? true}}]
   (swap-inputs! assoc-in [:failed id] {:reason reason :at (System/currentTimeMillis)})
   (logger/warn logger-tag (format "Module %s failed: %s" id reason))
   (when-let [m (:messenger ctx)]
     (try
       (messenger/showMessage m {:type :warning :message (format "ECA module %s: %s" id reason)})
       (catch Exception _)))
   (when (and reconcile? (:messenger ctx) (map? (:config ctx)))
     (shared/future* (:config ctx)
       (try
         (reconcile-if-started! ctx (:config ctx))
         (catch Exception e
           (logger/error logger-tag "Could not stop modules after a failure:" e)))))))

(defn set-enabled!
  "Enables or disables module `id` for the session, above the config. Enabling
   clears a failure."
  [id enabled?]
  (swap-inputs! (fn [registry]
                  (cond-> (assoc-in registry [:overrides id] (boolean enabled?))
                    enabled? (-> (update :failed dissoc id)
                                 (update :failures dissoc id))))))

(defn clear-failure!
  [id]
  (swap-inputs! (fn [registry] (-> registry (update :failed dissoc id) (update :failures dissoc id)))))

(defn restart!
  "Stops and starts module `id` again, clearing a failure."
  [components config id]
  (locking lifecycle-lock
    (when-let [r (get (:running @registry*) id)]
      (stop-module! components r))
    (clear-failure! id)
    (reconcile! components config)))

(defn errors
  "The last errors of module `id`."
  [id]
  (get-in @registry* [:errors id]))

(defn failures
  [id]
  (get-in @registry* [:failures id] 0))

;; --- Config

(defn ^:private merge-order
  "Active modules in the order their `:config-defaults` merge, later wins: a
   module after the modules it requires, directly or not; otherwise lower
   priority first, then id. So among equal priorities the higher id wins, and
   entries of shared maps keep id order, e.g. agents code, plan, explorer."
  [active]
  (let [compare-fn (fn [a b]
                     (let [c (compare (:priority a) (:priority b))]
                       (if (zero? c) (compare (:id a) (:id b)) c)))]
    (loop [pending (vec active) placed-ids #{} ordered []]
      (if-let [next-entry (pick-next pending placed-ids compare-fn)]
        (recur (filterv #(not= (:id %) (:id next-entry)) pending)
               (conj placed-ids (:id next-entry))
               (conj ordered next-entry))
        ordered))))

(defn default-config
  "The config defaults the active modules under `config` declare, lowest
   first: their `:config-spec` defaults, then their `:config-defaults` merged
   in `merge-order`. `db` is what a `:config-defaults` function can read
   (e.g. the workspaces)."
  [config db]
  (let [{:keys [active]} (resolution config)
        spec-defaults (reduce (fn [acc entry]
                                (shared/deep-merge acc (config-spec/defaults (:config-spec (:module entry)))))
                              {}
                              active)]
    (reduce (fn [acc entry]
              (let [d (:config-defaults (:module entry))
                    d (if (fn-like? d)
                        ;; only the db: with the components, (eca/config ctx) would
                        ;; resolve the config being computed
                        (let [ctx {:db db :module-id (:id entry)}]
                          (invoke-safe ctx entry "config-defaults"
                                       {:timeout-ms (timeout-for entry nil default-handler-timeout-ms)} d ctx))
                        d)]
                (if (map? d) (shared/deep-merge acc d) acc)))
            spec-defaults
            (merge-order active))))

(defn contributed-agents
  "The agents the active modules under `config` add, name → agent config, from
   their `:agents` functions; later modules win a name. `db` is what those
   functions can read, e.g. the workspaces. The config's own agents win over
   these."
  [config db]
  (reduce (fn [acc [entry f]]
            (let [ctx {:db db :module-id (:id entry)}
                  agents (invoke-safe ctx entry "agents"
                                      {:timeout-ms (timeout-for entry nil default-handler-timeout-ms)} f ctx)]
              (if (map? agents) (merge acc agents) acc)))
          {}
          (contributions config :agents)))

(defn defaults-signature
  "What decides `default-config` under `config`: the active modules in merge
   order, with their priorities."
  [config]
  (mapv (juxt :id :priority) (merge-order (active config))))

(defn declared-keys
  "path → {:spec :module} for every config key an active module owns."
  [config]
  (:declared (resolution config)))

(defn external-map-paths
  "The paths of the `:map` keys active external modules declare and no
   built-in module does, e.g. `[:lspServers]`: their entries keep string
   names, as in ECA's own `mcpServers`."
  [config]
  (let [built-in-paths (into #{} (mapcat (comp keys config-spec/leaves :config-spec)) (built-in-modules))]
    (->> (active config)
         (remove :built-in?)
         (mapcat (fn [{:keys [module]}]
                   (for [[path spec] (config-spec/leaves (:config-spec module))
                         :when (and (= :map (:type spec)) (not (contains? built-in-paths path)))]
                     path)))
         distinct
         vec)))

(defn known-root-keys
  "The root config keys some module declares: built-in ones, and external ones
   loaded, active or not."
  []
  (into #{}
        (comp (mapcat (comp keys config-spec/leaves :config-spec))
              (map first))
        (concat (built-in-modules)
                (keep :module (vals (external-modules))))))

(defn statuses
  "Every module with its status, reason, warnings, failures and errors."
  [config]
  (for [entry (:statuses (resolution config))]
    (assoc entry
           :running? (running? (:id entry))
           :failures (failures (:id entry))
           :errors (errors (:id entry)))))
