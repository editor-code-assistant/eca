(ns eca.modules.tools.tool-search
  "The `:eca/tool-search` module: when MCP servers bring too many tools, it
   defers them, lists them in the system prompt and lets the model load the
   ones it needs with the `search_tools` tool."
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools :as f.tools]
   [eca.features.tools.util :as tools.util]
   [eca.shared :as shared :refer [multi-str]]))

(set! *warn-on-reflection* true)

(def tool-name "search_tools")

(def ^:private default-max-results 10)
(def ^:private max-max-results 50)

(defn ^:private tokenize [s]
  (->> (string/split (string/lower-case (str s)) #"[^a-z0-9]+")
       (remove string/blank?)
       set))

(defn ^:private score
  [query-tokens {:keys [full-name description]}]
  (let [name-str (string/lower-case (str full-name))
        desc-str (string/lower-case (str description))
        name-tokens (tokenize name-str)
        desc-tokens (tokenize desc-str)]
    (transduce
     (map (fn [token]
            (+ (if (contains? name-tokens token) 10 0)
               (if (string/includes? name-str token) 5 0)
               (if (contains? desc-tokens token) 2 0)
               (if (string/includes? desc-str token) 1 0))))
     +
     0
     query-tokens)))

(defn ^:private ->positive-int [value]
  (cond
    (integer? value) value
    (number? value) (long value)
    (string? value) (try (Long/parseLong (string/trim value))
                         (catch NumberFormatException _ nil))
    :else nil))

(defn ^:private rank
  [deferred-tools query max-results]
  (let [query-tokens (tokenize query)]
    (if (empty? query-tokens)
      (take max-results (sort-by :full-name deferred-tools))
      (->> deferred-tools
           (map #(assoc % :score (score query-tokens %)))
           (filter #(pos? (:score %)))
           (sort-by (juxt (comp - :score) :full-name))
           (take max-results)))))

(defn ^:private render-tool
  [{:keys [full-name description parameters]}]
  (multi-str
   (format "<tool name=\"%s\">" full-name)
   (string/trim (str description))
   ""
   "Input schema:"
   "```json"
   (json/generate-string parameters {:pretty true})
   "```"
   "</tool>"))

(defn ^:private search-tools
  [arguments {:keys [db* chat-id all-tools]}]
  (let [query (str (get arguments "query" ""))
        max-results (-> (get arguments "max_results")
                        ->positive-int
                        (or default-max-results)
                        (max 1)
                        (min max-max-results))
        deferred-tools (filter :deferrable all-tools)
        matches (rank deferred-tools query max-results)]
    (cond
      (empty? deferred-tools)
      (tools.util/single-text-content "There are no deferred tools to search." :error)

      (empty? matches)
      (tools.util/single-text-content
       (format (multi-str "No deferred tool matched '%s'."
                          ""
                          "Available deferred tools: %s")
               query
               (string/join ", " (sort (map :full-name deferred-tools)))))

      :else
      (do
        (tools.util/activate-deferred-tools! db* chat-id (map :full-name matches))
        (tools.util/single-text-content
         (multi-str
          (format "Loaded %d tool(s). They are now available and you can call them directly from the next message onward."
                  (count matches))
          ""
          (string/join "\n\n" (map render-tool matches))))))))

(def definitions
  {tool-name
   {:description (tools.util/read-tool-description tool-name)
    :parameters {:type "object"
                 :properties {"query" {:type "string"
                                       :description "Keywords describing the capability you need, matched against deferred tool names and descriptions. Omit to list the deferred tools."}
                              "max_results" {:type "integer"
                                             :description (format "Maximum number of tools to load (default %d, max %d)."
                                                                  default-max-results max-max-results)}}
                 :required []}
    :handler #'search-tools
    :summary-fn (fn [{:keys [args]}]
                  (if-let [query (not-empty (str (get args "query" "")))]
                    (format "Searching tools for '%s'" query)
                    "Listing deferred tools"))}})

(def ^:private deferred-tool-description-max-length 250)

(defn ^:private deferred-tool-summary
  "First paragraph of the tool description, truncated: the catalog is routing
   metadata, the full description arrives when the tool is loaded."
  [description]
  (let [summary (-> (str description)
                    (string/split #"\R\s*\R" 2)
                    first
                    (string/replace #"\s+" " ")
                    string/trim)]
    (if (> (count summary) deferred-tool-description-max-length)
      (str (string/trimr (subs summary 0 deferred-tool-description-max-length)) "…")
      summary)))

(defn ^:private deferred-tools-section
  [all-tools]
  (when-let [deferred-tools (seq (filter :deferrable all-tools))]
    ["## Deferred Tools"
     ""
     (str (format "<deferred-tools%s>"
                  (f.prompt/attr-str {:description (str "Tools that exist but are not loaded: their full descriptions and input schemas are unavailable and they cannot be called yet. "
                                               "Load the ones you need with eca__search_tools, which makes them callable.")}))
          "\n")
     (reduce
      (fn [tools-str {:keys [full-name description]}]
        (str tools-str (format "<deferred-tool%s/>\n"
                               (f.prompt/attr-str {:name full-name
                                          :description (deferred-tool-summary description)}))))
      ""
      (sort-by :full-name deferred-tools))
     "</deferred-tools>"
     ""]))

(defn deferred-tools-prompt-section
  "`:eca/deferred-tools`: the tools hidden behind search_tools."
  [{:keys [all-tools]}]
  (deferred-tools-section all-tools))

(defn ^:private get-defer-all-percent
  "Percentage of the model context window the MCP tool definitions may take
   before all of them are deferred without any pattern. The agent value overrides
   the global one; nil (the default) leaves them unlimited."
  [config agent-name]
  (let [agent-config (get-in config [:agent agent-name :mcpToolSearch])]
    (if (contains? agent-config :deferAllWhenTotalTokensExceedPercentOfContext)
      (:deferAllWhenTotalTokensExceedPercentOfContext agent-config)
      (get-in config [:mcpToolSearch :deferAllWhenTotalTokensExceedPercentOfContext]))))

(defn ^:private tools-tokens
  "Rough token cost of the tool definitions sent on every request, measured over
   the same wire fields `shared/context-breakdown` reports as Tool definitions."
  [tools]
  (shared/estimate-tokens
   (pr-str (mapv #(select-keys % [:name :description :parameters]) tools))))

(defn ^:private defer-all-by-total-tokens?
  "True when the MCP tool definitions as a whole outgrow
   `deferAllWhenTotalTokensExceedPercentOfContext` of the model context window.
   False without a percent or a known context window, so an unknown model never
   silently hides its tools."
  [mcp-tools config agent-name db full-model]
  (let [percent (get-defer-all-percent config agent-name)
        context-limit (get-in db [:models full-model :limit :context])]
    (boolean (and (number? percent)
                  (not (neg? percent))
                  (number? context-limit)
                  (pos? context-limit)
                  (seq mcp-tools)
                  (> (tools-tokens mcp-tools)
                     (* context-limit (/ (double percent) 100.0)))))))

(defn ^:private mark-deferred-tools
  "Marks MCP tools kept out of the LLM context with `:deferrable`, and with
   `:deferred` while the chat has not loaded them yet via `eca__search_tools`.
   A tool is deferred when the MCP tool definitions as a whole outgrow
   `mcpToolSearch.deferAllWhenTotalTokensExceedPercentOfContext` of the context
   window, or when it matches `includePattern`, and in both cases only if it does
   not match `excludePattern`.
   Deferred tools stay resolvable and callable; they are simply kept out of the
   tool schemas sent to the LLM.
   Native ECA tools are never deferred: they are the agent's baseline capabilities,
   and a catch-all pattern taking them away would break the agent. They are also
   left out of the token count, so the limit tracks what MCP actually adds."
  [tools {:keys [config agent-name activated db full-model]}]
  (let [include-patterns (f.tools/get-tool-patterns config agent-name [:mcpToolSearch :includePattern])
        exclude-patterns (f.tools/get-tool-patterns config agent-name [:mcpToolSearch :excludePattern])
        mcp-tools (filterv #(not= :native (:origin %)) tools)
        defer-all? (defer-all-by-total-tokens? mcp-tools config agent-name db full-model)]
    (if-not (or defer-all? (seq include-patterns))
      tools
      (mapv (fn [tool]
              (if (and (not= :native (:origin tool))
                       (or defer-all? (f.tools/tool-matches-any? tool include-patterns))
                       (not (f.tools/tool-matches-any? tool exclude-patterns)))
                (assoc tool
                       :deferrable true
                       :deferred (not (contains? activated (:full-name tool))))
                tool))
            tools))))

(defn ^:private transform-tools
  "`:transform-tools`: defers the MCP tools the config asks to. search_tools
   only earns its context slot when something is actually deferred."
  [{:keys [config agent db chat-id full-model]} tools]
  (let [tools (mark-deferred-tools tools
                                   {:config config
                                    :agent-name agent
                                    :activated (tools.util/activated-deferred-tools db chat-id)
                                    :db db
                                    :full-model full-model})]
    (if (some :deferrable tools)
      tools
      (remove #(= "eca__search_tools" (:full-name %)) tools))))

(def module
  {:id :eca/tool-search
   :description "Hide MCP tools behind search_tools when there are too many."
   :config-spec {:mcpToolSearch {:type :map
                                 :default {:deferAllWhenTotalTokensExceedPercentOfContext nil
                                           :includePattern []
                                           :excludePattern []}}}
   :config-defaults {:toolCall {:approval {:allow {"eca__search_tools" {}}}}}
   :system-prompts [{:id :eca/deferred-tools :content #'deferred-tools-prompt-section}]
   :tools (fn [_ctx] (tools.util/->module-tools definitions))
   :transform-tools #'transform-tools})
