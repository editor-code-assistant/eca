(ns eca.features.tools.mcp.clojure-mcp
  (:require
   [clojure.string :as string]
   [eca.diff :as diff]
   [eca.features.tools.mcp :as f.mcp]
   [eca.features.tools.util :as tools.util]))

(defn ^:private clojure-edit-details-before-invocation [name args server {:keys [db ask-approval?]} new-file?]
  (when (not= "0.1.0" (:version server))
    (when ask-approval?
      (let [path (get args "file_path")
            {:keys [error contents]} (f.mcp/call-tool! name (assoc args "dry_run" "new-source") {:db db})]
        (when-not error
          (when-let [new-source (some->> contents (filter #(= :text (:type %))) first :text)]
            (let [{:keys [added removed diff]} (diff/diff (if new-file?
                                                            ""
                                                            (slurp path))
                                                          new-source
                                                          path)]
              {:type :fileChange
               :path path
               :linesAdded added
               :linesRemoved removed
               :diff diff})))))))

(defmethod tools.util/tool-call-details-before-invocation :clojure_edit [name args server ctx]
  (clojure-edit-details-before-invocation name args server ctx false))

(defmethod tools.util/tool-call-details-before-invocation :clojure_edit_replace_sexp [name args server ctx]
  (clojure-edit-details-before-invocation name args server ctx false))

(defmethod tools.util/tool-call-details-before-invocation :file_edit [name args server ctx]
  (clojure-edit-details-before-invocation name args server ctx false))

(defmethod tools.util/tool-call-details-before-invocation :file_write [name args server ctx]
  (clojure-edit-details-before-invocation name args server ctx true))

(defmethod tools.util/tool-call-details-after-invocation :clojure_edit [_name arguments details result ctx]
  (tools.util/tool-call-details-after-invocation :file_edit arguments details result ctx))

(defmethod tools.util/tool-call-details-after-invocation :clojure_edit_replace_sexp [_name arguments details result ctx]
  (tools.util/tool-call-details-after-invocation :file_edit arguments details result ctx))

(defmethod tools.util/tool-call-details-after-invocation :file_edit [_name arguments _details result _ctx]
  (when-not (:error result)
    (when-let [diff (some->> result :contents (filter #(= :text (:type %))) first :text)]
      (let [{:keys [added removed]} (diff/unified-diff-counts diff)]
        {:type :fileChange
         :path (get arguments "file_path")
         :linesAdded added
         :linesRemoved removed
         :diff diff}))))

(defmethod tools.util/tool-call-details-after-invocation :file_write [_name arguments _details result _ctx]
  (when-not (:error result)
    (when-let [diff (some->> result :contents
                             (filter #(= :text (:type %)))
                             first :text
                             (string/split-lines)
                             (drop 2)
                             (string/join "\n"))]
      (let [{:keys [added removed]} (diff/unified-diff-counts diff)]
        {:type :fileChange
         :path (get arguments "file_path")
         :linesAdded added
         :linesRemoved removed
         :diff diff}))))
