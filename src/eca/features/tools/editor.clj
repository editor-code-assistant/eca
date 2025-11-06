(ns eca.features.tools.editor
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.shared :as shared]))

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

(def definitions
  {"editor_diagnostics"
   {:description (tools.util/read-tool-description "editor_diagnostics")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "Optional absolute path to a file to return diagnostics only for that file."}}
                 :required []}
    :handler #'diagnostics
    :enabled-fn (fn [{:keys [db]}] (-> db :client-capabilities :code-assistant :editor :diagnostics))
    :summary-fn (constantly "Checking diagnostics")}})
