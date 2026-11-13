(ns eca.features.create
  "The `:eca/agent-create` module: the `create` agent, which helps the user
   configure and extend ECA, from config to modules. Its tools are offered
   only in its chats: `create__guide` reads ECA's guide (its docs and the
   eca.api reference), `create__modules` shows the modules and their errors,
   and `create__eval` evaluates Clojure in the module runtime, to test,
   install and reload modules."
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.features.guide :as guide]
   [eca.features.modules :as f.modules]
   [eca.features.tools.util :as tools.util]
   [eca.modules :as modules]
   [eca.modules.loader :as loader]
   [eca.shared :refer [multi-str]]
   [sci.core :as sci])
  (:import
   [java.io Writer]
   [java.util.concurrent ExecutionException]))

(set! *warn-on-reflection* true)

(def agent-name "create")

(defn ^:private create-chat?
  "Whether the tools are offered: only in the create agent's chats."
  [ctx]
  (= agent-name (:agent ctx)))

;; --- create__guide

(defn ^:private index-text []
  (multi-str
   "ECA's guide. Call create__guide again with one of these topics:"
   ""
   (for [{:keys [topic description]} (guide/index)]
     (str "- " topic ": " description))))

(defn ^:private resolve-ref
  "Schema `s` with its `$ref` to `#/definitions/<name>` replaced by that
   definition; `s`'s other keys, e.g. its description, win."
  [schema s]
  (if-let [[_ def-name] (and (map? s) (some->> (get s "$ref") (re-matches #"#/definitions/(.+)")))]
    (merge (get-in schema ["definitions" def-name]) (dissoc s "$ref"))
    s))

(defn ^:private schema-part
  "The part of the config schema for the config key at `key-path`, e.g.
   `toolCall.approval`, as JSON, or for `definitions.<name>`, what a `$ref`
   points to; an error result when there's none."
  [schema-text key-path]
  (let [schema (json/parse-string schema-text)
        path (remove string/blank? (string/split key-path #"\."))]
    (loop [s schema
           walked []
           [k & more :as remaining] path]
      (let [s (resolve-ref schema s)]
        (if (empty? remaining)
          (tools.util/single-text-content (json/generate-string s {:pretty true}))
          (let [props (get s "properties")
                entries (resolve-ref schema (get s "additionalProperties"))]
            (cond
              (contains? props k) (recur (get props k) (conj walked k) more)
              (and (empty? walked) (= "definitions" k)) (recur {"properties" (get schema "definitions")} [k] more)
              ;; maps with free names, e.g. an MCP server's
              (and (map? entries) (seq entries)) (recur entries (conj walked k) more)
              :else (tools.util/single-text-content
                     (str "No config key " (string/join "." (conj walked k)) " in the schema."
                          (when (seq props)
                            (str " Keys " (if (seq walked) (str "under " (string/join "." walked)) "at the root")
                                 ": " (string/join ", " (sort (keys props))))))
                     :error))))))))

(defn ^:private guide-tool [_ctx {:strs [topic key]}]
  (if (string/blank? topic)
    (tools.util/single-text-content (index-text))
    (if-let [{:keys [text]} (guide/topic (string/trim topic))]
      (if (and (= "config-schema" (string/trim topic)) (not (string/blank? key)))
        (schema-part text key)
        (tools.util/single-text-content text))
      (tools.util/single-text-content (str "No topic " topic ". Topics: " (string/join ", " (map :topic (guide/index))))
                                      :error))))

;; --- create__modules

(defn ^:private errors-text [errors]
  (when (seq errors)
    (multi-str
     "  errors, latest last:"
     (for [{:keys [what message]} errors]
       (str "  - in " what ": " message)))))

(defn ^:private modules-tool [{:keys [config db]} {:strs [id]}]
  (if (string/blank? id)
    (tools.util/single-text-content (f.modules/modules-text config db))
    (let [module-id (f.modules/parse-id id)
          entries (filter #(= module-id (:id %)) (modules/statuses config))]
      (if (seq entries)
        (tools.util/single-text-content
         (multi-str (for [entry entries]
                      (multi-str (f.modules/module-text entry {:config config :db db})
                                 (errors-text (:errors entry))))))
        (tools.util/single-text-content
         (str "No module " id ". Call create__modules without an id to list them.")
         :error)))))

;; --- create__eval

(def ^:private eval-timeout-ms 60000)

(def ^:private max-chars 20000)

(defn ^:private cut [^String s]
  (if (> (count s) max-chars)
    (str (subs s 0 max-chars) "… (cut)")
    s))

(defn ^:private bounded-writer
  "A Writer that keeps the first `limit` chars written to it and drops the
   rest, so code that prints without end can't fill ECA's memory, even after
   its eval timed out. Its `toString` is what it kept."
  ^Writer [limit]
  (let [buf (StringBuilder.)
        cut?* (volatile! false)
        append! (fn [^String s]
                  (locking buf
                    (let [room (- (long limit) (.length buf))]
                      (if (<= (count s) room)
                        (.append buf s)
                        (do (when (pos? room)
                              (.append buf (subs s 0 room)))
                            (vreset! cut?* true))))))]
    (proxy [Writer] []
      (write
        ([x]
         (append! (cond
                    (string? x) x
                    (integer? x) (str (char (int x)))
                    :else (String. ^chars x))))
        ([x off len]
         (append! (if (string? x)
                    (subs x (int off) (+ (int off) (int len)))
                    (String. ^chars x (int off) (int len))))))
      (flush [])
      (close [])
      (toString []
        (locking buf
          (str buf (when @cut?* "… (cut)")))))))

(defn ^:private error-location [e]
  (let [{:keys [line column]} (ex-data e)]
    (when line
      (str " (line " line (when column (str ", column " column)) ")"))))

(defn eval-code
  "Evaluates `code`, Clojure, in the SCI context external modules run in,
   in the `user` namespace: `{:value :out :err}`, the value printed, or
   `{:error :out :err}` when it throws or takes longer than `timeout-ms`."
  [code timeout-ms]
  (let [sci-ctx (loader/sci-context)
        out (bounded-writer max-chars)
        err (bounded-writer max-chars)
        job (future
              (sci/binding [sci/out out
                            sci/err err]
                ;; SCI gives errors their line only on its "main" thread
                (let [value (sci/eval-string* (assoc sci-ctx :main-thread-id (.getId (Thread/currentThread))) code)]
                  (binding [*print-length* 100
                            *print-level* 10]
                    (pr-str value)))))
        output (fn [result]
                 (assoc result :out (str out) :err (str err)))]
    (try
      (let [value (deref job timeout-ms ::timeout)]
        (if (= ::timeout value)
          (do (future-cancel job)
              (output {:error (format "Timed out after %d seconds" (quot timeout-ms 1000))}))
          (output {:value (cut value)})))
      (catch ExecutionException e
        (let [cause (or (.getCause e) e)]
          (output {:error (str (or (ex-message cause) (str cause)) (error-location cause))})))
      (catch InterruptedException e
        ;; the chat stopped
        (future-cancel job)
        (throw e)))))

(defn ^:private eval-tool [_ctx {:strs [code]}]
  (if (string/blank? code)
    (tools.util/single-text-content "No code to evaluate." :error)
    (let [{:keys [value error out err]} (eval-code code eval-timeout-ms)]
      (tools.util/single-text-content
       (multi-str (if error (str "Error: " error) (str "=> " value))
                  (when-not (string/blank? out) (str "Output:\n" out))
                  (when-not (string/blank? err) (str "Error output:\n" err)))
       (boolean error)))))

;; --- The module

(def ^:private tools
  {"guide" {:description (multi-str
                          "Read ECA's guide: its documentation pages, its config schema, and the reference of the eca.api"
                          "namespaces modules call, taken from ECA's code. Read it before writing ECA config, skills, rules,"
                          "commands, hooks, agents, plugins or modules: never guess a key, a path or a function."
                          "Without `topic`, lists the topics.")
            :parameters {:type "object"
                         :properties {"topic" {:type "string"
                                               :description "The topic, e.g. \"modules\" or \"config-schema\"; omit it to list them."}
                                      "key" {:type "string"
                                             :description (str "For config-schema only: a config key path, e.g. "
                                                               "\"toolCall.approval\" or \"mcpServers\", to get only "
                                                               "its part of the schema; \"definitions.<name>\" for "
                                                               "what a \"#/definitions/<name>\" $ref points to.")}}
                         :required []}
            :handler #'guide-tool
            :enabled-fn #'create-chat?
            :summary-fn (fn [_ctx args]
                          (if-let [topic (not-empty (get args "topic"))]
                            (str "Reading the guide: " topic)
                            "Listing the guide's topics"))}
   "modules" {:description (multi-str
                            "List ECA's modules: built-in and external, whether each is active, where it comes from,"
                            "what it adds (tools, commands, prompt sections, event handlers, config keys), and why it"
                            "isn't active or the last error, with the file and line of a load error. With `id`, one"
                            "module and its recent errors. Use it after writing or changing a module.")
              :parameters {:type "object"
                           :properties {"id" {:type "string"
                                              :description "A module id, e.g. \"acme/jira\"; omit it to list them all."}}
                           :required []}
              :handler #'modules-tool
              :enabled-fn #'create-chat?
              :summary-fn (fn [_ctx args]
                            (if-let [id (not-empty (get args "id"))]
                              (str "Checking module " id)
                              "Listing modules"))}
   "eval" {:description (multi-str
                         "Evaluate Clojure code in ECA's module runtime, the SCI context external modules run in, and"
                         "get the last value and what it printed. Use it to test a module with eca.api.test (mock-ctx,"
                         "run-tool, run-command, run-event), and to install, reload, remove or inspect modules with"
                         "eca.api.dev (install!, reload!, uninstall!, modules); changes last for this session. The code"
                         "runs inside ECA, with its permissions, so ECA asks the user before each call. Java interop"
                         "works only on SCI's default classes, e.g. String, Integer and Exception."
                         (format "Times out after %d seconds." (quot eval-timeout-ms 1000)))
           :parameters {:type "object"
                        :properties {"code" {:type "string"
                                             :description (str "Clojure forms, evaluated in order in the user "
                                                               "namespace, e.g. \"(require '[eca.api.dev :as dev]) "
                                                               "(dev/reload! :acme/jira)\".")}}
                        :required ["code"]}
           :handler #'eval-tool
           :enabled-fn #'create-chat?
           ;; asks even when the user's config allows the tool, as it runs code inside ECA; approving
           ;; it for the session, or a trusted chat, still skips asking, as for any tool
           :require-approval-fn (constantly true)
           :summary-fn (constantly "Evaluating Clojure in ECA")}})

(def module
  {:id :eca/agent-create
   :description "The create agent: helps the user configure and extend ECA, from config to modules."
   ;; it grows out of :eca/create; requiring it also merges the agent after plan, so clients list it last
   :requires #{:eca/agents :eca/create}
   :prefix "create"
   :config-defaults {:agent {agent-name {:mode "primary"
                                         :prompts {:chat "${classpath:prompts/create_agent.md}"}
                                         :disabledTools ["preview_file_change"]}}
                     :toolCall {:approval {:allow {"create__guide" {}
                                                   "create__modules" {}}}}}
   :tools tools})
