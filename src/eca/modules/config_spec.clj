(ns eca.modules.config-spec
  "A module's `:config-spec`: the ECA config keys it owns. The spec is a tree
   that mirrors the config: a map with `:type` is a key's spec, any other map
   is a level, e.g.

     {:jiraUrl {:type :string :doc \"Jira base URL\"}
      :toolCall {:readFile {:maxLines {:type :number :default 2000}}}}"
  (:require
   [clojure.string :as string]))

(set! *warn-on-reflection* true)

(def types #{:string :number :boolean :enum :map :array})

(def ^:private spec-keys #{:type :default :doc :values :secret?})

(defn key-spec?
  "Whether `node` is a key's spec rather than a level."
  [node]
  (and (map? node) (contains? node :type)))

(defn path-str
  "A config path as users write it, e.g. `toolCall.readFile.maxLines`."
  [path]
  (string/join "." (map #(if (keyword? %) (subs (str %) 1) (str %)) path)))

(defn leaves
  "Map of path → key spec for every key the tree declares."
  [tree]
  (letfn [(walk [path node]
            (if (key-spec? node)
              [[path node]]
              (when (map? node)
                (mapcat (fn [[k v]] (walk (conj path k) v)) node))))]
    (if (and (map? tree) (not (key-spec? tree)))
      (into {} (mapcat (fn [[k v]] (walk [k] v)) tree))
      {})))

(defn valid-value?
  "Whether `value` fits the key spec. `nil` always fits: it means unset."
  [{:keys [type values]} value]
  (or (nil? value)
      (case type
        :string (string? value)
        :number (number? value)
        :boolean (boolean? value)
        :enum (boolean (some #(= % value) values))
        :map (map? value)
        :array (sequential? value)
        false)))

(defn ^:private leaf-problems [path {:keys [type values doc secret?] :as spec}]
  (let [where (path-str path)
        unknown (remove spec-keys (keys spec))]
    (cond-> []
      (not (contains? types type))
      (conj (format "%s: :type must be one of %s, got %s" where (string/join ", " (sort types)) (pr-str type)))

      (and (= :enum type) (not (and (sequential? values) (seq values))))
      (conj (format "%s: an :enum needs a non-empty :values vector" where))

      (and (some? doc) (not (string? doc)))
      (conj (format "%s: :doc must be a string" where))

      (and (some? secret?) (not (boolean? secret?)))
      (conj (format "%s: :secret? must be a boolean" where))

      (seq unknown)
      (conj (format "%s: unknown spec keys %s" where (string/join ", " (map pr-str unknown))))

      (and (contains? types type)
           (not (valid-value? spec (:default spec))))
      (conj (format "%s: :default %s doesn't match :type %s" where (pr-str (:default spec)) type)))))

(defn problems
  "Problems in a `:config-spec` tree, as strings. Empty when valid."
  [tree]
  (cond
    (nil? tree) []
    (not (map? tree)) [":config-spec must be a map"]
    (key-spec? tree) [":config-spec must be a tree of keys, not one key's spec"]
    :else
    (letfn [(check [path node]
              (cond
                (key-spec? node) (leaf-problems path node)
                (and (map? node) (empty? node)) [(format "%s: an empty level declares no key" (path-str path))]
                (map? node) (mapcat (fn [[k v]] (check (conj path k) v)) node)
                :else [(format "%s: expected a key spec (a map with :type) or a level (a map)" (path-str path))]))]
      (vec (concat
            (for [k (keys tree) :when (not (keyword? k))]
              (format "%s: config keys must be keywords" (pr-str k)))
            (mapcat (fn [[k v]] (check [k] v)) tree))))))

(defn defaults
  "The config the tree's `:default`s describe."
  [tree]
  (reduce-kv (fn [config path spec]
               (if (some? (:default spec))
                 (assoc-in config path (:default spec))
                 config))
             {}
             (leaves tree)))

(defn ^:private same-spec? [a b]
  (= (dissoc a :doc) (dissoc b :doc)))

(defn conflicts
  "Problems with declaring `tree` next to `declared`, a map of path →
   {:spec key-spec :module module-id} of keys other modules already own.
   The same key is fine when both specs agree; a path can't be a key in one
   module and a level in another."
  [declared tree]
  (let [declared-levels (into #{} (mapcat (fn [path] (map #(subvec path 0 %) (range 1 (count path)))))
                              (keys declared))]
    (vec
     (for [[path spec] (leaves tree)
           :let [where (path-str path)
                 same (get declared path)
                 key-above (some (fn [n] (when-let [owner (get declared (subvec path 0 n))]
                                           [(subvec path 0 n) owner]))
                                 (range 1 (count path)))
                 problem (cond
                           (and same (not (same-spec? (:spec same) spec)))
                           (format "%s: %s declares it with another spec" where (:module same))

                           key-above
                           (let [[above {:keys [module]}] key-above]
                             (format "%s: %s declares %s as a key, so it can't be a level" where module (path-str above)))

                           (contains? declared-levels path)
                           (format "%s: other modules declare keys inside it, so it can't be a key" where))]
           :when problem]
       problem))))

(defn type-problems
  "Configured values that don't fit their key spec: `[{:path :value :type}]`.
   `declared` is a map of path → {:spec key-spec ...}."
  [declared config]
  (vec
   (for [[path {:keys [spec]}] (sort-by (comp path-str key) declared)
         :let [value (when (map? config) (get-in config path))]
         :when (not (valid-value? spec value))]
     {:path path :value value :type (:type spec)})))

(defn unknown-root-keys
  "Root keys of `config` that no declared path starts with."
  [declared config]
  (let [roots (into #{} (map first) (keys declared))]
    (into (sorted-set) (remove roots) (when (map? config) (keys config)))))

(defn masked
  "The config with the values of `:secret?` keys masked."
  [declared config]
  (reduce-kv (fn [config path {:keys [spec]}]
               (if (and (:secret? spec) (some? (get-in config path)))
                 (assoc-in config path "****")
                 config))
             config
             declared))
