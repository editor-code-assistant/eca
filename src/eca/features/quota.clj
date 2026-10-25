(ns eca.features.quota
  "Account-level subscription quota (Claude Pro/Max, ChatGPT/Codex and GitHub
   Copilot), unrelated to chat tokens or API billing. Providers report it on
   their response headers; snapshots are normalized and kept in memory per
   provider under `[:provider-quotas <provider>]`:

   {:updated-at <epoch-seconds>
    :windows [{:id \"5h\" :label \"5h\" :used-percent 42.0
               :resets-at <epoch-seconds> :window-minutes 300}]}

   Window ids, labels and durations come from what the provider reports.
   Snapshots can also be fetched on demand from each provider's usage
   endpoint (see `fetch-quota`), so they show up before any request."
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.shared :as shared])
  (:import
   [java.net URLDecoder]
   [java.nio.charset StandardCharsets]
   [java.time Instant LocalDate OffsetDateTime ZoneOffset]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[QUOTA]")

(def ^:private refresh-ttl-seconds
  "Minimum interval between usage endpoint fetches of a provider."
  60)

(defn ^:private now-seconds ^long []
  (quot (System/currentTimeMillis) 1000))

(defn ^:private parse-number [v]
  (cond
    (number? v) (double v)
    (string? v) (parse-double (string/trim v))))

(defn ^:private round1 [x]
  (/ (Math/round (* 10.0 (double x))) 10.0))

(defn ^:private iso->epoch-seconds [s]
  (when-let [^String s (shared/not-blank s)]
    (or (try (.getEpochSecond (Instant/parse s)) (catch Exception _ nil))
        (try (.toEpochSecond (OffsetDateTime/parse s)) (catch Exception _ nil))
        (try (.toEpochSecond (.atStartOfDay (LocalDate/parse s) ZoneOffset/UTC)) (catch Exception _ nil)))))

(defn ^:private ->epoch-seconds
  "Epoch seconds from an ISO-8601 string or an epoch number (seconds or
   milliseconds)."
  [v]
  (cond
    (number? v) (let [n (long v)] (if (> n 100000000000) (quot n 1000) n))
    (string? v) (or (some-> (parse-long (string/trim v)) ->epoch-seconds)
                    (iso->epoch-seconds v))))

(defn ^:private minutes->label [minutes]
  (when (and minutes (pos? minutes))
    (cond
      (zero? (mod minutes 1440)) (str (quot minutes 1440) "d")
      (zero? (mod minutes 60)) (str (quot minutes 60) "h")
      :else (str minutes "m"))))

(defn ^:private duration-id->minutes [id]
  (when-let [[_ n unit] (re-matches #"(\d+)([mhdw])" id)]
    (* (parse-long n) (case unit "m" 1 "h" 60 "d" 1440 "w" 10080))))

(defn ^:private normalize-headers
  "Lower-cased header name -> first string value."
  [headers]
  (into {}
        (keep (fn [[k v]]
                (let [v (if (sequential? v) (first v) v)]
                  (when (string? v)
                    [(string/lower-case (if (keyword? k) (name k) (str k))) v]))))
        headers))

(defn ^:private anthropic-windows
  "Claude subscription windows from `anthropic-ratelimit-unified-<window>-*`
   headers (e.g. 5h and 7d), utilization being a 0-1 fraction and resets
   epoch seconds."
  [headers]
  (keep (fn [[k v]]
          (when-let [[_ id] (re-matches #"anthropic-ratelimit-unified-(\d+[a-z]+)-utilization" k)]
            (when-let [utilization (parse-number v)]
              (shared/assoc-some
               {:id id
                :label id
                :used-percent (round1 (* 100 utilization))}
               :resets-at (some-> (get headers (str "anthropic-ratelimit-unified-" id "-reset")) parse-number long)
               :window-minutes (duration-id->minutes id)))))
        headers))

(defn ^:private empty-codex-slot?
  "Whether a ChatGPT window slot carries no data: unused and without a
   duration, like the secondary slot of plans without a 5h limit, which report
   their weekly window in the primary slot. The Codex CLI skips those too."
  [used-percent minutes]
  (and (zero? used-percent)
       (not (pos? (or minutes 0)))))

(defn ^:private codex-windows
  "ChatGPT subscription windows from `x-codex-{primary,secondary}-*` headers.
   Other limit families use their own `x-<family>-` prefix."
  [headers]
  (keep (fn [[k v]]
          (when-let [[_ family slot] (re-matches #"x-([a-z0-9-]+)-(primary|secondary)-used-percent" k)]
            (when-let [used-percent (parse-number v)]
              (let [prefix (str "x-" family "-" slot "-")
                    minutes (some-> (get headers (str prefix "window-minutes")) parse-number long)
                    resets-at (or (some-> (get headers (str prefix "reset-at")) parse-number long)
                                  (some->> (get headers (str prefix "reset-after-seconds"))
                                           parse-number
                                           long
                                           (+ (now-seconds))))
                    default-family? (= "codex" family)
                    label (or (minutes->label minutes) slot)]
                (when-not (empty-codex-slot? used-percent minutes)
                  (shared/assoc-some
                   {:id (if default-family? slot (str family "-" slot))
                    :label (if default-family? label (str family " " label))
                    :used-percent (round1 used-percent)}
                   :resets-at resets-at
                   :window-minutes minutes))))))
        headers))

(defn ^:private parse-form [s]
  (into {}
        (keep (fn [pair]
                (let [[k v] (string/split pair #"=" 2)
                      ^String v (or v "")]
                  (when (seq k)
                    [k (URLDecoder/decode v StandardCharsets/UTF_8)]))))
        (string/split s #"&")))

(defn ^:private copilot-label [quota-id]
  (case quota-id
    ("premium_interactions" "premium_models") "Premium"
    "chat" "Chat"
    "completions" "Completions"
    (string/capitalize (string/replace quota-id #"[_-]+" " "))))

(defn ^:private copilot-window [quota-id {:keys [entitlement remaining-percent unlimited? reset]}]
  (when (and remaining-percent
             (not unlimited?)
             (not (contains? #{-1.0 0.0} entitlement)))
    (shared/assoc-some
     {:id quota-id
      :label (copilot-label quota-id)
      :used-percent (round1 (max 0.0 (- 100.0 remaining-percent)))}
     :resets-at (->epoch-seconds reset))))

(defn ^:private prefer-premium-models
  "Newer Copilot backends report `premium_models` alongside the legacy
   `premium_interactions`; keep only the former, like VS Code does."
  [windows]
  (if (some #(= "premium_models" (:id %)) windows)
    (remove #(= "premium_interactions" (:id %)) windows)
    windows))

(defn ^:private copilot-windows
  "GitHub Copilot quotas from `x-quota-snapshot-<quota-id>` headers, whose
   values are url-encoded like `ent=300&rem=95.3&rst=2026-09-01T00:00:00Z`
   (`rem` is the remaining percentage, `ent=-1` means unlimited)."
  [headers]
  (prefer-premium-models
   (keep (fn [[k v]]
           (when-let [[_ quota-id] (re-matches #"x-quota-snapshot-(.+)" k)]
             (let [{:strs [ent rem rst]} (parse-form v)]
               (copilot-window quota-id {:entitlement (parse-number ent)
                                         :remaining-percent (parse-number rem)
                                         :reset rst}))))
         headers)))

(defn ^:private sort-windows [windows]
  (vec (sort-by (juxt #(or (:window-minutes %) Long/MAX_VALUE) :id) windows)))

(defn headers->snapshot
  "Normalized quota snapshot from provider response `headers`, or nil when
   they carry no quota (only subscription logins report it)."
  [headers]
  (let [headers (normalize-headers headers)
        windows (concat (anthropic-windows headers)
                        (codex-windows headers)
                        (copilot-windows headers))]
    (when (seq windows)
      {:updated-at (now-seconds)
       :windows (sort-windows windows)})))

(defn ^:private merge-snapshot [old new]
  (let [by-id #(into {} (map (juxt :id identity)) %)]
    {:updated-at (:updated-at new)
     :windows (sort-windows (vals (merge (by-id (:windows old)) (by-id (:windows new)))))}))

(defn ^:private visible-state
  "What clients render from a snapshot, to avoid notifying them of noise."
  [snapshot]
  (set (map (fn [{:keys [id used-percent resets-at]}]
              [id (Math/round (double used-percent)) (some-> resets-at long (quot 60))])
            (:windows snapshot))))

(defn update!
  "Merges `snapshot` windows into the stored quota of `provider` by window
   id. Returns true when what clients display changed."
  [db* provider snapshot]
  (let [[old new] (swap-vals! db* update-in [:provider-quotas provider] merge-snapshot snapshot)]
    (not= (visible-state (get-in old [:provider-quotas provider]))
          (visible-state (get-in new [:provider-quotas provider])))))

(defn update-from-headers!
  "Updates the quota of `provider` from its response `headers`. Returns true
   when what clients display changed."
  [db* provider headers]
  (boolean
   (when-let [snapshot (headers->snapshot headers)]
     (update! db* provider snapshot))))

(defn subscription?
  "Whether requests to `provider` authenticate with a subscription login
   (OAuth), following the precedence of `llm-util/provider-api-key`, so a
   configured API key taking precedence counts as API billing."
  [provider db config]
  (boolean
   (when provider
     (= :auth/oauth (first (llm-util/provider-api-key provider (get-in db [:auth provider]) config))))))

(defn subscription-providers
  "Providers, logged in or configured, using a subscription login."
  [db config]
  (->> (concat (keys (:auth db)) (keys (:providers config)))
       distinct
       sort
       (filter #(subscription? % db config))))

(def ^:private number-words
  {"one" 1 "two" 2 "three" 3 "four" 4 "five" 5 "six" 6 "seven" 7
   "eight" 8 "nine" 9 "ten" 10 "eleven" 11 "twelve" 12})

(def ^:private unit-minutes
  {"minute" 1 "minutes" 1 "hour" 60 "hours" 60
   "day" 1440 "days" 1440 "week" 10080 "weeks" 10080})

(defn anthropic-usage->snapshot
  "Snapshot from Claude's `api/oauth/usage` response, whose windows are keyed
   by their duration (e.g. `five_hour`, `seven_day`, `seven_day_opus`), with
   utilization as a 0-100 percentage and ISO-8601 resets."
  [body]
  (let [windows (keep (fn [[k v]]
                        (let [[amount unit & scope] (string/split (name k) #"_")
                              n (or (get number-words amount) (some-> amount parse-long))
                              unit-mins (get unit-minutes unit)
                              utilization (when (map? v) (parse-number (:utilization v)))]
                          (when (and n unit-mins utilization)
                            (let [minutes (* n unit-mins)
                                  duration (minutes->label minutes)
                                  scope (when (seq scope) (string/join " " scope))]
                              (shared/assoc-some
                               {:id (cond-> duration scope (str "-" (string/replace scope " " "-")))
                                :label (cond-> duration scope (str " " (string/capitalize scope)))
                                :used-percent (round1 utilization)
                                :window-minutes minutes}
                               :resets-at (->epoch-seconds (:resets_at v)))))))
                      body)]
    (when (seq windows)
      {:updated-at (now-seconds)
       :windows (sort-windows windows)})))

(defn codex-usage->snapshot
  "Snapshot from ChatGPT's `backend-api/wham/usage` response, keeping the
   `primary`/`secondary` window ids used by the Codex response headers."
  [body]
  (let [windows (keep (fn [[slot k]]
                        (when-let [{:keys [used_percent limit_window_seconds reset_at reset_after_seconds]}
                                   (get-in body [:rate_limit k])]
                          (when-let [used-percent (parse-number used_percent)]
                            (let [minutes (some-> limit_window_seconds parse-number long (quot 60))]
                              (when-not (empty-codex-slot? used-percent minutes)
                                (shared/assoc-some
                                 {:id slot
                                  :label (or (minutes->label minutes) slot)
                                  :used-percent (round1 used-percent)}
                                 :resets-at (or (->epoch-seconds reset_at)
                                                (some->> reset_after_seconds parse-number long (+ (now-seconds))))
                                 :window-minutes minutes))))))
                      [["primary" :primary_window] ["secondary" :secondary_window]])]
    (when (seq windows)
      {:updated-at (now-seconds)
       :windows (sort-windows windows)})))

(defn copilot-user->snapshot
  "Snapshot from GitHub's `copilot_internal/user` response `quota_snapshots`."
  [{:keys [quota_snapshots quota_reset_date_utc quota_reset_date]}]
  (let [windows (prefer-premium-models
                 (keep (fn [[quota-id {:keys [entitlement percent_remaining unlimited quota_reset_at]}]]
                         (copilot-window (name quota-id)
                                         {:entitlement (parse-number entitlement)
                                          :remaining-percent (parse-number percent_remaining)
                                          :unlimited? (true? unlimited)
                                          :reset (or quota_reset_at quota_reset_date_utc quota_reset_date)}))
                       quota_snapshots))]
    (when (seq windows)
      {:updated-at (now-seconds)
       :windows (sort-windows windows)})))

(defmulti fetch-quota
  "Fetches the subscription quota of `provider` from its usage endpoint,
   returning a snapshot or nil. Implementations live in provider namespaces
   and dispatch on the provider's base (see `config/provider-base`)."
  (fn [{:keys [provider config]}]
    (config/provider-base provider config)))

(defmethod fetch-quota :default [_] nil)

(defn refresh!
  "Fetches the quota of `provider` when it uses a subscription login and
   wasn't fetched in the last minute (unless `force?`). Best-effort: failures
   are only logged. Returns true when what clients display changed."
  [provider {:keys [db* config force?]}]
  (let [db @db*
        last-fetch (get-in db [:provider-quota-fetched-at provider])
        now (now-seconds)]
    (boolean
     (when (and (subscription? provider db config)
                (or force?
                    (nil? last-fetch)
                    (>= (- now (long last-fetch)) refresh-ttl-seconds)))
       (swap! db* assoc-in [:provider-quota-fetched-at provider] now)
       (try
         (when-let [snapshot (fetch-quota {:provider provider
                                           :provider-auth (get-in db [:auth provider])
                                           :config config})]
           (update! db* provider snapshot))
         (catch Exception e
           (logger/debug logger-tag (format "Could not fetch %s quota: %s" provider (ex-message e)))
           nil))))))

(defn ^:private format-duration [secs]
  (let [secs (max 0 (long secs))]
    (cond
      (< secs 60) "<1m"
      (< secs 3600) (str (quot secs 60) "m")
      (< secs 86400) (let [h (quot secs 3600) m (quot (mod secs 3600) 60)]
                       (if (zero? m) (str h "h") (str h "h " m "m")))
      :else (let [d (quot secs 86400) h (quot (mod secs 86400) 3600)]
              (if (zero? h) (str d "d") (str d "d " h "h"))))))

(defn ^:private window-line [{:keys [label used-percent resets-at]} now]
  (str "- " label ": " (Math/round (double used-percent)) "% used"
       (when resets-at
         (if (> resets-at now)
           (str " · resets in " (format-duration (- resets-at now)))
           " · reset, refreshes on next request"))))

(defn quota-text
  "Human readable quota of the subscription `providers`, for the /quota command."
  [db providers]
  (let [now (now-seconds)]
    (if (empty? providers)
      "No subscription login found. Quota is available when logged in with a subscription (Claude Pro/Max, ChatGPT or GitHub Copilot)."
      (shared/multi-str
       "Subscription quota:"
       (for [provider providers
             :let [{:keys [updated-at windows]} (get-in db [:provider-quotas provider])]]
         (if (seq windows)
           [""
            (str provider " (updated " (format-duration (- now updated-at)) " ago)")
            (map #(window-line % now) windows)]
           ["" (str provider ": not available yet, it shows up after the next request.")]))))))
