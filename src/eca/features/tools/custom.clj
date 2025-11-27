(ns eca.features.tools.custom
  (:require
   [babashka.process :as p]
   [clojure.string :as string]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CUSTOM-TOOLS]")

(defn ^:private build-tool-fn
  "Creates a function that safely executes the command from a custom tool config.
  It substitutes {{placeholders}} in the command vector with LLM-provided arguments."
  [{:keys [command]}]
  (fn [args {:keys [db]}]
    (let [resolved-command (reduce
                            (fn [s [arg-name arg-value]]
                              (string/replace s (str "{{" arg-name "}}") arg-value))
                            command
                            args)
          work-dir (some-> (:workspace-folders db)
                           first
                           :uri
                           shared/uri->filename)
          _ (logger/debug logger-tag "Running custom tool:" resolved-command)
          result (try
                   (p/shell {:dir work-dir
                             :out :string
                             :err :string
                             :continue true} "bash -c" resolved-command)
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
      :handler (build-tool-fn tool-config)}}))

(defn definitions
  "Loads all custom tools from the config."
  [config]
  (->> (get config :customTools {})
       (map custom-tool->tool-def)
       (apply merge)))
