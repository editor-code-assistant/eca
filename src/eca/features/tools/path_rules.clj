(ns eca.features.tools.path-rules
  (:require
   [clojure.string :as string]
   [eca.features.rules :as f.rules]
   [eca.features.tools.util :as tools.util]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private validated-path-rules-key :validated-path-rules)

(defn fetch-rule-available?
  [all-tools]
  (tools.util/tool-available? all-tools "eca__fetch_rule"))

(defn record-validated-rule!
  [db* chat-id rule _match-info]
  (swap! db* update-in [:chats chat-id validated-path-rules-key] (fnil conj #{}) (:id rule)))

(defn validated-rule?
  [db chat-id rule-id]
  (contains? (get-in db [:chats chat-id validated-path-rules-key] #{}) rule-id))

(defn enforce-on-modify?
  "Returns true if the rule should be enforced before file modification.
   Default (nil enforce) is modify — backwards compatible."
  [rule]
  (let [enforce (:enforce rule)]
    (or (nil? enforce)
        (some #(= "modify" %) enforce))))

(defn enforce-on-read?
  "Returns true if the rule should be enforced before file reading.
   Default (nil enforce) is false — only opt-in via explicit `enforce: read`."
  [rule]
  (some #(= "read" %) (:enforce rule)))

(defn applicable-path-scoped-rules
  [config db chat-id agent all-tools target-path]
  (when (and (fetch-rule-available? all-tools)
             (not (string/blank? target-path)))
    (let [full-model (get-in db [:chats chat-id :model])]
      (f.rules/matching-path-scoped-rules config
                                          (:workspace-folders db)
                                          agent
                                          full-model
                                          target-path))))

(defn ^:private missing-path-scoped-rules
  [config db chat-id agent all-tools target-path enforce?]
  (let [target-path (shared/normalize-path target-path)]
    (when target-path
      (->> (applicable-path-scoped-rules config db chat-id agent all-tools target-path)
           (filter (fn [{:keys [rule]}] (enforce? rule)))
           (remove (fn [{:keys [rule]}] (validated-rule? db chat-id (:id rule))))
           vec
           not-empty))))

(defn missing-path-scoped-rules-for-modify
  "Returns rules that require fetching before modifying the target path."
  [config db chat-id agent all-tools target-path]
  (missing-path-scoped-rules config db chat-id agent all-tools target-path enforce-on-modify?))

(defn missing-path-scoped-rules-for-read
  "Returns rules that require fetching before reading the target path."
  [config db chat-id agent all-tools target-path]
  (missing-path-scoped-rules config db chat-id agent all-tools target-path enforce-on-read?))

(defn missing-path-scoped-rules-error
  [target-path missing-rules action]
  (tools.util/single-text-content
   (str "Path-scoped rules must be fetched before " action " '" target-path "'.\n"
        "Fetch the missing rule(s) first:\n"
        (->> missing-rules
             (map (fn [{{:keys [id name]} :rule {:keys [matched-pattern]} :match}]
                    (str "- " name "\n"
                         "  id: " id "\n"
                         "  path: " target-path "\n"
                         "  matched-pattern: " matched-pattern "\n"
                         "  next: call `fetch_rule` with this exact `id` and `path`.")))
             (string/join "\n")))
   :error))

(defn require-fetched-path-scoped-rules
  [target-path {:keys [config db chat-id agent all-tools]}]
  (when-let [missing-rules (missing-path-scoped-rules-for-modify config db chat-id agent all-tools target-path)]
    (missing-path-scoped-rules-error (shared/normalize-path target-path) missing-rules "modifying")))

(defn require-fetched-path-scoped-rules-for-read
  [target-path {:keys [config db chat-id agent all-tools]}]
  (when-let [missing-rules (missing-path-scoped-rules-for-read config db chat-id agent all-tools target-path)]
    (missing-path-scoped-rules-error (shared/normalize-path target-path) missing-rules "reading")))
