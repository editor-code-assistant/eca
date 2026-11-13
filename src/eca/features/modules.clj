(ns eca.features.modules
  "The `/modules` command: list the modules, and enable, disable or reload one
   for the session."
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.modules :as modules]
   [eca.modules.config-spec :as config-spec]
   [eca.shared :refer [multi-str]]))

(set! *warn-on-reflection* true)

(def ^:private status-icon
  {:active "✅"
   :disabled "⏸️"
   :inactive "⚠️"
   :failed "❌"
   :refused "⛔"})

(defn parse-id
  "A module id as users type it: `eca/mcp` or `:eca/mcp`."
  [s]
  (when-let [s (some-> s string/trim not-empty)]
    (let [s (string/replace-first s #"^:" "")]
      (when (string/includes? s "/")
        (keyword s)))))

(defn ^:private code-list [xs]
  (string/join ", " (map #(str "`" % "`") xs)))

(defn ^:private tool-names
  "The names of a module's tools; for `:tools` functions, the ones they give
   outside of a chat."
  [{:keys [module] :as entry} ctx]
  (let [tools (:tools module)]
    (sort (keys (if (modules/fn-like? tools)
                  (try (tools (modules/module-ctx ctx entry)) (catch Exception _ nil))
                  tools)))))

(defn ^:private contributions [{:keys [module] :as entry} ctx]
  (let [prefix (modules/prefix entry)
        {:keys [commands system-prompts providers apis event-handlers defines config-spec rpc]} module
        tools (tool-names entry ctx)]
    (remove nil?
            [(when (seq tools)
               (str "tools " (code-list (map #(str prefix "__" %) tools))))
             (when (seq commands)
               (str "commands " (code-list (map #(str "/" %) (sort (keys commands))))))
             (when (seq system-prompts)
               (str "prompt sections " (code-list (map :id system-prompts))))
             (when (seq providers)
               (str "providers " (code-list (sort (keys providers)))))
             (when (seq apis)
               (str "APIs " (code-list (sort (keys apis)))))
             (when (seq rpc)
               (str "protocol methods " (code-list (sort (keys rpc)))))
             (when (seq event-handlers)
               (str "handles " (code-list (sort (keys event-handlers)))))
             (when-let [events (seq (:events defines))]
               (format "defines %d events" (count events)))
             (when-let [paths (seq (keys (config-spec/leaves config-spec)))]
               (str "config " (code-list (sort (map config-spec/path-str paths)))))])))

(defn ^:private module-text [{:keys [id module status reason source built-in? priority running?
                                     warnings failures errors]
                              :as entry}
                             ctx]
  (let [{:keys [description version]} module]
    (multi-str
     (str (status-icon status "•") " `" id "`"
          (when version (str " " version))
          (when description (str " — " description)))
     (str "  " (string/join " · " (remove nil? [(if built-in? "built-in" (str "from `" source "`"))
                                                (when priority (str "priority " priority))
                                                (when running? "running")
                                                (when (pos? (or failures 0)) (str failures " failures in a row"))])))
     (when (and reason (not= :active status))
       (str "  " (name status) ": " reason))
     (when (= :active status)
       (for [line (contributions entry ctx)]
         (str "  " line)))
     (for [warning warnings]
       (str "  warning: " warning))
     (when-let [{:keys [what message]} (last errors)]
       (str "  last error, in " what ": " message)))))

(defn modules-text
  "The `/modules` listing."
  [config db]
  (let [{:keys [active] :as by-status} (group-by #(if (= :active (:status %)) :active :other)
                                                 (modules/statuses config))]
    (multi-str
     (format "## Modules (%d active)" (count active))
     ""
     (for [entry (concat active (:other by-status))]
       (module-text entry {:config config :db db}))
     ""
     "`/modules enable <id>`, `/modules disable <id>` and `/modules reload <id>` take effect now, for this session."
     "To keep a choice, set `modules.<id>.enabled` in your config.")))

(defn ^:private known-ids [config]
  (set (map :id (modules/statuses config))))

(defn ^:private dependents-text [id before after]
  (let [stopped (sort (remove (conj after id) before))]
    (when (seq stopped)
      (str "Also inactive now: " (code-list stopped) "."))))

(defn handle!
  "Runs `/modules [enable|disable|reload <id>]`, returning the text to show."
  [args {:keys [db* messenger metrics config]}]
  (let [[action id-str] args
        components {:db* db* :messenger messenger :metrics metrics}
        fresh-config #(config/all @db*)
        id (parse-id id-str)]
    (cond
      (nil? action)
      (modules-text config @db*)

      (not (#{"enable" "disable" "reload"} action))
      (str "Unknown action `" action "`. Use `/modules`, `/modules enable <id>`, `/modules disable <id>`"
           " or `/modules reload <id>`.")

      (nil? id)
      (str "Which module? E.g. `/modules " action " eca/mcp`.")

      (not (contains? (known-ids config) id))
      (str "No module `" id "`. `/modules` lists them.")

      (and (= "disable" action) (= :eca/core id))
      "`:eca/core` can't be disabled."

      :else
      (let [before (:active-ids (modules/resolution config))]
        (case action
          "enable" (modules/set-enabled! id true)
          "disable" (modules/set-enabled! id false)
          "reload" (modules/restart! components config id))
        (let [config (fresh-config)
              _ (modules/reconcile-if-started! components config)
              after (:active-ids (modules/resolution config))
              status (some #(when (= id (:id %)) %) (modules/statuses config))]
          (multi-str
           (case action
             "enable" (if (= :active (:status status))
                        (str "Enabled `" id "` for this session.")
                        (str "`" id "` is enabled but not active: " (:reason status)))
             "disable" (str "Disabled `" id "` for this session.")
             "reload" (if (= :active (:status status))
                        (str "Reloaded `" id "`.")
                        (str "`" id "` didn't come back: " (:reason status))))
           (dependents-text id before after)))))))

(defn active-ids-text
  "One line naming the active modules, for `/doctor`."
  [config]
  (code-list (sort (map str (:active-ids (modules/resolution config))))))

(defn ^:private value-kind [value]
  (cond
    (string? value) "a string"
    (number? value) "a number"
    (boolean? value) "a boolean"
    (map? value) "an object"
    (sequential? value) "an array"
    :else (str "a " (type value))))

(defn config-problems
  "Configured values that don't fit the type a module declares for them.
   Values aren't shown: they may be secrets."
  [config]
  (for [{:keys [path value type]} (config-spec/type-problems (modules/declared-keys config) config)]
    (format "`%s` should be %s, got %s" (config-spec/path-str path)
            (case type
              :enum "one of the allowed values"
              :map "an object"
              :array "an array"
              (str "a " (name type)))
            (value-kind value))))
