(ns eca.modules.data
  "Folders where modules keep their files, in the workspace's cache: one per
   module, `modules/<namespace>/<name>/`, and in it one per chat,
   `chats/<chat-id>/`, removed with the chat."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private modules-dir
  "In the session's cache folder: the one of the workspaces it started with,
   like the chats'."
  [db]
  (fs/path (cache/workspace-cache-dir (or (:initial-workspace-folders db) (:workspace-folders db))
                                      shared/uri->filename)
           "modules"))

(defn ^:private safe-name
  "A file name for `s`, which comes from a module id or a chat id."
  [s]
  (let [s (string/replace (str s) #"[^A-Za-z0-9_.-]" "_")]
    (if (re-matches #"\.*" s) (str "_" s) s)))

(defn module-dir
  "Module `id`'s folder, which may not exist yet."
  [db id]
  (fs/path (modules-dir db) (safe-name (namespace id)) (safe-name (name id))))

(defn chat-dir
  "Module `id`'s folder for chat `chat-id`, which may not exist yet."
  [db id chat-id]
  (fs/path (module-dir db id) "chats" (safe-name chat-id)))

(defn delete-chat-dirs!
  "Removes every module's folder for chat `chat-id`."
  [db chat-id]
  (let [root (modules-dir db)]
    (when (fs/directory? root)
      (doseq [ns-dir (fs/list-dir root)
              :when (fs/directory? ns-dir)
              module-dir (fs/list-dir ns-dir)
              :let [dir (fs/path module-dir "chats" (safe-name chat-id))]
              :when (fs/directory? dir)]
        (fs/delete-tree dir)))))
