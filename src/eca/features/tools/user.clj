(ns eca.features.tools.user
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [eca.features.tools.util :as tools.util]
            [eca.logger :as logger]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS-USER]")

;; ============================================================================
;; Schema Validation
;; ============================================================================

(s/def ::arg-type #{"string" "number" "boolean" "array" "object"})
(s/def ::arg-spec (s/keys :req-un [::type ::description]))
(s/def ::args (s/map-of string? ::arg-spec))
(s/def ::required (s/coll-of string?))
(s/def ::schema (s/keys :req-un [::required ::args]))
(s/def ::tool-def (s/keys :req-un [::bash ::schema] :opt-un [::description]))

;; ============================================================================
;; Argument Processing
;; ============================================================================

(defn- shell-escape
  "Safely escape shell arguments using single quotes."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn- substitute-params
  "Replace template placeholders with escaped arguments."
  [template args]
  (reduce-kv
   (fn [cmd k v]
     (str/replace cmd
                  (re-pattern (str "\\{\\{" k "\\}\\}"))
                  (shell-escape v)))
   template
   args))

;; ============================================================================
;; Validation
;; ============================================================================

(defn- validate-tool-definition [tool-def]
  (when-not (s/valid? ::tool-def tool-def)
    (throw (ex-info "Invalid tool definition"
                    {:explain (s/explain-str ::tool-def tool-def)}))))

(defn- validate-required-args [schema args]
  (let [required (set (:required schema))
        provided (set (keys args))
        missing (clojure.set/difference required provided)]
    (when (seq missing)
      (format "Missing required arguments: %s" (str/join ", " missing)))))

(defn- validate-arg-types [schema args]
  (let [arg-specs (:args schema)]
    (reduce-kv
     (fn [errors k v]
       (if-let [spec (get arg-specs k)]
         (let [expected-type (:type spec)
               actual-value v]
           (case expected-type
             "string" (if (string? actual-value) errors
                          (conj errors (format "Argument '%s' must be a string" k)))
             "number" (if (number? actual-value) errors
                          (conj errors (format "Argument '%s' must be a number" k)))
             "boolean" (if (boolean? actual-value) errors
                           (conj errors (format "Argument '%s' must be a boolean" k)))
             errors))
         errors))
     []
     args)))

(defn- validate-args [schema args]
  (or (validate-required-args schema args)
      (when-let [type-errors (seq (validate-arg-types schema args))]
        (str/join "; " type-errors))))

;; ============================================================================
;; Command Execution
;; ============================================================================

(defn- determine-work-dir [db]
  (or (some-> (:workspace-folders db) first :uri)
      (System/getProperty "user.home")))

(defn- execute-command [cmd work-dir]
  (try
    (p/shell {:dir work-dir
              :out :string
              :err :string
              :continue true}
             "bash" "-c" cmd)
    (catch Exception e
      {:exit 1 :err (.getMessage e)})))

(defn- format-error-result [result]
  (let [err (some-> (:err result) str/trim)
        out (some-> (:out result) str/trim)]
    {:error true
     :contents (cond-> [{:type :text :text (str "Exit code " (:exit result))}]
                 (not (str/blank? err))
                 (conj {:type :text :text (str "Stderr:\n" err)})

                 (not (str/blank? out))
                 (conj {:type :text :text (str "Stdout:\n" out)}))}))

;; ============================================================================
;; Tool Handler Factory
;; ============================================================================

(defn- create-user-tool-handler [tool-def]
  (validate-tool-definition tool-def)
  (let [schema (:schema tool-def)
        bash-template (:bash tool-def)]
    (fn [arguments {:keys [db config behavior]}]
      (if-let [validation-error (validate-args schema arguments)]
        {:error true :contents [{:type :text :text validation-error}]}
        (let [cmd (substitute-params bash-template arguments)
              work-dir (determine-work-dir db)
              result (execute-command cmd work-dir)]
          (logger/debug logger-tag "User tool executed:" result)
          (if (zero? (:exit result))
            (tools.util/single-text-content (:out result))
            (format-error-result result)))))))

;; ============================================================================
;; Tool Definition Creation
;; ============================================================================

(defn- create-tool-parameters [schema]
  {:type "object"
   :properties (into {}
                     (for [[k v] (:args schema)]
                       [k {:type (:type v)
                           :description (:description v)}]))
   :required (:required schema)})

(defn- create-tool-definition [name tool-def]
  (merge tool-def
         {:name name
          :handler (create-user-tool-handler tool-def)
          :parameters (create-tool-parameters (:schema tool-def))
          :description (or (:description tool-def)
                           (str "User-defined tool: " name))}))

;; ============================================================================
;; Public API
;; ============================================================================

(defn user-tool-definitions [config]
  (let [user-tools (get config :userTools)]
    (try
      (into {}
            (for [[name defn] user-tools]
              [name (create-tool-definition name defn)]))
      (catch Exception e
        (logger/error logger-tag "Error creating user tool definitions:" (.getMessage e))
        {}))))

;; Expose for testing
(def substitute-params substitute-params)
(def validate-args validate-args)
(def user-tool-handler create-user-tool-handler)
