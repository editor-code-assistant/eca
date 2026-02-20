(ns eca.shared
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.core.memoize :as memoize]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.walk :as walk]
   [eca.cache :as cache]
   [eca.messenger :as messenger])
  (:import
   [java.net URI]
   [java.nio.file Paths]
   [java.time Instant ZoneId ZoneOffset]
   [java.time.format DateTimeFormatter]
   [org.yaml.snakeyaml Yaml]))

(set! *warn-on-reflection* true)

(defn ^:private java->clj
  "Recursively converts Java collections from SnakeYAML to Clojure equivalents."
  [x]
  (cond
    (instance? java.util.Map x)
    (into {} (map (fn [[k v]] [(str k) (java->clj v)])) x)

    (instance? java.util.List x)
    (mapv java->clj x)

    :else x))

(defn parse-md
  "Parses YAML frontmatter and body from a markdown string.
   Frontmatter must be delimited by --- at the start and end.
   Uses SnakeYAML to handle nested structures (maps, lists).
   Returns a map with parsed YAML keys (as keywords) and :body (content after frontmatter)."
  [content]
  (let [lines (string/split-lines content)]
    (if (and (seq lines)
             (= "---" (string/trim (first lines))))
      (let [after-opening (rest lines)
            metadata-lines (take-while #(not= "---" (string/trim %)) after-opening)
            body-lines (rest (drop-while #(not= "---" (string/trim %)) after-opening))
            yaml-str (string/join "\n" metadata-lines)
            yaml (Yaml.)
            parsed (.load yaml ^String yaml-str)
            metadata (when (instance? java.util.Map parsed)
                       (into {} (map (fn [[k v]] [(keyword k) (java->clj v)]))
                             parsed))]
        (assoc (or metadata {}) :body (string/trim (string/join "\n" body-lines))))
      {:body (string/trim content)})))

(defn global-config-dir
  "Returns the global ECA config directory as a java.io.File.
   Respects XDG_CONFIG_HOME, defaults to ~/.config/eca."
  ^java.io.File []
  (let [xdg-config-home (or (System/getenv "XDG_CONFIG_HOME")
                            (io/file (System/getProperty "user.home") ".config"))]
    (io/file xdg-config-home "eca")))

(def windows-os?
  (.contains (System/getProperty "os.name") "Windows"))

(defn hostname* []
  (try (.getHostName (java.net.InetAddress/getLocalHost))
       (catch java.net.UnknownHostException _ nil)))

(def hostname (memoize hostname*))

(def line-separator
  "The system's line separator."
  (System/lineSeparator))

(defn normalize-api-url [api-url]
  (some-> api-url
          string/trim
          (string/replace #"/+$" "")))

(defn join-api-url
  "Join API base URL and optional relative path with a single slash boundary.
   Handles trailing slashes on base and leading slashes on path."
  [api-url extra-path]
  (let [base (normalize-api-url api-url)
        path (some-> extra-path
                     string/trim
                     (string/replace #"^/+" ""))]
    (cond
      (string/blank? base) nil
      (string/blank? path) base
      :else (str base "/" path))))

(defn uri->filename [uri]
  (let [^URI uri (-> uri
                     (string/replace " " "%20")
                     (URI.))]
    (-> (Paths/get uri) .toString
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

(defn workspaces-as-str [db]
  (string/join ", " (map (comp uri->filename :uri) (:workspace-folders db))))

(defn update-last [coll f]
  (if (seq coll)
    (update coll (dec (count coll)) f)
    coll))

(defn deep-merge [v & vs]
  (letfn [(rec-merge [v1 v2]
            (cond
              (nil? v1) v2
              (nil? v2) v1
              (and (map? v1) (map? v2))
              (reduce-kv (fn [m k v]
                           (if (nil? v)
                             (dissoc m k)
                             (assoc m k (rec-merge (get v1 k) v))))
                         v1
                         v2)
              :else v2))]
    (reduce rec-merge v vs)))

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

(defn usage-sumary [chat-id full-model db]
  (let [last-input-tokens (or (get-in db [:chats chat-id :last-input-tokens]) 0)
        last-output-tokens (or (get-in db [:chats chat-id :last-output-tokens]) 0)
        last-input-cache-creation-tokens (or (get-in db [:chats chat-id :last-input-cache-creation-tokens]) 0)
        last-input-cache-read-tokens (or (get-in db [:chats chat-id :last-input-cache-read-tokens]) 0)
        total-input-tokens (or (get-in db [:chats chat-id :total-input-tokens]) 0)
        total-input-cache-creation-tokens (or (get-in db [:chats chat-id :total-input-cache-creation-tokens]) 0)
        total-input-cache-read-tokens (or (get-in db [:chats chat-id :total-input-cache-read-tokens]) 0)
        total-output-tokens (or (get-in db [:chats chat-id :total-output-tokens]) 0)
        model-capabilities (get-in db [:models full-model])]
    (assoc-some {:session-tokens (+ last-input-tokens
                                    last-input-cache-read-tokens
                                    last-input-cache-creation-tokens
                                    last-output-tokens)}
                :limit (:limit model-capabilities)
                :last-message-cost (tokens->cost last-input-tokens last-input-cache-creation-tokens last-input-cache-read-tokens last-output-tokens model-capabilities)
                :session-cost (tokens->cost total-input-tokens total-input-cache-creation-tokens total-input-cache-read-tokens total-output-tokens model-capabilities))))

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
    (swap! db* assoc-in [:chats chat-id :last-input-tokens] input-tokens)
    (swap! db* assoc-in [:chats chat-id :last-output-tokens] output-tokens)
    (swap! db* update-in [:chats chat-id :total-input-tokens] (fnil + 0) input-tokens)
    (swap! db* update-in [:chats chat-id :total-output-tokens] (fnil + 0) output-tokens)
    (when input-cache-creation-tokens
      (swap! db* assoc-in [:chats chat-id :last-input-cache-creation-tokens] input-cache-creation-tokens)
      (swap! db* update-in [:chats chat-id :total-input-cache-creation-tokens] (fnil + 0) input-cache-creation-tokens))
    (when input-cache-read-tokens
      (swap! db* assoc-in [:chats chat-id :last-input-cache-read-tokens] input-cache-read-tokens)
      (swap! db* update-in [:chats chat-id :total-input-cache-read-tokens] (fnil + 0) input-cache-read-tokens))
    (usage-sumary chat-id full-model @db*)))

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

(defn map->snake-cased-map
  "Converts top-level keyword keys to snake_case strings.
 Used for hook script inputs to follow shell/bash conventions."
  [m]
  (update-keys m #(if (keyword %) (csk/->snake_case %) %)))

(defn obfuscate
  "Obfuscate all but first `preserve-num` and last `preserve-num` characters of a string.
   If the string is 4 characters or less, obfuscate all characters.
   Replace the middle part with asterisks, but always at least 5 asterisks."
  [s & {:keys [preserve-num]
        :or {preserve-num 3}}]
  (when s
    (let [s   (str s)
          len (count s)]
      (cond
        (zero? len)
        s

        (<= len 4)
        (apply str (repeat len "*"))

        :else
        (let [raw-preserve (max 0 preserve-num)
              max-preserve (quot len 2)
              p            (min raw-preserve max-preserve)
              middle-len   (min 10 (max 5 (- len (* 2 p))))
              stars        (apply str (repeat middle-len "*"))]
          (str (subs s 0 p)
               stars
               (subs s (- len p))))))))

(defn normalize-model-name [model]
  (let [model-s (cond
                  (keyword? model) (string/replace-first (str model) ":" "")
                  (string? model) model
                  :else nil)]
    (some-> model-s string/lower-case)))

(defn memoize-by-file-last-modified
  "Return a memoized variant of f where the first argument is a filename.
   The cache key includes the file's last-modified timestamp, so the cache
   automatically invalidates when the file changes."
  [f]
  (let [mf (memoize/memo (fn [file _mtime & args]
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

(defn ms->presentable-date [^Long ms ^String pattern]
  (when ms
    (.format (.atZoneSameInstant (.atOffset (Instant/ofEpochMilli ms) ZoneOffset/UTC)
                                 (ZoneId/systemDefault))
             (DateTimeFormatter/ofPattern pattern))))

(defmacro future*
  "Wrapper for future unless in tests. In non-test envs we spawn a Thread and
   return a promise (derefable) to avoid relying on clojure.core/future which
   can behave differently in some REPL tooling environments."
  [config & body]
  `(if (= "test" (:env ~config))
     ~@body
     (let [p# (promise)
           t# (Thread. (fn []
                         (try
                           (deliver p# (do ~@body))
                           (catch Throwable e#
                             ;; deliver the Throwable so deref can inspect it if needed
                             (deliver p# e#)))))]
       (.start t#)
       p#)))

(defn get-workspaces
  "Returns a vector of all workspace folder paths.
   Returns nil if no workspace folders are configured."
  [db]
  (when-let [folders (seq (:workspace-folders db))]
    (mapv (comp uri->filename :uri) folders)))

(defn db-cache-path
  "Returns the absolute path to the workspace-specific DB cache file as a string.
   Used by hooks to access the cached database."
  [db]
  (str (cache/workspace-cache-file (:workspace-folders db) "db.transit.json" uri->filename)))

(defn compact-side-effect!
  "Performs side effects after chat compaction: replaces history with summary,
   zeros token usage, and notifies the user."
  [{:keys [chat-id full-model db* messenger]} auto-compact?]
  ;; Replace chat history with summary
  (swap! db* (fn [db]
               (assoc-in db [:chats chat-id :messages]
                         [{:role "user"
                           :content [{:type :text
                                      :text (str "The conversation was compacted/summarized, consider this summary:\n"
                                                 (get-in db [:chats chat-id :last-summary]))}]}])))

  ;; Zero chat usage
  (swap! db* assoc-in [:chats chat-id :last-input-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :last-output-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :last-input-cache-creation-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :last-input-cache-read-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :total-input-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :total-output-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :total-input-cache-creation-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :total-input-cache-read-tokens] nil)
  (messenger/chat-content-received
   messenger
   {:chat-id chat-id
    :role :system
    :content {:type :text
              :text (if auto-compact?
                      "Auto-compacted chat"
                      "Compacted chat")}})
  (when-let [usage (usage-sumary chat-id full-model @db*)]
    (messenger/chat-content-received
     messenger
     {:chat-id chat-id
      :role :system
      :content (merge {:type :usage}
                      usage)})))

(defn full-model->provider+model
  [full-model]
  (string/split full-model #"/" 2))
