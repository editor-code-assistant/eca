(ns eca.features.modules
  "The `/modules` command: list the modules, and enable, disable or reload one
   for the session."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.tools :as f.tools]
   [eca.modules :as modules]
   [eca.modules.config-spec :as config-spec]
   [eca.modules.loader :as loader]
   [eca.shared :as shared :refer [multi-str]]))

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

(defn ^:private registered
  "The names of what module `entry` registered while it runs, of `kind`."
  [config kind entry]
  (for [[registering reg-name _] (modules/registrations config kind)
        :when (= (:id entry) (:id registering))]
    reg-name))

(defn ^:private tool-names
  "The full names of a module's tools: `<prefix>__<name>`, or the name a tool
   `:replaces`. For `:tools` functions, the ones they give outside of a chat."
  [{:keys [module] :as entry} {:keys [config] :as ctx}]
  (let [prefix (modules/prefix entry)
        tools (:tools module)
        tools (if (modules/fn-like? tools)
                (try (tools (modules/module-ctx ctx entry)) (catch Exception _ nil))
                tools)]
    (sort (concat (for [[tool-name tool] tools]
                    (or (:replaces tool) (str prefix "__" tool-name)))
                  (for [tool-name (registered config :tools entry)]
                    (str prefix "__" tool-name))))))

(defn ^:private contributions [{:keys [module] :as entry} {:keys [config] :as ctx}]
  (let [{:keys [system-prompts providers apis event-handlers defines config-spec rpc]} module
        tools (tool-names entry ctx)
        commands (sort (distinct (concat (keys (:commands module)) (registered config :commands entry))))]
    (remove nil?
            [(when (seq tools)
               (str "tools " (code-list tools)))
             (when (seq commands)
               (str "commands " (code-list (map #(str "/" %) commands))))
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

(defn ^:private llm-usage-text
  "The module's LLM calls this session (`eca.api/complete!`), or nil."
  [{:keys [db]} id]
  (when-let [{:keys [calls cost]} (get-in db [:module-usage id])]
    (str calls (if (= 1 calls) " LLM call" " LLM calls")
         (when cost (str " ($" (shared/format-cost cost) ")")))))

(defn ^:private module-text [{:keys [id module status reason source built-in? priority running?
                                     warnings failures errors]
                              :as entry}
                             ctx]
  (let [{:keys [description version]} module]
    (multi-str
     ;; a module that failed to load may have no id yet
     (str (status-icon status "•") " `" (or id (str (fs/file-name source))) "`"
          (when version (str " " version))
          (when description (str " — " description)))
     (str "  " (string/join " · " (remove nil? [(if built-in? "built-in" (str "from `" source "`"))
                                                (when priority (str "priority " priority))
                                                (when running? "running")
                                                (llm-usage-text ctx id)
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
          ;; an external module is evaluated again from its source first
          "reload" (do (loader/reload! id config @db*)
                       (modules/restart! components (fresh-config) id)))
        (let [config (fresh-config)
              _ (modules/reconcile-if-started! components config)
              ;; the module may add, replace or remove tools
              _ (when messenger
                  (f.tools/refresh-module-tool-servers! db* messenger config))
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

(defn unknown-config-keys
  "Root keys of `config` that neither ECA nor any module knows: typos, or keys
   of a module that isn't loaded. Sorted."
  [config]
  (let [known (into config/core-root-keys (modules/known-root-keys))]
    (vec (sort-by str (remove known (filter keyword? (keys config)))))))

(defn unknown-config-keys-text
  "The warning about `unknown` root config keys."
  [unknown]
  (str "Unknown config keys: " (code-list (map config-spec/path-str (map vector unknown)))
       ". Check them for typos; a module's keys are known once the module is loaded."))

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
