(ns eca.llm-providers.openai-codex
  (:require
   [clojure.string :as string]

   [eca.shared :refer [assoc-some]]))

(set! *warn-on-reflection* true)

(def ^:private codex-compatibility-version "0.146.0")

(def responses-url "https://chatgpt.com/backend-api/codex/responses")
(def models-url
  (str "https://chatgpt.com/backend-api/codex/models?client_version="
       codex-compatibility-version))

(def request-profile :chatgpt-codex)

(def ^:private responses-lite-fallbacks
  {"gpt-5.6-sol" {:default-reasoning-effort "low"
                  :supported-reasoning-efforts ["low" "medium" "high" "xhigh" "max"]}
   "gpt-5.6-terra" {:default-reasoning-effort "medium"
                    :supported-reasoning-efforts ["low" "medium" "high" "xhigh" "max"]}
   "gpt-5.6-luna" {:default-reasoning-effort "medium"
                   :supported-reasoning-efforts ["low" "medium" "high" "xhigh" "max"]}})

(def ^:private reasoning-efforts
  #{"none" "low" "medium" "high" "xhigh" "max"})

(defn responses-profile? [profile]
  (= request-profile profile))

(defn new-turn-context
  "Creates state shared by every request and retry in one user turn.
   A supplied conversation ID keeps routing stable across turns in the same chat."
  [conversation-id]
  (let [conversation-id (or (some-> conversation-id str not-empty)
                            (str (random-uuid)))]
    {:session-id conversation-id
     :thread-id conversation-id
     :turn-state* (atom nil)}))

(defn request-headers
  [{:keys [account-id turn-context responses-lite?]}]
  (let [{:keys [session-id thread-id turn-state*]} turn-context]
    (assoc-some
     ;; The backend reports a mismatched Originator/User-Agent pair as
     ;; server_is_overloaded, so keep Codex's default identity paired.
     {"Originator" "codex_cli_rs"
      "User-Agent" (str "codex_cli_rs/" codex-compatibility-version)}
     "ChatGPT-Account-ID" account-id
     "Session-ID" session-id
     "Thread-ID" thread-id
     "x-client-request-id" thread-id
     "x-codex-turn-state" (some-> turn-state* deref)
     "x-openai-internal-codex-responses-lite" (when responses-lite? "true"))))

(defn capture-turn-state!
  "Stores the first routing state returned for a turn, matching Codex's
   first-write-wins behavior."
  [turn-context turn-state]
  (when-let [turn-state* (:turn-state* turn-context)]
    (when-not (string/blank? turn-state)
      (compare-and-set! turn-state* nil turn-state))))

(defn ^:private normalize-reasoning-efforts [levels]
  (->> levels
       (keep #(if (map? %) (:effort %) %))
       (map #(if (= "ultra" %) "max" %))
       (filter reasoning-efforts)
       distinct
       vec
       not-empty))

(defn ^:private reasoning-variants [efforts]
  (when efforts
    (into {}
          (map (fn [effort]
                 [effort {:reasoning {:effort effort :summary "auto"}}]))
          efforts)))

(defn live-model-discovery
  [{:keys [use_responses_lite default_reasoning_level
           supported_reasoning_levels supports_parallel_tool_calls] :as model}]
  (let [efforts (normalize-reasoning-efforts supported_reasoning_levels)]
    (assoc-some (cond-> {}
                  (contains? model :use_responses_lite)
                  (assoc :discovered-codex-responses-lite? (true? use_responses_lite)))
                :discovered-default-reasoning-effort default_reasoning_level
                :discovered-supports-parallel-tool-calls? supports_parallel_tool_calls
                :discovered-variants (reasoning-variants efforts))))

(defn responses-lite-fallback [model]
  (when-let [{:keys [default-reasoning-effort supported-reasoning-efforts]}
             (get responses-lite-fallbacks (string/lower-case (str model)))]
    {:discovered-codex-responses-lite? true
     :discovered-default-reasoning-effort default-reasoning-effort
     :discovered-variants (reasoning-variants supported-reasoning-efforts)}))

(defn responses-lite-body
  "Projects a regular Responses request into the Codex Responses Lite shape."
  [body]
  (let [instructions (:instructions body)
        tools (->> (:tools body)
                   (filterv #(= "function" (:type %))))
        input (cond-> [{:type "additional_tools"
                        :role "developer"
                        :tools tools}]
                (not (string/blank? instructions))
                (conj {:type "message"
                       :role "developer"
                       :content [{:type "input_text"
                                  :text instructions}]})

                true
                (into (:input body)))]
    (-> body
        (dissoc :instructions :tools)
        (assoc :input input
               :parallel_tool_calls false
               :reasoning (assoc (or (:reasoning body) {})
                                 :context "all_turns")))))
