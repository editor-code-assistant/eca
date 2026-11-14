(ns eca.modules.tools.custom-tools
  "The `:eca/custom-tools` module. Tools from the customTools config: shell
   command templates."
  (:require
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.shared :as shared]
   [eca.shell :as shell]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CUSTOM-TOOLS]")

(defn ^:private build-tool-fn
  "Creates a function that safely executes the command from a custom tool config.
  It substitutes {{placeholders}} in the command vector with LLM-provided arguments."
  [tool-name {:keys [command]}]
  (fn [args {:keys [db config chat-id] :as ctx}]
    (let [resolved-command (reduce
                            (fn [s [arg-name arg-value]]
                              (string/replace s (str "{{" arg-name "}}") (str arg-value)))
                            command
                            args)
          work-dir (or (some-> (:workspace-folders db)
                               first
                               :uri
                               shared/uri->filename)
                       (cache/user-home))
          shell-config (get-in config [:toolCall :shellCommand])
          shell-path (get shell-config :path)
          shell-args (get shell-config :args)
          _ (logger/debug logger-tag "Running custom tool:" resolved-command)
          result (try
                   @(shell/start-shell-process!
                     (shell/before-exec ctx
                                        (cond-> {:cwd work-dir
                                                 :script resolved-command
                                                 :chat-id chat-id}
                                          shell-path (assoc :shell-path shell-path)
                                          shell-args (assoc :shell-args shell-args))
                                        {:source :tool :tool (str "eca__" tool-name)}))
                   (catch Exception e
                     {:exit 1 :err (.getMessage e)}))
          exit (:exit result)
          err (some-> (:err result) string/trim)
          out (some-> (:out result) string/trim)]
      (logger/debug logger-tag "custom tool executed:" result)
      {:error (not= 0 exit)
       :contents (remove nil?
                         (concat [(when (not= 0 exit)
                                    {:type :text
                                     :text (str "Exit code " exit)})]
                                 (when-not (string/blank? err)
                                   [{:type :text
                                     :text (str "Stderr:\n" err)}])
                                 (when-not (string/blank? out)
                                   [{:type :text
                                     :text (str "Stdout:\n" out)}])))})))

(defn ^:private custom-tool->tool-def
  "Transforms a single custom tool from the config map into a full tool definition."
  [[tool-name tool-config]]
  (let [schema (:schema tool-config)]
    {tool-name
     {:name tool-name
      :description (:description tool-config)
      :parameters {:type "object"
                   :properties (:properties schema)
                   :required (:required schema)}
      :handler (build-tool-fn tool-name tool-config)}}))

(defn definitions
  "Loads all custom tools from the config."
  [config]
  (->> (get config :customTools {})
       (map custom-tool->tool-def)
       (apply merge)))

(def module
  {:id :eca/custom-tools
   :description "Tools from the customTools config: shell command templates."
   :config-spec {:customTools {:type :map}}
   ;; read from the config on every listing, so config changes apply at once; a
   ;; custom tool named like a built-in one replaces it, as before modules
   :tools (fn [{:keys [config]}]
            (into {}
                  (map (fn [[tool-name tool]] [tool-name (assoc tool :replaces (str "eca__" tool-name))]))
                  (tools.util/->module-tools (definitions config))))})
