(ns eca.features.chat.export
  "Structured export/import of a single chat to/from an EDN file.

   Unlike eca.features.chat.debug (which obfuscates for privacy/size), this
   preserves the chat verbatim so it can be re-imported as a fully resumable
   chat: messages plus model, variant, agent/subagent, usage, trust and the
   original chat-id (so provider/cache reuse keeps working). Issue #28."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as string]
   [eca.config :as config]))

(set! *warn-on-reflection* true)

(def ^:private export-version 1)

(def ^:private exported-chat-keys
  "Chat fields preserved on export. Excludes live/transient state
   (:tool-calls promises/futures, :last-status-payload) and the
   machine/provider-specific :prompt-cache."
  [:id :title :title-custom? :status :created-at :updated-at :login-provider
   :model :variant :last-api :trust :prompt-id :user-prompt-count :subagent
   :parent-chat-id :startup-context :messages :usage :task])

(defn build-export
  "Build the export envelope map for chat-id from the in-memory db."
  [db chat-id]
  {:eca/version (config/eca-version)
   :eca/export-version export-version
   :exported-at (System/currentTimeMillis)
   :chat (select-keys (get-in db [:chats chat-id]) exported-chat-keys)})

(defn export->edn [export]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint export))))

(defn ^:private slugify [s]
  (let [slug (-> (or s "")
                 string/lower-case
                 (string/replace #"[^a-z0-9]+" "-")
                 (string/replace #"^-+|-+$" ""))]
    (when-not (string/blank? slug)
      (subs slug 0 (min 60 (count slug))))))

(defn ^:private default-filename [chat]
  (let [name (or (slugify (:title chat)) (:id chat) "chat")]
    (str "eca-chat-" name ".edn")))

(defn default-filepath [chat]
  (str (fs/file (fs/temp-dir) (default-filename chat))))

(defn ^:private expand [filepath]
  (str (fs/expand-home (string/trim filepath))))

(defn ^:private dir-target?
  "True when the destination is a directory rather than a file: `.`/`..`, a path
   ending with a separator, or an already-existing directory."
  [trimmed expanded]
  (or (contains? #{"." ".."} trimmed)
      (boolean (re-find #"[/\\]$" trimmed))
      (fs/directory? expanded)))

(defn ^:private resolve-export-path
  "Resolve the destination file path: blank -> temp-dir default; a directory
   target -> default filename inside it; otherwise the given path verbatim."
  [filepath chat]
  (-> (if (string/blank? filepath)
        (default-filepath chat)
        (let [trimmed (string/trim filepath)
              expanded (expand filepath)]
          (if (dir-target? trimmed expanded)
            (str (fs/path expanded (default-filename chat)))
            expanded)))
      fs/absolutize
      fs/normalize
      str))

(defn export-chat!
  "Serialize chat-id as EDN to filepath (defaults to /tmp/eca-chat-<title>.edn
   when blank). Returns {:path <abs-path> :message-count <n>} on success or
   {:error <msg>} on failure."
  [{:keys [db chat-id filepath]}]
  (try
    (if-let [chat (get-in db [:chats chat-id])]
      (let [path (resolve-export-path filepath chat)]
        (io/make-parents path)
        (spit path (export->edn (build-export db chat-id)))
        {:path path
         :message-count (count (:messages chat))})
      {:error (str "Chat not found: " chat-id)})
    (catch Exception e
      {:error (.getMessage e)})))

(defn ^:private valid-export? [data]
  (and (map? data)
       (contains? data :eca/export-version)
       (map? (:chat data))
       (string? (get-in data [:chat :id]))))

(defn import-chat!
  "Read and validate an exported chat EDN file. Returns {:chat <chat-map>} with
   the verbatim chat (its original :id preserved) or {:error <msg>}."
  [{:keys [filepath]}]
  (try
    (if (string/blank? filepath)
      {:error "Missing filepath. Ex: /import /tmp/chat.edn"}
      (let [path (expand filepath)]
        (if-not (fs/exists? path)
          {:error (str "File not found: " path)}
          (let [data (edn/read-string (slurp path))]
            (if (valid-export? data)
              {:chat (:chat data)}
              {:error "Not a valid ECA chat export file."})))))
    (catch Exception e
      {:error (.getMessage e)})))
