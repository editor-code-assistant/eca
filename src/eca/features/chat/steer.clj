(ns eca.features.chat.steer
  "What a typed message is (a command, a skill, a prompt), and messages
   steered into a running turn."
  (:require
   [clojure.string :as string]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.chat.replay :as f.chat.replay]
   [eca.features.commands :as f.commands]
   [eca.logger :as logger]
   [eca.shared :as shared :refer [assoc-some]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn ^:private tokenize-args [^String s]
  (if (string/blank? s)
    []
    (->> (re-seq #"\s*\"([^\"]*)\"|\s*([^\s]+)" s)
         (map (fn [[_ quoted unquoted]] (or quoted unquoted)))
         (vec))))

(defn message->decision [message db config]
  (let [;; Build a name->command map. `into {}` keeps the last entry on key
        ;; collisions, and `all-commands` places plugin/custom entries after
        ;; MCP prompts, so a plugin command wins over an MCP prompt with the
        ;; same prefixed name.
        cmds-by-name (into {} (map (juxt :name identity)) (f.commands/all-commands db config))
        slash? (string/starts-with? message "/")
        possible-command (when slash? (subs message 1))
        [command-name & args] (when possible-command
                                (let [toks (tokenize-args possible-command)] (if (seq toks) toks [""])))
        args (vec args)
        matched (or (get cmds-by-name command-name)
                    ;; a module command typed by its full id, e.g. /eca:compact
                    (when command-name (f.commands/find-command command-name config)))]
    (cond
      (= :mcpPrompt (:type matched))
      (let [[server prompt] (string/split command-name #":" 2)]
        {:type :mcp-prompt
         :server server
         :prompt prompt
         :args args})

      matched
      {:type :eca-command
       :command (:name matched)
       :command-type (:type matched)
       :args args}

      :else
      {:type :prompt-message
       :message message})))

(defn consume-steer-message!
  "Reads and clears any pending steer message for the chat in a single swap.
   If present, adds it as a user message to history and notifies the client."
  [chat-id db* chat-ctx add-to-history!]
  (let [steer-msg* (volatile! nil)]
    (swap! db* (fn [db]
                 (if-let [msg (get-in db [:chats chat-id :steer-message])]
                   (do (vreset! steer-msg* msg)
                       (update-in db [:chats chat-id] dissoc :steer-message))
                   db)))
    (when-let [steer-msg @steer-msg*]
      (let [content-id (str (random-uuid))
            user-message {:role "user"
                          :content [{:type :text :text steer-msg}]
                          :content-id content-id}]
        (add-to-history! user-message)
        (f.chat.lifecycle/send-content! chat-ctx :user {:type :text
                                                        :content-id content-id
                                                        :text (str steer-msg "\n")})))))

(defn ^:private take-steered-command!
  "Parses the pending steer message as a command and, when `pred` accepts the
   `message->decision` result, clears it and returns that decision with the raw
   text under :steer-msg; otherwise leaves the steer message pending and returns nil.
   The command is parsed outside the swap (command lookup reads the filesystem)
   and only removed while the pending message is still the parsed one; if
   another steer landed meanwhile the merged message is parsed again."
  [chat-id db* config pred]
  (loop []
    (when-let [steer-msg (get-in @db* [:chats chat-id :steer-message])]
      (let [decision (message->decision steer-msg @db* config)]
        (when (pred decision)
          (let [[old-db new-db] (swap-vals! db* (fn [db]
                                                  (if (= steer-msg (get-in db [:chats chat-id :steer-message]))
                                                    (update-in db [:chats chat-id] dissoc :steer-message)
                                                    db)))
                removed? (and (= steer-msg (get-in old-db [:chats chat-id :steer-message]))
                              (nil? (get-in new-db [:chats chat-id :steer-message])))]
            (if removed?
              (assoc decision :steer-msg steer-msg)
              (recur))))))))

(defn consume-steered-compact!
  "When the pending steer message is a `/compact` command, clears it, echoes it
   to the client as the user message it was typed as and returns
   {:custom-instructions string :content-id string}; otherwise leaves the steer
   message pending and returns nil."
  [chat-id db* {:keys [config] :as chat-ctx}]
  (when-let [{:keys [args steer-msg]} (and (f.chat.lifecycle/compaction-handled? config)
                                           (take-steered-command! chat-id db* config
                                                                  (fn [{:keys [type command]}]
                                                                    (and (= :eca-command type)
                                                                         (= "compact" command)))))]
    (let [content-id (f.chat.lifecycle/new-content-id)]
      (logger/info logger-tag "Steered /compact consumed" {:chat-id chat-id})
      (f.chat.lifecycle/send-content! chat-ctx :user {:type :text
                                                      :content-id content-id
                                                      :text (str steer-msg "\n")})
      {:custom-instructions (string/join " " args)
       :content-id content-id})))

(def ^:private steer-boundary-commands
  "Native commands that can run while a prompt is in flight without touching the
   running turn: read-only ones, /sync-system-prompt, which only drops the
   prompt cache read by the next prompt, and /forget-approvals, which only drops
   remembered approvals read by the next tool calls. All return :chat-messages.
   Steered, they are queued and executed at the next turn boundary with the
   running turn's context, then the task resumes; any other steered native
   command is refused (see `steer-refused-command`). /btw runs immediately
   instead (`steer-divertible-commands`) and /compact has its own boundary
   handling. #610"
  #{"sync-system-prompt" "costs" "context" "chats" "skills" "rules" "subagents"
    "plugins" "hooks" "prompt-show" "repo-map-show" "doctor" "config" "remote"
    "debug-chat" "export" "approvals" "forget-approvals"})

(defn send-command-chat-messages!
  "Emits a command's :chat-messages result to the client, per target chat."
  [result chat-ctx]
  (doseq [[chat-id {:keys [messages title]}] (:chats result)]
    (let [new-chat-ctx (assoc chat-ctx :chat-id chat-id)]
      (f.chat.replay/send-chat-contents! messages new-chat-ctx)
      (when title
        (f.chat.lifecycle/send-content! new-chat-ctx :system (assoc-some
                                                              {:type :metadata}
                                                              :title title))))))

(defn consume-steered-native-command!
  "When the pending steer message is a native command that can run while the
   turn is in flight (see `steer-boundary-commands`), clears it, echoes it to
   the client as the user message it was typed as and runs it with the running
   turn's context, showing its output without finishing the prompt; the turn
   then resumes. Otherwise leaves the steer message pending. Returns nil. #610"
  [chat-id db* {:keys [config] :as chat-ctx}]
  (when-let [{:keys [command args steer-msg]}
             (take-steered-command! chat-id db* config
                                    (fn [{:keys [type command command-type]}]
                                      (and (= :eca-command type)
                                           (= :native command-type)
                                           (contains? steer-boundary-commands command))))]
    (logger/info logger-tag "Steered command consumed at turn boundary" {:chat-id chat-id :command command})
    (f.chat.lifecycle/send-content! chat-ctx :user {:type :text
                                                    :content-id (f.chat.lifecycle/new-content-id)
                                                    :text (str steer-msg "\n")})
    (try
      (let [{:keys [type] :as result} (f.commands/handle-command! command args chat-ctx)]
        (if (= :chat-messages type)
          (send-command-chat-messages! result chat-ctx)
          (logger/warn logger-tag "Unexpected steered command result" {:chat-id chat-id :command command :type type})))
      (catch Exception e
        (logger/error e)
        (f.chat.lifecycle/send-content! chat-ctx :system {:type :text
                                                          :text (str "Error: " (ex-message e) "\n\nCheck ECA stderr for more details.")})))
    nil))

(def ^:private steer-divertible-commands
  "Commands that are safe to execute immediately while a prompt is running.
   A steered message matching one is handled as a normal prompt right away
   (e.g. /btw forks the chat ASAP) instead of being queued for the running turn."
  #{"btw"})

(defn steer-divertible? [message db config]
  (let [{:keys [type command]} (message->decision message db config)]
    (and (= :eca-command type)
         (contains? steer-divertible-commands command))))

(defn steer-refused-command
  "Returns the command name when the steered message is a native command that
   can't run while a prompt is in flight because it would change the chat or
   start another prompt (e.g. /model, /agent, /resume); nil otherwise. #610"
  [message db config]
  (let [{:keys [type command command-type]} (message->decision message db config)]
    (when (and (= :eca-command type)
               (= :native command-type)
               (not= "compact" command)
               (not (contains? steer-divertible-commands command))
               (not (contains? steer-boundary-commands command)))
      command)))
