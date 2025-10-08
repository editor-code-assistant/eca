(ns eca.shared
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.core.memoize :as memoize]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.walk :as walk])
  (:import
   [java.net URI]
   [java.nio.file Paths]))

(set! *warn-on-reflection* true)

(def windows-os?
  (.contains (System/getProperty "os.name") "Windows"))

(defn hostname* []
  (try (.getHostName (java.net.InetAddress/getLocalHost))
       (catch java.net.UnknownHostException _ nil)))

(def hostname (memoize hostname*))

(def line-separator
  "The system's line separator."
  (System/lineSeparator))

(defn uri->filename [uri]
  (let [uri (-> (URI. uri)
                (.toASCIIString)
                (URI.))]
    (-> uri Paths/get .toString
        ;; WINDOWS drive letters
        (string/replace #"^[a-z]:\\" string/upper-case))))

(defn filename->uri [^String filename]
  (let [uri (-> filename io/file .toPath .toUri .toString)
        [_match scheme+auth path] (re-matches #"([a-z:]+//.*?)(/.*)" uri)]
    (str scheme+auth
         (-> path
             (string/replace-first #"^/[a-zA-Z](?::|%3A)/"
                                   (if windows-os?
                                     string/upper-case
                                     string/lower-case))
             (string/replace ":" "%3A")))))

(defn update-last [coll f]
  (if (seq coll)
    (update coll (dec (count coll)) f)
    coll))

(defn deep-merge [v & vs]
  (letfn [(rec-merge [v1 v2]
            (if (and (map? v1) (map? v2))
              (merge-with deep-merge v1 v2)
              v2))]
    (when (some identity vs)
      (reduce #(rec-merge %1 %2) v vs))))

(defn assoc-some
  "Assoc[iate] if the value is not nil. "
  ([m k v]
   (if (nil? v) m (assoc m k v)))
  ([m k v & kvs]
   (let [ret (assoc-some m k v)]
     (if kvs
       (if (next kvs)
         (recur ret (first kvs) (second kvs) (nnext kvs))
         (throw (IllegalArgumentException.
                 "assoc-some expects even number of arguments after map/vector, found odd number")))
       ret))))

(defn update-some
  "Update if the value if not nil."
  ([m k f]
   (if-let [v (get m k)]
     (assoc m k (f v))
     m))
  ([m k f & args]
   (if-let [v (get m k)]
     (assoc m k (apply f v args))
     m)))

(defn multi-str [& strings] (string/join "\n" (remove nil? (flatten strings))))

(defn tokens->cost [input-tokens input-cache-creation-tokens input-cache-read-tokens output-tokens model-capabilities]
  (when-let [{:keys [input-token-cost output-token-cost
                     input-cache-creation-token-cost input-cache-read-token-cost]} model-capabilities]
    (when (and input-token-cost output-token-cost)
      (let [input-cost (* input-tokens input-token-cost)
            input-cost (if (and input-cache-creation-tokens input-cache-creation-token-cost)
                         (+ input-cost (* input-cache-creation-tokens input-cache-creation-token-cost))
                         input-cost)
            input-cost (if (and input-cache-read-tokens input-cache-read-token-cost)
                         (+ input-cost (* input-cache-read-tokens input-cache-read-token-cost))
                         input-cost)]
        (format "%.2f" (+ input-cost
                          (* output-tokens output-token-cost)))))))

(defn usage-msg->usage
  "How this works:
    - tokens: the last message from API already contain the total
              tokens considered, but we save them for cost calculation
    - cost: we count the tokens in past requests done + current one"
  [{:keys [input-tokens output-tokens
           input-cache-creation-tokens input-cache-read-tokens]}
   full-model
   {:keys [chat-id db*]}]
  (when (and output-tokens input-tokens)
    (swap! db* update-in [:chats chat-id :total-input-tokens] (fnil + 0) input-tokens)
    (swap! db* update-in [:chats chat-id :total-output-tokens] (fnil + 0) output-tokens)
    (when input-cache-creation-tokens
      (swap! db* update-in [:chats chat-id :total-input-cache-creation-tokens] (fnil + 0) input-cache-creation-tokens))
    (when input-cache-read-tokens
      (swap! db* update-in [:chats chat-id :total-input-cache-read-tokens] (fnil + 0) input-cache-read-tokens))
    (let [db @db*
          total-input-tokens (get-in db [:chats chat-id :total-input-tokens] 0)
          total-input-cache-creation-tokens (get-in db [:chats chat-id :total-input-cache-creation-tokens] nil)
          total-input-cache-read-tokens (get-in db [:chats chat-id :total-input-cache-read-tokens] nil)
          total-output-tokens (get-in db [:chats chat-id :total-output-tokens] 0)
          model-capabilities (get-in db [:models full-model])]
      (assoc-some {:session-tokens (+ input-tokens
                                      (or input-cache-read-tokens 0)
                                      (or input-cache-creation-tokens 0)
                                      output-tokens)}
                  :limit (:limit model-capabilities)
                  :last-message-cost (tokens->cost input-tokens input-cache-creation-tokens input-cache-read-tokens output-tokens model-capabilities)
                  :session-cost (tokens->cost total-input-tokens total-input-cache-creation-tokens total-input-cache-read-tokens total-output-tokens model-capabilities)))))

(defn map->camel-cased-map [m]
  (let [f (fn [[k v]]
            (if (keyword? k)
              [(csk/->camelCase k) v]
              [k v]))]
    (walk/postwalk (fn [x]
                     (if (map? x)
                       (into {} (map f x))
                       x))
                   m)))

(defn obfuscate
  "Obfuscate all but first 3 and last 3 characters of a string, minimum 5 characters.
   If the string is 4 characters or less, obfuscate all characters.
   Replace the middle part with asterisks, but always at least 5 asterisks."
  [s]
  (when s
    (string/replace
     (if (<= (count s) 4)
       (apply str (repeat (count s) "*"))
       (str (subs s 0 3)
            (apply str (repeat (- (count s) 4) "*"))
            (subs s (- (count s) 3))))
     (string/join "" (repeat (- (count s) 4) "*")) "*****")))

(defn normalize-model-name [model]
  (let [model-s (if (keyword? model)
                  (string/replace-first (str model) ":" "")
                  model)]
    (string/lower-case model-s)))

(defn memoize-by-file-last-modified
  "Return a memoized variant of f where the first argument is a filename.
   The cache key includes the file's last-modified timestamp, so the cache
   automatically invalidates when the file changes."
  [f]
  (let [mf (memoize/memo (fn [file mtime & args]
                           (apply f file args)))
        safe-mtime (fn [file]
                     (try
                       (.lastModified (io/file file))
                       (catch Exception _ 0)))]
    (fn
      ([file]
       (mf file (safe-mtime file)))
      ([file & args]
       (apply mf file (safe-mtime file) args)))))
