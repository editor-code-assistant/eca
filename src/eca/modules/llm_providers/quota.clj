(ns eca.modules.llm-providers.quota
  "Account-level subscription quota (Claude Pro/Max, ChatGPT/Codex and GitHub
   Copilot), unrelated to chat tokens or API billing. Providers report it on
   their response headers; snapshots are normalized and kept in memory per
   provider under `[:provider-quotas <provider>]`:

   {:updated-at <epoch-seconds>
    :windows [{:id \"5h\" :label \"5h\" :used-percent 42.0
               :resets-at <epoch-seconds> :window-minutes 300}]}

   Window ids, labels and durations come from what the provider reports:
   each provider module reads its headers (`:quota :headers`) and its usage
   endpoint (`:quota :fetch`, see `fetch-quota`), so quotas show up before
   any request."
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.shared :as shared])
  (:import
   [java.time Instant LocalDate OffsetDateTime ZoneOffset]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[QUOTA]")

(def ^:private refresh-ttl-seconds
  "Minimum interval between usage endpoint fetches of a provider."
  60)

(defn now-seconds ^long []
  (quot (System/currentTimeMillis) 1000))

(defn parse-number [v]
  (cond
    (number? v) (double v)
    (string? v) (parse-double (string/trim v))))

(defn round1 [x]
  (/ (Math/round (* 10.0 (double x))) 10.0))

(defn ^:private iso->epoch-seconds [s]
  (when-let [^String s (shared/not-blank s)]
    (or (try (.getEpochSecond (Instant/parse s)) (catch Exception _ nil))
        (try (.toEpochSecond (OffsetDateTime/parse s)) (catch Exception _ nil))
        (try (.toEpochSecond (.atStartOfDay (LocalDate/parse s) ZoneOffset/UTC)) (catch Exception _ nil)))))

(defn ->epoch-seconds
  "Epoch seconds from an ISO-8601 string or an epoch number (seconds or
   milliseconds)."
  [v]
  (cond
    (number? v) (let [n (long v)] (if (> n 100000000000) (quot n 1000) n))
    (string? v) (or (some-> (parse-long (string/trim v)) ->epoch-seconds)
                    (iso->epoch-seconds v))))

(defn minutes->label [minutes]
  (when (and minutes (pos? minutes))
    (cond
      (zero? (mod minutes 1440)) (str (quot minutes 1440) "d")
      (zero? (mod minutes 60)) (str (quot minutes 60) "h")
      :else (str minutes "m"))))

(defn duration-id->minutes [id]
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

(defn ^:private sort-windows [windows]
  (vec (sort-by (juxt #(or (:window-minutes %) Long/MAX_VALUE) :id) windows)))

(defn snapshot
  "A quota snapshot of `windows`, or nil when there are none."
  [windows]
  (when (seq windows)
    {:updated-at (now-seconds)
     :windows (sort-windows windows)}))

(defn headers->snapshot
  "Normalized quota snapshot from `provider`'s response `headers`, or nil
   when they carry no quota (only subscription logins report it): the
   windows the `:quota :headers` of the provider's base (see
   `config/provider-base`) reads from the headers, by lower-cased name."
  [provider headers config]
  (when-let [windows (get-in (llm-util/provider-declaration config (config/provider-base provider config))
                             [:quota :headers])]
    (snapshot (windows (normalize-headers headers)))))

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
  [db* provider headers config]
  (boolean
   (when-let [snapshot (headers->snapshot provider headers config)]
     (update! db* provider snapshot))))

(defn subscription-providers
  "Providers, logged in or configured, using a subscription login."
  [db config]
  (->> (concat (keys (:auth db)) (keys (:providers config)))
       distinct
       sort
       (filter #(llm-util/subscription? % db config))))

(defn fetch-quota
  "Fetches the subscription quota of `provider` from its usage endpoint,
   returning a snapshot or nil: the `:quota :fetch` of the provider's base
   (see `config/provider-base`)."
  [{:keys [provider config] :as ctx}]
  (when-let [fetch (get-in (llm-util/provider-declaration config (config/provider-base provider config))
                           [:quota :fetch])]
    (fetch ctx)))

(defn refresh!
  "Fetches the quota of `provider` when it uses a subscription login and
   wasn't fetched in the last minute (unless `force?`). Best-effort: failures
   are only logged. Returns true when what clients display changed."
  [provider {:keys [db* config force?]}]
  (let [db @db*
        last-fetch (get-in db [:provider-quota-fetched-at provider])
        now (now-seconds)]
    (boolean
     (when (and (llm-util/subscription? provider db config)
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
