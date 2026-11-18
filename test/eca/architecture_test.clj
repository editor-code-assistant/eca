(ns eca.architecture-test
  "Core is layered and doesn't depend on the built-in modules: they extend it through module keys and events
  (docs/development.md). These tests read the sources, so they load nothing from src."
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]))

(def ^:private layers
  "Core namespaces by layer, lowest first: a namespace requires only its own layer and lower ones."
  [[:libs '#{eca.logger eca.cache eca.digest eca.diff eca.secrets eca.interpolation eca.oauth eca.messenger
             eca.message-sanitize eca.shared}]
   [:runtime '#{eca.modules eca.modules.events eca.modules.config-spec eca.modules.data eca.shell}]
   [:state #(or (contains? '#{eca.config eca.db eca.metrics eca.network eca.client-http eca.llm-util eca.models} %)
                (string/starts-with? (str %) "eca.db."))]
   [:loader+api #(or (= 'eca.modules.loader %) (= 'eca.api %) (string/starts-with? (str %) "eca.api."))]
   [:engines #(or (string/starts-with? (str %) "eca.features.")
                  (contains? '#{eca.llm-api eca.llm-api.auth eca.llm-api.errors} %))]
   [:wiring '#{eca.server eca.handlers eca.main eca.nrepl eca.read-chat eca.modules.built-in}]])

(def ^:private allowed-module-requires
  "Core namespaces that still require a built-in module (the built-in list aside). Only shrinks."
  '#{;; startup: plugins, remote and otel start with the server
     [eca.handlers eca.modules.plugins]
     [eca.handlers eca.modules.remote]
     [eca.server eca.modules.otel]
     [eca.server eca.modules.remote]
     [eca.server eca.modules.remote.messenger]
     [eca.server eca.modules.remote.server]})

(def ^:private allowed-id-checks
  "Built-in module ids core names in its calls into the module runtime, e.g. `(modules/active? config :eca/x)`,
  by namespace and id, with their count. Only shrinks."
  '{;; startup: remote and otel start with the server and run until ECA exits
    [eca.handlers :eca/otel] 1
    [eca.handlers :eca/remote] 2
    [eca.server :eca/otel] 1
    ;; --repl
    [eca.main :eca/repl] 1})

(def ^:private allowed-upward-resolves
  "Core namespaces that resolve a var of a higher layer at runtime (`requiring-resolve`). Only shrinks."
  '#{;; the module API calls the engines for the modules
     [eca.api eca.features.chat]
     [eca.api eca.features.chat.compaction]
     [eca.api eca.features.tools]
     [eca.api eca.llm-api]
     [eca.api eca.llm-api.auth]})

(def ^:private allowed-extended-multimethods
  "Core multimethods the built-in modules extend with `defmethod`. Only shrinks."
  '#{})

(defn ^:private libspecs
  "The libs of a `:require` clause's specs, as maps of `:ns` and the options (`:as`...); prefix lists expanded."
  [specs]
  (mapcat (fn spec->libs [spec]
            (cond
              (symbol? spec) [{:ns spec}]
              (and (sequential? spec) (symbol? (first spec)))
              (let [[lib & opts] spec]
                (if (or (empty? opts) (keyword? (first opts)))
                  [(assoc (apply hash-map opts) :ns lib)]
                  (for [sub opts
                        lib-map (spec->libs sub)]
                    (update lib-map :ns #(symbol (str lib "." %))))))
              :else []))
          specs))

(defn ^:private code-text
  "`text` without its comments and the content of its strings, so the checks below only see code."
  [^String text]
  (let [sb (StringBuilder.)
        n (count text)]
    (loop [i 0 state :code]
      (if (>= i n)
        (str sb)
        (let [c (.charAt text i)]
          (case state
            :code (cond
                    ;; a character literal, e.g. \" or \;
                    (= c \\) (do (.append sb (subs text i (min n (+ i 2)))) (recur (+ i 2) :code))
                    (= c \") (do (.append sb c) (recur (inc i) :string))
                    (= c \;) (recur (inc i) :comment)
                    :else (do (.append sb c) (recur (inc i) :code)))
            :string (cond
                      (= c \\) (recur (+ i 2) :string)
                      (= c \") (do (.append sb c) (recur (inc i) :code))
                      :else (recur (inc i) :string))
            :comment (if (= c \newline)
                       (do (.append sb c) (recur (inc i) :code))
                       (recur (inc i) :comment))))))))

(def ^:private sources
  (delay
    (doall
     (for [^java.io.File f (file-seq (io/file "src"))
           :when (string/ends-with? (.getName f) ".clj")
           :let [text (slurp f)
                 [_ ns-name & clauses] (binding [*read-eval* false] (read-string text))
                 libs (->> clauses
                           (filter #(and (seq? %) (= :require (first %))))
                           (mapcat (comp libspecs rest)))]]
       {:ns ns-name
        :text text
        :code (code-text text)
        :requires (set (map :ns libs))
        :aliases (into {} (keep (fn [{:keys [ns as as-alias]}]
                                  (when-let [alias (or as as-alias)] [alias ns])))
                       libs)}))))

(defn ^:private layer-of [ns-sym]
  (first (keep-indexed (fn [i [_ in-layer?]] (when (in-layer? ns-sym) i)) layers)))

(defn ^:private layer-name [ns-sym]
  (first (get layers (layer-of ns-sym))))

(defn ^:private module-ns? [ns-sym]
  (and (not (layer-of ns-sym)) (string/starts-with? (str ns-sym) "eca.modules.")))

(defn ^:private core-sources []
  (filter (comp layer-of :ns) @sources))

(defn ^:private quoted-namespaces
  "Namespaces a source's code names as quoted symbols, as a `requiring-resolve` or a `require` does."
  [code]
  (set (map (comp symbol second) (re-seq #"'\[?(eca\.[a-z0-9.\-]+[a-z0-9\-])" code))))

(defn ^:private qualified-namespaces
  "Namespaces a source's code calls by their full name, e.g. `(eca.modules.mcp/shutdown! ..)`."
  [code]
  (set (map (comp symbol second) (re-seq #"(?<![\w.\-'])(eca\.[a-z0-9.\-]+[a-z0-9\-])/" code))))

(defn ^:private id-checks
  "The built-in module ids a source's code names in its calls into the module runtime, e.g.
  `(modules/active? x :eca/id)` or `(modules/stop-all! ctx #{:eca/id})`."
  [{:keys [code aliases]}]
  (when-let [alias (some (fn [[alias ns]] (when (= 'eca.modules ns) alias)) aliases)]
    (->> code
         (re-seq (re-pattern (str "\\(" (java.util.regex.Pattern/quote (str alias)) "/[^\\s()\\[\\]{}]+"
                                  "((?:[^()]|\\([^()]*\\))*)\\)")))
         (mapcat (fn [[_ args]] (re-seq #":(eca/[\w\-]+)" args)))
         (map (comp keyword second))
         (remove #{:eca/core}))))

(deftest namespaces-test
  (testing "the sources are found"
    (is (seq @sources)))
  (testing "every namespace under src is in a core layer or is a built-in module's (under eca.modules)"
    (is (= [] (->> @sources (map :ns) (remove #(or (layer-of %) (module-ns? %))) sort vec)))))

(defn ^:private upward
  "`[ns to]` for each namespace of a higher layer core namespaces require (`:require`), or resolve at runtime
  (`resolved?`)."
  [resolved?]
  (set (for [{:keys [ns requires code]} (core-sources)
             to (if resolved? (set/difference (quoted-namespaces code) requires) requires)
             :when (and (layer-of to) (< (layer-of ns) (layer-of to)))]
         [ns to])))

(deftest core-layers-test
  (testing "core namespaces require only their own layer and lower ones"
    (is (= [] (->> (upward false)
                   (map (fn [[ns to]] [ns (layer-name ns) '-> to (layer-name to)]))
                   (sort-by str)
                   vec))))
  (testing "no new runtime resolve of a higher layer"
    (is (= #{} (set/difference (upward true) allowed-upward-resolves))))
  (testing "a runtime resolve of a higher layer is gone: remove it from allowed-upward-resolves"
    (is (= #{} (set/difference allowed-upward-resolves (upward true))))))

(deftest core-module-requires-test
  (let [actual (set (for [{:keys [ns requires code]} (core-sources)
                          :when (not= 'eca.modules.built-in ns)
                          to (set/union requires (quoted-namespaces code) (qualified-namespaces code))
                          :when (module-ns? to)]
                      [ns to]))]
    (testing "no new core -> module coupling: move the code into the module, or reach it with module keys or events"
      (is (= #{} (set/difference actual allowed-module-requires))))
    (testing "a coupling is gone: remove it from allowed-module-requires"
      (is (= #{} (set/difference allowed-module-requires actual))))))

(defn ^:private exceeding
  "The entries of counts `a` above their count in `b`."
  [a b]
  (into {} (remove (fn [[k n]] (<= n (get b k 0)))) a))

(deftest module-id-checks-test
  (let [actual (frequencies (for [{:keys [ns] :as source} (core-sources)
                                  id (id-checks source)]
                              [ns id]))]
    (testing "no new module-id check in core: let the module decide, through module keys or events"
      (is (= {} (exceeding actual allowed-id-checks))))
    (testing "a module-id check is gone: lower its count in allowed-id-checks"
      (is (= {} (exceeding allowed-id-checks actual))))))

(deftest extended-multimethods-test
  (let [actual (set (for [{:keys [ns text aliases]} @sources
                          :when (module-ns? ns)
                          [_ sym] (re-seq #"\(defmethod\s+([^\s()\[\]{}\"]+)" text)
                          :let [s (symbol sym)
                                target (some-> (namespace s) symbol (as-> alias (get aliases alias alias)))]
                          :when (and target (layer-of target))]
                      (symbol (str target) (name s))))]
    (testing "built-in modules extend no new core multimethod: declare what they contribute with module keys"
      (is (= #{} (set/difference actual allowed-extended-multimethods))))
    (testing "a core multimethod is no longer extended: remove it from allowed-extended-multimethods"
      (is (= #{} (set/difference allowed-extended-multimethods actual))))))

(def ^:private allowed-undeclared-module-uses
  "Built-in module namespaces that use another built-in module's namespace without requiring that module
  (`:requires`). Only shrinks."
  '#{;; eca/remote calls eca/mcp's server methods for web clients
     [eca.modules.remote.handlers eca.modules.mcp]})

(defn ^:private defined-modules
  "The built-in modules a source defines, as its `module` and `api-module` vars: id → the ids it `:requires`."
  [{:keys [code]}]
  (into {}
        (for [[_ id form] (re-seq #"(?s)\(def\s+(?:module|api-module)\s+\{:id\s+:(eca/[\w\-]+)(.*?)(?=\n\(|\z)" code)]
          [(keyword id)
           (set (map (comp keyword second)
                     (some->> (re-find #":requires\s+#\{([^}]*)\}" form) second (re-seq #":(eca/[\w\-]+)"))))])))

(deftest module-requires-test
  (let [module-sources (filter (comp module-ns? :ns) @sources)
        modules-of (into {} (keep (fn [source]
                                    (when-let [modules (not-empty (defined-modules source))]
                                      [(:ns source) modules])))
                         module-sources)
        ;; a module's namespace, or the closest one a namespace is under (a module's helpers); none for
        ;; namespaces of no module, like the providers' shared variants
        unit-of (fn [ns-sym]
                  (->> (keys modules-of)
                       (filter #(or (= % ns-sym) (string/starts-with? (str ns-sym) (str % "."))))
                       (sort-by (comp - count str))
                       first))
        actual (set (for [{:keys [ns requires code]} module-sources
                          :let [from (unit-of ns)]
                          :when from
                          to-ns (set/union requires (quoted-namespaces code) (qualified-namespaces code))
                          :let [to (unit-of to-ns)]
                          :when (and to (not= from to)
                                     (empty? (set/intersection (reduce into #{} (vals (modules-of from)))
                                                               (set (keys (modules-of to))))))]
                      [ns to-ns]))]
    (testing "the built-in modules are found"
      (is (< 40 (reduce + (map count (vals modules-of))))))
    (testing "a built-in module uses another module's namespaces only when it requires that module (:requires)"
      (is (= #{} (set/difference actual allowed-undeclared-module-uses))))
    (testing "an undeclared use is gone: remove it from allowed-undeclared-module-uses"
      (is (= #{} (set/difference allowed-undeclared-module-uses actual))))))
