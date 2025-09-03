(ns eca.features.tools.custom
  (:require
   [babashka.process :as process]
   [clojure.string :as string]))

(set! *warn-on-reflection* true)

(defn ^:private build-tool-fn
  "Creates a function that safely executes the command from a custom tool config.
  It substitutes {{placeholders}} in the command vector with LLM-provided arguments."
  [{:keys [command]}]
  ;; The handler function takes arguments and a context map. We only need the arguments.
  (fn [llm-args _context]
    (let [resolved-command (mapv
                            (fn [part]
                              (if (and (string? part) (string/starts-with? part "{{") (string/ends-with? part "}}"))
                                (let [key-name (keyword (subs part 2 (- (count part) 2)))]
                                  (str (get llm-args key-name "")))
                                part))
                            command)
          {:keys [out exit]} (process/sh resolved-command {:error-to-out true})]
      (if (zero? exit)
        out
        (str "Error: Command failed with exit code " exit "\nOutput:\n" out)))))

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
