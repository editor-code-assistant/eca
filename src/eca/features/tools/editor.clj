(ns eca.features.tools.editor
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private diagnostics
  "Return editor diagnostics (e.g., LSP findings)."
  [arguments {:keys [messenger config]}]
  (or (tools.util/invalid-arguments arguments [["path" #(or (nil? %)
                                                            (string/blank? %)
                                                            (not (fs/directory? %))) "Path needs to be a file, not a directory."]])
      (let [uri (some-> (get arguments "path") not-empty shared/filename->uri)
            timeout-ms (* 1000 (get config :lspTimeoutSeconds 30))]
        (try
          (let [response (deref (messenger/editor-diagnostics messenger uri) timeout-ms ::timeout)]
            (if (= response ::timeout)
              {:error true
               :contents [{:type :text
                           :text "Timeout waiting for editor diagnostics response"}]}
              (let [diags (:diagnostics response)]
                (if (seq diags)
                  {:error false
                   :contents [{:type :text
                               :text (reduce
                                      (fn [s {:keys [uri range severity code message]}]
                                        (str s (format "%s:%s:%s: %s: %s%s"
                                                       (shared/uri->filename uri)
                                                       (-> range :start :line)
                                                       (-> range :start :character)
                                                       severity
                                                       (if code (format "[%s] " code) "")
                                                       message)))
                                      ""
                                      diags)}]}
                  {:error false
                   :contents [{:type :text
                               :text "No diagnostics found"}]}))))
          (catch Exception e
            (logger/error (format "Error getting editor diagnostics for arguments %s: %s" arguments e))
            {:error true
             :contents [{:type :text
                         :text "Error getting editor diagnostics"}]})))))

(def ^:dynamic *starting-poll-interval-ms*
  "Interval between re-sent requests while the editor reports the language
   server is still `starting`. Dynamic to speed up tests."
  2000)

(def ^:private max-references-results 100)

(defn ^:private read-file-lines [path]
  (try
    (with-open [rdr (io/reader (fs/file path))]
      (vec (line-seq rdr)))
    (catch Exception _ nil)))

(defn ^:private symbol-char? [c]
  (boolean (or (Character/isLetterOrDigit (char c))
               (#{\_ \- \* \+ \! \? \< \> \= \'} c))))

(defn ^:private find-symbol-character
  "Finds the 1-based character of the first occurrence of `sym` in `line-text`
   that is not part of a bigger symbol, falling back to the first raw
   occurrence (e.g. `foo` in a line containing only `foobar`).
   Character offsets count UTF-16 code units, the LSP default encoding."
  [line-text sym]
  (let [line-count (count line-text)
        sym-count (count sym)
        boundary? (fn [i]
                    (or (neg? i)
                        (>= i line-count)
                        (not (symbol-char? (.charAt ^String line-text i)))))]
    (loop [from 0]
      (if-let [idx (string/index-of line-text sym from)]
        (if (and (boundary? (dec idx))
                 (boundary? (+ idx sym-count)))
          (inc idx)
          (recur (inc idx)))
        (some-> (string/index-of line-text sym) inc)))))

(defn ^:private resolve-position
  "Resolves the 1-based position of `sym` at `line` (1-based) of `path`,
   unless an explicit 1-based `character` is given.
   Returns {:position {:line l :character c}} or {:error-text ...}."
  [path line sym character]
  (if character
    {:position {:line line :character character}}
    (let [lines (read-file-lines path)
          line-text (when (and lines (<= 1 line (count lines)))
                      (nth lines (dec line)))]
      (cond
        (nil? lines)
        {:error-text (format "Could not read file %s." path)}

        (nil? line-text)
        {:error-text (format "Line %s does not exist in %s (file has %s lines). Re-read the file and retry with the correct line."
                             line path (count lines))}

        :else
        (if-let [character (find-symbol-character line-text sym)]
          {:position {:line line :character character}}
          {:error-text (format "Symbol '%s' not found on line %s of %s. The file may have changed; re-read it and retry with the correct line and symbol."
                               sym line path)})))))

(defn ^:private location->str
  "Formats a location as `path:line:character: line-text`, omitting the text
   preview for lines that cannot be read (e.g. non-file URIs like jars)."
  [lines-cache* {:keys [uri range]}]
  (let [file-uri? (boolean (some-> uri (string/starts-with? "file:")))
        path (if file-uri? (shared/uri->filename uri) (str uri))
        line (or (-> range :start :line) 1)
        character (or (-> range :start :character) 1)
        line-text (when (and file-uri? (number? line))
                    (let [lines (or (get @lines-cache* path)
                                    (let [lines (or (read-file-lines path) ::unreadable)]
                                      (swap! lines-cache* assoc path lines)
                                      lines))]
                      (when (and (vector? lines) (<= 1 line (count lines)))
                        (string/trim (nth lines (dec line))))))]
    (cond-> (format "%s:%s:%s" path line character)
      (not (string/blank? line-text)) (str ": " line-text))))

(defn ^:private await-editor-response
  "Derefs `request-fn`'s response, re-requesting while the editor reports the
   language server is `starting`, all bounded by a total `timeout-ms` budget.
   Returns the response, `::timeout` or `::still-starting`."
  [request-fn timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [saw-starting? false]
      (let [remaining (- deadline (System/currentTimeMillis))
            response (if (pos? remaining)
                       (deref (request-fn) remaining ::timeout)
                       ::timeout)]
        (cond
          (= "starting" (:status response))
          (let [remaining (- deadline (System/currentTimeMillis))]
            (if (pos? remaining)
              (do (Thread/sleep (long (min *starting-poll-interval-ms* remaining)))
                  (recur true))
              ::still-starting))

          (and (= ::timeout response) saw-starting?)
          ::still-starting

          :else
          response)))))

(defn ^:private location-results-text [kind sym path line locations]
  (if-let [locations (seq (remove #(string/blank? (:uri %)) locations))]
    (let [lines-cache* (atom {})
          shown (if (= :references kind) (take max-references-results locations) locations)
          omitted (- (count locations) (count shown))]
      (cond-> (string/join "\n" (map #(location->str lines-cache* %) shown))
        (pos? omitted) (str (format "\n... and %s more references omitted (%s total). Narrow your search if needed."
                                    omitted (count locations)))))
    (format "No %s found for '%s' at %s:%s"
            (name kind) sym path line)))

(defn ^:private find-in-editor
  "Ask the editor for the `kind` (:definition or :references) locations of a
   symbol, resolving its position server-side and giving the LLM actionable
   feedback when the editor's language server cannot answer."
  [kind arguments {:keys [messenger config]}]
  (or (tools.util/invalid-arguments arguments [["path" #(and (string? %)
                                                             (not (string/blank? %))
                                                             (fs/exists? %)
                                                             (not (fs/directory? %))) "Path '$path' needs to be an existing file."]
                                               ["line" #(and (number? %) (pos? %)) "line must be a positive 1-based integer, got '$line'."]
                                               ["symbol" #(and (string? %) (not (string/blank? %))) "symbol must be a non-blank string."]
                                               ["character" #(or (nil? %) (and (number? %) (pos? %))) "character must be a positive 1-based integer, got '$character'."]
                                               ["include_declaration" #(or (nil? %) (boolean? %)) "include_declaration must be a boolean, got '$include_declaration'."]])
      (let [path (get arguments "path")
            line (long (get arguments "line"))
            sym (get arguments "symbol")
            character (some-> (get arguments "character") long)
            include-declaration (get arguments "include_declaration")
            kind-str (name kind)
            {:keys [position error-text]} (resolve-position path line sym character)
            timeout-ms (* 1000 (get config :lspTimeoutSeconds 30))]
        (if error-text
          (tools.util/single-text-content error-text :error)
          (try
            (let [uri (shared/filename->uri path)
                  request-fn (case kind
                               :definition #(messenger/editor-definition messenger uri position)
                               :references #(messenger/editor-references messenger uri position include-declaration))
                  response (await-editor-response request-fn timeout-ms)]
              (cond
                (= ::timeout response)
                (tools.util/single-text-content
                 (format "Timeout waiting for editor %s response. The editor may be unresponsive; use eca__grep as fallback." kind-str)
                 :error)

                (= ::still-starting response)
                (tools.util/single-text-content
                 "The editor is still starting a language server for this file. Retry this tool call soon or use eca__grep as fallback."
                 :error)

                (= "success" (:status response))
                (tools.util/single-text-content
                 (location-results-text kind sym path line (:locations response)))

                (= "no-server" (:status response))
                (tools.util/single-text-content
                 (format "No language server available in the editor for this file%s. Use eca__grep as fallback."
                         (if-let [msg (not-empty (:message response))] (str ": " msg) ""))
                 :error)

                :else
                (tools.util/single-text-content
                 (format "Editor failed to find %s%s. Use eca__grep as fallback."
                         kind-str
                         (if-let [msg (not-empty (:message response))] (str ": " msg) ""))
                 :error)))
            (catch InterruptedException _
              (.interrupt (Thread/currentThread))
              (tools.util/single-text-content (format "Editor %s request interrupted." kind-str) :error))
            (catch Exception e
              (logger/error (format "Error getting editor %s for arguments %s: %s" kind-str arguments e))
              (tools.util/single-text-content (format "Error getting editor %s" kind-str) :error)))))))

(defn ^:private definition [arguments components]
  (find-in-editor :definition arguments components))

(defn ^:private references [arguments components]
  (find-in-editor :references arguments components))

(def definitions
  {"editor_diagnostics"
   {:description (tools.util/read-tool-description "editor_diagnostics")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "Optional absolute path to a file to return diagnostics only for that file."}}
                 :required []}
    :handler #'diagnostics
    :enabled-fn (fn [{:keys [db]}] (-> db :client-capabilities :code-assistant :editor :diagnostics))
    :summary-fn (fn [{:keys [args]}]
                  (if-let [path (some-> (get args "path") not-empty)]
                    (format "Checking diagnostics: %s" (fs/file-name (fs/file path)))
                    "Checking all diagnostics"))}
   "editor_definition"
   {:description (tools.util/read-tool-description "editor_definition")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "Absolute path to the file where the symbol appears."}
                              "line" {:type "integer"
                                      :description "1-based line number where the symbol appears."}
                              "symbol" {:type "string"
                                        :description "The symbol name exactly as it appears in that line."}
                              "character" {:type "integer"
                                           :description "Optional 1-based column of the symbol in the line; takes precedence over locating `symbol` in the line."}}
                 :required ["path" "line" "symbol"]}
    :handler #'definition
    :enabled-fn (fn [{:keys [db config]}]
                  (and (get-in config [:toolCall :editorNav :enabled] true)
                       (-> db :client-capabilities :code-assistant :editor :definition)))
    :summary-fn (fn [{:keys [args]}]
                  (if-let [sym (some-> (get args "symbol") not-empty)]
                    (format "LSP definition: %s" sym)
                    "LSP definition"))}
   "editor_references"
   {:description (tools.util/read-tool-description "editor_references")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "Absolute path to the file where the symbol appears."}
                              "line" {:type "integer"
                                      :description "1-based line number where the symbol appears."}
                              "symbol" {:type "string"
                                        :description "The symbol name exactly as it appears in that line."}
                              "character" {:type "integer"
                                           :description "Optional 1-based column of the symbol in the line; takes precedence over locating `symbol` in the line."}
                              "include_declaration" {:type "boolean"
                                                     :description "Whether to include the symbol declaration in the results (default true)."}}
                 :required ["path" "line" "symbol"]}
    :handler #'references
    :enabled-fn (fn [{:keys [db config]}]
                  (and (get-in config [:toolCall :editorNav :enabled] true)
                       (-> db :client-capabilities :code-assistant :editor :references)))
    :summary-fn (fn [{:keys [args]}]
                  (if-let [sym (some-> (get args "symbol") not-empty)]
                    (format "LSP references: %s" sym)
                    "LSP references"))}})
