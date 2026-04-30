(ns eca.features.tools.util
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(def ^:private logger-tag "[TOOLS-UTIL]")

(defmulti tool-call-details-before-invocation
  "Return the tool call details before invoking the tool."
  (fn [full-name _arguments _server _ctx] (keyword full-name)))

(defmethod tool-call-details-before-invocation :default [_full-name _arguments _server _ctx]
  nil)

(defmulti tool-call-details-after-invocation
  "Return the tool call details after invoking the tool."
  (fn [name _arguments _before-details _result _ctx] (keyword name)))

(defn ^:private json-outputs-if-any [result]
  (when-let [jsons (->> (:contents result)
                        (keep (fn [content]
                                (when (= :text (:type content))
                                  (let [text (string/trim (:text content))]
                                    (when (or (and (string/starts-with? text "{")
                                                   (string/ends-with? text "}"))
                                              (and (string/starts-with? text "[")
                                                   (string/ends-with? text "]")))
                                      (try
                                        (json/generate-string (json/parse-string text) {:pretty true})
                                        (catch Exception e
                                          (logger/warn logger-tag "Could not pretty format json text output for %s: %s" content (.getMessage e))
                                          nil)))))))
                        seq)]
    {:type :jsonOutputs
     :jsons jsons}))

(defmethod tool-call-details-after-invocation :default [_name _arguments before-details result _ctx]
  (or before-details
      (json-outputs-if-any result)))

(defmulti tool-call-destroy-resource!
  "Destroy the tool call resource."
  (fn [full-name _resource-kwd _resource] (keyword full-name)))

(defmethod tool-call-destroy-resource! :default [_name _resource-kwd _resource]
  ;; By default, do nothing
  )

(defn single-text-content [text & [error]]
  {:error (boolean error)
   :contents [{:type :text
               :text text}]})

(defn tool-available?
  [all-tools full-name]
  (boolean (some #(= full-name (:full-name %)) all-tools)))

(defn selector->string [selector]
  (cond
    (keyword? selector) (name selector)
    (string? selector) selector))

(defn tool-selector-matches?
  "Matches tool selectors used by tool approval and hook object matchers.
   Selector forms: server__tool exact full name, native ECA tool short name
   when present in native-tools, otherwise server name."
  [selector tool-call-server tool-call-name native-tools]
  (when-let [selector (selector->string selector)]
    (let [[server-name tool-name] (if (string/includes? selector "__")
                                    (string/split selector #"__" 2)
                                    (if (some #(= selector (:name %)) native-tools)
                                      ["eca" selector]
                                      [selector nil]))]
      (if tool-name
        (and (= tool-call-server server-name)
             (= tool-call-name tool-name))
        (= tool-call-server server-name)))))

(defn workspace-roots-strs [db]
  (->> (:workspace-folders db)
       (map #(shared/uri->filename (:uri %)))
       (string/join "\n")))

(defn workspace-root-paths
  "Returns a vector of workspace root absolute paths from `db`."
  [db]
  (mapv (comp shared/uri->filename :uri) (:workspace-folders db)))

(defn path-outside-workspace?
  "Returns true if `path` is outside any workspace root in `db`.
   Paths inside the ECA tool-call-outputs cache dir are always considered 'inside'.
   Uses shared path normalization so existing and non-existing paths behave consistently."
  [db path]
  (let [p (shared/normalize-path path)
        roots (workspace-root-paths db)]
    (and p
         (not (shared/path-inside-root? p (cache/tool-call-outputs-dir)))
         (not-any? #(shared/path-inside-root? p %) roots))))

(defn require-approval-when-outside-workspace
  "Returns a function suitable for tool `:require-approval-fn` that triggers
   approval when any of the provided `path-keys` in args is outside the
   workspace roots."
  [path-keys]
  (fn [args {:keys [db]}]
    (when (seq path-keys)
      (some (fn [k]
              (when-let [p (get args k)]
                (path-outside-workspace? db p)))
            path-keys))))

(defn command-available? [command & args]
  (try
    (zero? (:exit (apply shell/sh (concat [command] args))))
    (catch Exception _ false)))

(defn invalid-arguments [arguments validator]
  (first (keep (fn [[key pred error-msg]]
                 (let [value (get arguments key)]
                   (when-not (pred value)
                     (single-text-content (string/replace error-msg (str "$" key) (str value))
                                          :error))))
               validator)))

(defn read-tool-description
  "Read tool description from prompts/tools/<tool-name>.md file"
  [tool-name]
  (-> (io/resource (str "prompts/tools/" tool-name ".md"))
      (slurp)))

(defn reorder-schema-required-first
  "Returns schema as an ordered map with `type` first, `required` second when
   present, and the remaining entries in their existing iteration order."
  [{:keys [type required] :as schema}]
  (let [first-entries (cond-> []
                        type (conj [:type type])
                        required (conj [:required required]))]
    (if (seq first-entries)
      (into (apply array-map (mapcat identity first-entries))
            (remove (fn [[k _]] (contains? #{:type :required} k)) schema))
      schema)))

(defn required-params-error
  "Given a tool `parameters` JSON schema (object) and an args map, return a
  single-text-content error when any required parameter is missing. Returns nil
  if all required parameters are present."
  [parameters args]
  (when-let [req (seq (:required parameters))]
    (let [args (update-keys args name)
          missing (->> req (map name) (filter #(nil? (get args %))) vec)]
      (when (seq missing)
        (single-text-content
         (format "INVALID_ARGS: missing required params: %s"
                 (->> missing (map #(str "`" % "`")) (string/join ", ")))
         :error)))))

(defn omit-optional-empty-string-args
  "Drops optional tool arguments whose value is the empty string.
   Required arguments are preserved exactly as provided."
  [parameters args]
  (let [required (->> (:required parameters)
                      (map name)
                      set)]
    (into {}
          (remove (fn [[k v]]
                    (and (= "" v)
                         (not (contains? required (name k))))))
          args)))

(defn ^:private contents->text
  "Concatenates all text contents from a tool result's :contents into a single string."
  [contents]
  (reduce
   (fn [^StringBuilder sb content]
     (case (:type content)
       :text (doto sb
               (.append ^String (:text content))
               (.append "\n"))
       :image (doto sb
                (.append (format "[Image: %s]" (:media-type content)))
                (.append "\n"))
       sb))
   (StringBuilder.)
   contents))

(defn ^:private exceeds-truncation-limits?
  "Returns true if the text exceeds either the line limit or size limit."
  [^String text max-lines max-size-kb]
  (let [size-kb (/ (alength (.getBytes text "UTF-8")) 1024.0)
        line-count (loop [idx 0 count 1]
                     (let [next-idx (.indexOf text "\n" (int idx))]
                       (if (or (= -1 next-idx) (> count max-lines))
                         count
                         (recur (inc next-idx) (inc count)))))]
    (or (> line-count max-lines)
        (> size-kb max-size-kb))))

(defn ^:private truncate-text-lines
  "Truncates text to the given number of lines."
  [^String text max-lines]
  (let [lines (string/split-lines text)]
    (if (<= (count lines) max-lines)
      text
      (string/join "\n" (take max-lines lines)))))

(defn ^:private truncate-text-size
  "Truncates text to the given size in KB, respecting UTF-8 character boundaries."
  [^String text max-size-kb]
  (let [max-bytes (long (* max-size-kb 1024))
        bs (.getBytes text "UTF-8")]
    (if (<= (alength bs) max-bytes)
      text
      (let [truncated (java.util.Arrays/copyOf bs (int max-bytes))
            result (String. truncated "UTF-8")]
        ;; Remove trailing replacement character from split multi-byte sequence
        (if (.endsWith result "\uFFFD")
          (subs result 0 (dec (.length result)))
          result)))))

(defn ^:private truncate-text
  "Truncates text to the given number of lines and size."
  [^String text max-lines max-size-kb]
  (-> text
      (truncate-text-lines max-lines)
      (truncate-text-size max-size-kb)))

(defn maybe-truncate-output
  "Checks if a tool call result exceeds configured output truncation limits.
   When truncation is needed:
   - Saves the full output to ~/.cache/eca/toolCallOutputs/{toolCallId}.txt
   - Truncates the output text to the configured line limit
   - Appends a notice with the saved file path and instructions.
   Returns the (possibly truncated) result."
  [result config tool-call-id]
  (let [max-lines (get-in config [:toolCall :outputTruncation :lines])
        max-size-kb (get-in config [:toolCall :outputTruncation :sizeKb])]
    (if (or (nil? max-lines) (nil? max-size-kb) (:error result))
      result
      (let [full-text (str (contents->text (:contents result)))]
        (if (exceeds-truncation-limits? full-text max-lines max-size-kb)
          (let [saved-path (cache/save-tool-call-output! tool-call-id full-text)
                truncated (truncate-text full-text max-lines max-size-kb)
                notice (str "\n\n[OUTPUT TRUNCATED] The tool call succeeded but the output was truncated. "
                            "Full output saved to: " saved-path "\n"
                            "Use `eca__grep` or `eca__read_file` with offset/limit to view specific sections. Do not full read the file.")]
            (assoc result :contents [{:type :text
                                      :text (str truncated notice)}]))
          result)))))
