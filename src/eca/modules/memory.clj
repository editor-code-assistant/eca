(ns eca.modules.memory
  "The `:eca/memory` module: durable Markdown notes persisted across chats,
   off by default (`modules.eca/memory.enabled`)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [eca.api :as eca]
   [eca.db :as db]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.modules.memory.delivery :as m.memory.delivery]
   [eca.modules.memory.render :as m.memory.render]
   [eca.modules.memory.store :as m.memory.store]
   [eca.modules.memory.usage :as m.memory.usage]))

(set! *warn-on-reflection* true)

(defn ^:private subagent? [ctx]
  (:subagent? (eca/chat ctx)))

;; --- Consolidation flag

(defn ^:private consolidate-command? [prompt]
  (and (string? prompt)
       (re-find #"^/(?:eca:)?memory-consolidate(?:\s|$)" prompt)))

(defn ^:private consolidating?
  "True while this chat or its parent runs a `/memory-consolidate` turn."
  [db chat-id module-id]
  (let [flag #(get-in db [:chats % :modules module-id :consolidating?])]
    (or (flag chat-id)
        (some-> (db/parent-chat-id db chat-id) flag))))

(defn ^:private set-consolidating!
  "Updated only on new user requests so retries and continuations keep the flag."
  [ctx on?]
  (when (not= on? (boolean (:consolidating? (eca/chat-state ctx))))
    (eca/swap-chat-state! ctx #(if on?
                                (assoc % :consolidating? true)
                                (dissoc % :consolidating?)))))

;; --- Memory blocks

(defn ^:private memory-context [ctx]
  (when-let [block (m.memory.delivery/turn-block {:chat-id (:chat-id ctx)
                                                 :db @(:db* ctx)
                                                 :config (eca/config ctx)
                                                 :window (eca/messages ctx {:since-checkpoint? true})
                                                 :module-id (eca/module-id ctx)})]
    {:additional-context block}))

(defn ^:private before-request [ctx {:keys [prompt]}]
  (when-not (subagent? ctx)
    (if (consolidate-command? prompt)
      (do (set-consolidating! ctx true) nil)
      (do (set-consolidating! ctx false)
          (memory-context ctx)))))

(defn ^:private after-compact [ctx _event]
  (when-not (subagent? ctx)
    (memory-context ctx)))

;; --- System prompt

(defn ^:private prepare-trash-dirs! [db dirs]
  (let [trash (m.memory.store/trash-dirs db)
        dirs (mapv #(assoc % :trash (get trash (:dir %))) dirs)]
    (m.memory.store/create-dirs! (keep :trash dirs))
    dirs))

(defn ^:private guidance-section [{:keys [db config chat-id all-tools] :as ctx}]
  (when-not (subagent? ctx)
    (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
      (let [write? (or (tools.util/tool-available? all-tools "eca__write_file")
                       (tools.util/tool-available? all-tools "eca__edit_file"))
            dirs (m.memory.store/memory-dirs db)]
        [(m.memory.render/guidance-text
          {:write-mode (m.memory.store/write-mode config)
           :write? write?
           :subagents? (tools.util/tool-available? all-tools "eca__spawn_agent")
           :dirs (if write? (prepare-trash-dirs! db dirs) dirs)})
         ""]))))

;; --- File tools

(def ^:private file-tools
  #{"eca__read_file" "eca__write_file" "eca__edit_file" "eca__move_file"})

(defn ^:private path-note-error [{:keys [dir path link?]}]
  (cond
    link?
    (str "Memory notes must be regular files, not links."
         " To keep memory somewhere else, link the whole memory directory instead.")

    (m.memory.store/hidden-note-path? dir path)
    "Memory notes must not be hidden or inside hidden subdirectories. Move the file to a visible path."

    (not (m.memory.store/markdown-file? path))
    (str "Memory directories hold only Markdown notes, and files with other names are not read as"
         " memories: move it to a name that ends in `.md`.")

    (not (fs/regular-file? path))
    "Memory notes must be regular files."))

(defn ^:private written-note-error
  "Validates `write_file`'s `content` directly without re-reading disk."
  [located content]
  (or (path-note-error located)
      (when (string? content)
        (m.memory.store/note-content-error content))))

(defn ^:private disk-note-error [{:keys [path] :as located}]
  (or (path-note-error located)
      (when-let [content (try (slurp path) (catch Exception _ nil))]
        (m.memory.store/note-content-error content))))

(defn ^:private invalid-note-result [result done error]
  (assoc result
         :error true
         :contents [{:type :text
                     :text (str done ", but it is not a valid memory note:\n" error)}]))

(defn ^:private after-write [usage-ctx db result path content]
  (when-let [located (m.memory.store/locate-memory-path db path)]
    (m.memory.usage/record-write! usage-ctx path)
    (when-let [error (written-note-error located content)]
      {:result (invalid-note-result result (str "Wrote " path) error)})))

(defn ^:private after-edit [usage-ctx db result path]
  (when-let [located (m.memory.store/locate-memory-path db path)]
    (m.memory.usage/record-write! usage-ctx path)
    (when-let [error (disk-note-error located)]
      {:result (invalid-note-result result (str "Applied the edit to " path) error)})))

(defn ^:private after-move [usage-ctx db result source destination]
  (let [source-located (m.memory.store/locate-memory-path db source)
        dest-located (m.memory.store/locate-memory-path db destination)
        source-key (or (:path source-located) (m.memory.store/abs-path source))]
    (when (or dest-located source-located)
      (m.memory.usage/record-move! usage-ctx source-key destination)
      (when (and dest-located (or (:link? dest-located) (not (fs/directory? destination))))
        (when-let [dest-error (disk-note-error dest-located)]
          {:result (invalid-note-result result (str "Moved " source " to " destination) dest-error)})))))

(defn ^:private after-call [ctx {:keys [tool arguments result error? tool-call-id]}]
  (when-not error?
    (let [db @(:db* ctx)
          chat-id (:chat-id ctx)
          consolidation? (consolidating? db chat-id (eca/module-id ctx))
          usage-ctx {:chat-id chat-id :tool-call-id tool-call-id :db db
                     :consolidation? consolidation?}]
      (case tool
        "eca__read_file" (do (when-not consolidation?
                               (m.memory.usage/record-read! usage-ctx (get arguments "path")))
                             nil)
        "eca__write_file" (after-write usage-ctx db result (get arguments "path") (get arguments "content"))
        "eca__edit_file" (after-edit usage-ctx db result (get arguments "path"))
        "eca__move_file" (after-move usage-ctx db result (get arguments "source") (get arguments "destination"))
        nil))))

;; --- Commands

(defn ^:private memories-command [{:keys [db* config chat-id]} _args]
  (let [db @db*]
    (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
      (let [{:keys [entries skipped]} (m.memory.usage/ranked-scan db)
            opts (assoc (m.memory.delivery/index-limits config) :entries entries)]
        (m.memory.render/memories-text
         (assoc opts :dirs (m.memory.store/memory-dirs db)
                     :skipped skipped
                     :index (m.memory.render/index-context opts)))))))

(defn ^:private consolidate-scope
  "First token is a scope only when it is exactly `project` or `global` (any case)."
  [args]
  (case (some-> (first args) string/lower-case)
    "project" {:tier :personal :label "project" :request (rest args)}
    "global" {:tier :global :label "global" :request (rest args)}
    {:tier nil :label "project + global" :request args}))

(defn ^:private consolidate-command [{:keys [db* config agent chat-id]} {args :argv}]
  (let [db @db*]
    (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
      (let [{:keys [tier request] scope-label :label} (consolidate-scope args)
            args-text (string/join " " request)
            in-scope? #(or (nil? tier) (= tier (:tier %)))
            dirs (prepare-trash-dirs! db (filter in-scope? (m.memory.store/memory-dirs db)))
            {:keys [entries skipped]} (m.memory.usage/ranked-scan db)
            snapshot (m.memory.render/consolidate-snapshot-text
                      {:dirs dirs
                       :entries entries
                       :skipped (filter in-scope? skipped)})]
        {:type :send-prompt
         :prompt (str (f.prompt/get-config-prompt :memoryConsolidate agent config)
                      "\n\n## Memory directories and usage snapshot\n"
                      "Scope: " scope-label "\n\n"
                      snapshot
                      (when-not (string/blank? args-text)
                        (str "\n\n## Additional instructions from the user\n" args-text)))}))))

;; --- Approval

(defn ^:private config-defaults
  "Marks memory and trash dirs as inside-workspace for file-tool approval."
  [{:keys [db]}]
  (let [trash (m.memory.store/trash-dirs db)]
    {:toolCall {:approval {:insideWorkspace (into []
                                                  (mapcat (fn [{:keys [dir]}] [dir (get trash dir)]))
                                                  (m.memory.store/memory-dirs db))}}}))

(def module
  {:id :eca/memory
   :description "Durable memory: notes the model saves and reads across chats, in Markdown files."
   :default-enabled? false
   :config-spec {:memory {:writeMode {:type :enum
                                      :values ["agent" "explicit"]
                                      :default "agent"
                                      :doc (str "agent: the model saves useful findings on its own; "
                                                "explicit: only when you ask.")}
                          :index {:maxEntries {:type :number :default 100
                                               :doc "Most notes the memory index lists."}
                                  :maxTokens {:type :number :default 2000
                                              :doc "Token budget of the memory index, estimated as 4 characters per token."}}}
                 :prompts {:memoryConsolidate {:type :string
                                               :default "${classpath:prompts/memory_consolidate.md}"}}}
   :config-defaults #'config-defaults
   :system-prompts [{:id :eca/memory
                     :before :eca/additional-info
                     :content #'guidance-section}]
   :event-handlers {:chat/before-request #'before-request
                    :chat/after-compact #'after-compact
                    :tool/after-call {:match {:tool file-tools}
                                      :handler #'after-call}}
   :commands {"memories" {:description "List saved memories and show which ones the memory index includes."
                          :arguments []
                          :handler #'memories-command}
              "memory-consolidate" {:description "Review, merge and clean up durable memory files"
                                    :arguments [{:name "scope"
                                                 :description "project or global (default: both)"
                                                 :required false}
                                                {:name "instructions"
                                                 :description "Your request, e.g. only entries changed in the last 14 days"
                                                 :required false}]
                                    :handler #'consolidate-command}}})
