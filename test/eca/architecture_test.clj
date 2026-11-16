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
   [:state '#{eca.config eca.db eca.metrics eca.network eca.client-http eca.llm-util eca.models}]
   [:loader+api #(or (= 'eca.modules.loader %) (= 'eca.api %) (string/starts-with? (str %) "eca.api."))]
   [:engines #(or (string/starts-with? (str %) "eca.features.") (contains? '#{eca.llm-api eca.llm-api.errors} %))]
   [:wiring '#{eca.server eca.handlers eca.main eca.nrepl eca.read-chat eca.modules.built-in}]])

(def ^:private allowed-module-requires
  "Core namespaces that still require a built-in module (the built-in list aside). Only shrinks."
  '#{[eca.features.chat eca.modules.mcp]
     [eca.features.chat eca.modules.repo-map]
     [eca.features.chat eca.modules.rules]
     [eca.features.chat eca.modules.skills]
     [eca.features.chat.tool-calls eca.modules.mcp]
     [eca.features.commands eca.modules.custom-commands]
     [eca.features.commands eca.modules.mcp]
     [eca.features.commands eca.modules.skills]
     [eca.features.context eca.modules.mcp]
     [eca.features.tools eca.modules.mcp]
     [eca.handlers eca.modules.mcp]
     ;; startup: plugins, remote and otel start with the server
     [eca.handlers eca.modules.plugins]
     [eca.handlers eca.modules.remote]
     [eca.server eca.modules.otel]
     [eca.server eca.modules.remote]
     [eca.server eca.modules.remote.messenger]
     [eca.server eca.modules.remote.server]})

(def ^:private allowed-id-checks
  "`(modules/active? config :eca/x)` calls in core, by namespace and id, with their count. Only shrinks."
  '{[eca.features.chat.lifecycle :eca/compact-as-summary] 1
    [eca.features.commands :eca/custom-commands] 2
    [eca.features.commands :eca/mcp] 1
    [eca.features.commands :eca/skills] 2
    [eca.features.tools :eca/mcp] 3
    [eca.features.tools :eca/tool-search] 1
    [eca.handlers :eca/remote] 1
    [eca.server :eca/otel] 1})

(def ^:private allowed-extended-multimethods
  "Core multimethods the built-in modules extend with `defmethod`. Only shrinks."
  '#{eca.features.login/login-step
     eca.features.providers/complete-oauth-code!
     eca.features.providers/start-login!
     eca.features.quota/fetch-quota
     eca.features.tools.util/tool-call-destroy-resource!
     eca.features.tools.util/tool-call-details-after-invocation
     eca.features.tools.util/tool-call-details-before-invocation
     eca.llm-api.errors/enrich-provider-error
     eca.llm-api.errors/recover-error!
     eca.llm-api.errors/recoverable-error?
     eca.llm-util/provider-models-override})

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
  "Namespaces a source names as quoted symbols, as a `requiring-resolve` does."
  [text]
  (set (map (comp symbol second) (re-seq #"'(eca\.[a-z0-9.\-]+[a-z0-9\-])" text))))

(defn ^:private id-checks
  "The module ids a source's `(modules/active? x :some/id)` calls name."
  [{:keys [text aliases]}]
  (when-let [alias (some (fn [[alias ns]] (when (= 'eca.modules ns) alias)) aliases)]
    (->> text
         (re-seq (re-pattern (str "\\(" (java.util.regex.Pattern/quote (str alias)) "/active\\?"
                                  "\\s+(?:[^\\s()]+|\\([^()]*\\))\\s+:([\\w.\\-]+/[\\w\\-]+)\\)")))
         (map (comp keyword second)))))

(deftest namespaces-test
  (testing "every namespace under src is in a core layer or is a built-in module's (under eca.modules)"
    (is (= [] (->> @sources (map :ns) (remove #(or (layer-of %) (module-ns? %))) sort vec)))))

(deftest core-layers-test
  (testing "core namespaces require only their own layer and lower ones"
    (is (= [] (->> (for [{:keys [ns requires]} (core-sources)
                         to requires
                         :when (and (layer-of to) (< (layer-of ns) (layer-of to)))]
                     [ns (layer-name ns) '-> to (layer-name to)])
                   (sort-by str)
                   vec)))))

(deftest core-module-requires-test
  (let [actual (set (for [{:keys [ns requires text]} (core-sources)
                          :when (not= 'eca.modules.built-in ns)
                          to (set/union requires (quoted-namespaces text))
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
