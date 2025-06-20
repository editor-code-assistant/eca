(ns eca.features.chat
  (:require
   [babashka.fs :as fs]
   [clojure.set :as set]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.index :as f.index]
   [eca.llm-api :as llm-api]
   [eca.messenger :as messenger]
   [eca.shared :as shared]))

(defn ^:private raw-context->refined [context]
  (mapcat (fn [{:keys [type path]}]
            (case type
              "file" [{:type :file
                       :path path
                       :content-map (llm-api/refine-file-context path)}]
              "directory" (->> (fs/glob path "**")
                               (remove fs/directory?)
                               (map (fn [path]
                                      (let [filename (str (fs/canonicalize path))]
                                        {:type :file
                                         :path filename
                                         :content-map (llm-api/refine-file-context filename)}))))
              nil))
          context))

(defn ^:private build-context [behavior refined-context]
  {:role (str "You are an expert AI coding tool called ECA (Editor Code Assistant).\n"
              "Structure your answer in markdown *WITHOUT* using markdown code block.")
   :behavior (format "Your behavior is to '%s'."
                     (case (keyword behavior)
                       :agent "Help suggesting what needs to be changed if requested, offering help to make itself."
                       :chat "Help suggesting what needs to be changed not suggesting to fix it itself."
                       ""))
   :context (format "<context>\n%s\n</context>"
                    (reduce (fn [msg {:keys [type path content-map]}]
                              (str
                               msg
                               (case type
                                 :file (str path ":\n" content-map)
                                 "")
                               "\n")) "" refined-context))})

(defn default-model [db]
  (if-let [ollama-model (first (filter #(string/starts-with? % config/ollama-model-prefix) (:models db)))]
    ollama-model
    (:default-model db)))

(defn prompt
  [{:keys [message model behavior contexts chat-id request-id]}
   db*
   messenger
   config]
  (let [chat-id (or chat-id
                    (let [new-id (str (random-uuid))]
                      (swap! db* update :chats conj {:id new-id})
                      new-id))]
    (messenger/chat-content-received
     messenger
     {:chat-id chat-id
      :request-id request-id
      :is-complete false
      :role :user
      :content {:type :text
                :text (str message "\n")}})
    (when (seq contexts)
      (messenger/chat-content-received
       messenger
       {:chat-id chat-id
        :request-id request-id
        :is-complete false
        :role :system
        :content {:type :progress
                  :state :running
                  :text "Parsing given context"}}))
    (let [refined-contexts (raw-context->refined contexts)
          context (build-context (or behavior (:chat-default-behavior @db*)) refined-contexts)
          chosen-model (or model (default-model @db*))]
      (messenger/chat-content-received
       messenger
       {:chat-id chat-id
        :request-id request-id
        :role :system
        :content {:type :progress
                  :state :running
                  :text "Generating"}})
      (llm-api/complete! {:model chosen-model
                          :user-prompt message
                          :context context
                          :config config
                          :on-message-received (fn [{:keys [message finish-reason]}]
                                                 (messenger/chat-content-received
                                                  messenger
                                                  {:chat-id chat-id
                                                   :request-id request-id
                                                   :role :assistant
                                                   :content {:type :text
                                                             :text message}})
                                                 (when finish-reason
                                                   (messenger/chat-content-received
                                                    messenger
                                                    {:chat-id chat-id
                                                     :request-id request-id
                                                     :role :system
                                                     :content {:type :progress
                                                               :state :finished}})))
                          :on-error (fn [{:keys [message exception]}]
                                      (messenger/chat-content-received
                                       messenger
                                       {:chat-id chat-id
                                        :request-id request-id
                                        :role :system
                                        :content {:type :text
                                                  :text (str (or message (ex-message exception)) "\n")}})
                                      (messenger/chat-content-received
                                       messenger
                                       {:chat-id chat-id
                                        :request-id request-id
                                        :role :system
                                        :content {:type :progress
                                                  :state :finished}}))})
      {:chat-id chat-id
       :model chosen-model
       :status :success})))

(defn query-context
  [{:keys [query contexts chat-id]}
   db*]
  (let [config (config/all)
        all-subfiles-and-dirs (into []
                                    (comp
                                     (map :uri)
                                     (map shared/uri->filename)
                                     (mapcat (fn [root-filename]
                                               (let [all-files (fs/glob root-filename (str "**" (or query "") "**"))
                                                     all-dirs (filter fs/directory? all-files)
                                                     excluded-dirs (filter #(f.index/ignore? (str %) root-filename config) all-dirs)]
                                                 (->> all-files
                                                      (remove (fn [path]
                                                                (or (some #(fs/starts-with? (str path) %) excluded-dirs)
                                                                    (f.index/ignore? (str path) root-filename config))))))))
                                     (take 200) ;; for performance, user can always make query specific for better results.
                                     (map (fn [file-or-dir]
                                            {:type (if (fs/directory? file-or-dir)
                                                     "directory"
                                                     "file")
                                             :path (str (fs/canonicalize file-or-dir))})))
                                    (:workspace-folders @db*))
        root-dirs (mapv (fn [{:keys [uri]}] {:type "directory"
                                             :path (shared/uri->filename uri)})
                        (:workspace-folders @db*))
        all-contexts (concat root-dirs
                             all-subfiles-and-dirs)]
    {:chat-id chat-id
     :contexts (set/difference (set all-contexts)
                               (set contexts))}))