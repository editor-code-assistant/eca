(ns eca.features.tools.editor-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.editor :as f.tools.editor]
   [eca.messenger :as messenger]
   [eca.shared :as shared]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(def ^:private sample-source
  "(ns foo)\n\n(defn my-func [x]\n  (inc x))\n\n(my-func 1)\n")

(defn ^:private temp-source-file! []
  (let [f (fs/create-temp-file {:prefix "eca-editor-test-" :suffix ".clj"})]
    (fs/delete-on-exit f)
    (spit (fs/file f) sample-source)
    (str f)))

(defn ^:private definition-handler [args components]
  ((get-in f.tools.editor/definitions ["editor_definition" :handler]) args components))

(defn ^:private references-handler [args components]
  ((get-in f.tools.editor/definitions ["editor_references" :handler]) args components))

(deftest diagnostics-invalid-path-test
  (testing "Path is a directory (invalid)"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text "Path needs to be a file, not a directory."}]}
         (with-redefs [fs/directory? (constantly true)]
           ((get-in f.tools.editor/definitions ["editor_diagnostics" :handler])
            {"path" (h/file-path "/foo/dir")}
            {:messenger (h/messenger)}))))))

(deftest diagnostics-no-diagnostics-test
  (testing "No diagnostics available"
    (reset! (:diagnostics* (h/messenger)) [])
    (is (match?
         {:error false
          :contents [{:type :text
                      :text "No diagnostics found"}]}
         ((get-in f.tools.editor/definitions ["editor_diagnostics" :handler])
          {}
          {:messenger (h/messenger)})))))

(deftest diagnostics-with-code-test
  (testing "Single diagnostic with code"
    (reset! (:diagnostics* (h/messenger))
            [{:uri (h/file-uri "file:///project/foo/src/app.clj")
              :range {:start {:line 10 :character 4}
                      :end {:line 10 :character 8}}
              :severity "error"
              :code "wrong-arity"
              :message "Wrong number of args"}])
    (is (match?
         {:error false
          :contents [{:type :text
                      :text (format "%s:%s:%s: %s: [wrong-arity] %s"
                                     (h/file-path "/project/foo/src/app.clj")
                                     10 4 "error" "Wrong number of args")}]}
         ((get-in f.tools.editor/definitions ["editor_diagnostics" :handler])
          {}
          {:messenger (h/messenger)})))))

(deftest diagnostics-without-code-test
  (testing "Single diagnostic without code"
    (reset! (:diagnostics* (h/messenger))
            [{:uri (h/file-uri "file:///project/foo/src/app.clj")
              :range {:start {:line 3 :character 1}
                      :end {:line 3 :character 5}}
              :severity "warning"
              :message "Unused var"}])
    (is (match?
         {:error false
          :contents [{:type :text
                      :text (format "%s:%s:%s: %s: %s"
                                     (h/file-path "/project/foo/src/app.clj")
                                     3 1 "warning" "Unused var")}]}
         ((get-in f.tools.editor/definitions ["editor_diagnostics" :handler])
          {}
          {:messenger (h/messenger)})))))

(deftest diagnostics-error-test
  (testing "Error getting diagnostics"
    (reset! (:diagnostics* (h/messenger)) 1)
    (is (match?
         {:error true
          :contents [{:type :text
                      :text "Error getting editor diagnostics"}]}
         (with-redefs [messenger/editor-diagnostics (fn [_ _]
                                                      (throw (Exception. "boom")))]
           ((get-in f.tools.editor/definitions ["editor_diagnostics" :handler])
            {}
            {:messenger (h/messenger)}))))))

(deftest definition-invalid-arguments-test
  (testing "path does not exist"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text (format "Path '%s' needs to be an existing file." (h/file-path "/non/existent/file.clj"))}]}
         (definition-handler {"path" (h/file-path "/non/existent/file.clj") "line" 1 "symbol" "foo"}
                             {:messenger (h/messenger)}))))
  (testing "line is not positive"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text "line must be a positive 1-based integer, got '0'."}]}
         (definition-handler {"path" (temp-source-file!) "line" 0 "symbol" "foo"}
                             {:messenger (h/messenger)}))))
  (testing "blank symbol"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text "symbol must be a non-blank string."}]}
         (definition-handler {"path" (temp-source-file!) "line" 1 "symbol" ""}
                             {:messenger (h/messenger)})))))

(deftest definition-position-resolution-test
  (let [path (temp-source-file!)]
    (testing "line beyond end of file"
      (is (match?
           {:error true
            :contents [{:type :text
                        :text (format "Line 42 does not exist in %s (file has 6 lines). Re-read the file and retry with the correct line." path)}]}
           (definition-handler {"path" path "line" 42 "symbol" "my-func"}
                               {:messenger (h/messenger)}))))
    (testing "symbol not in line"
      (is (match?
           {:error true
            :contents [{:type :text
                        :text (format "Symbol 'other-func' not found on line 3 of %s. The file may have changed; re-read it and retry with the correct line and symbol." path)}]}
           (definition-handler {"path" path "line" 3 "symbol" "other-func"}
                               {:messenger (h/messenger)}))))
    (testing "resolves 1-based character of symbol in line and sends file uri"
      (let [captured* (atom nil)]
        (reset! (:definition-response* (h/messenger))
                (fn [uri position]
                  (reset! captured* {:uri uri :position position})
                  {:status "success" :locations []}))
        (definition-handler {"path" path "line" 3 "symbol" "my-func"}
                            {:messenger (h/messenger)})
        ;; line 3 is "(defn my-func [x]" so `my-func` is at 1-based character 7.
        (is (match? {:uri (shared/filename->uri path)
                     :position {:line 3 :character 7}}
                    @captured*))))
    (testing "explicit character takes precedence over symbol lookup"
      (let [captured* (atom nil)]
        (reset! (:definition-response* (h/messenger))
                (fn [_uri position]
                  (reset! captured* position)
                  {:status "success" :locations []}))
        (definition-handler {"path" path "line" 3 "symbol" "my-func" "character" 1}
                            {:messenger (h/messenger)})
        (is (match? {:line 3 :character 1} @captured*))))
    (testing "invalid character argument"
      (is (match?
           {:error true
            :contents [{:type :text
                        :text "character must be a positive 1-based integer, got '2'."}]}
           (definition-handler {"path" path "line" 3 "symbol" "my-func" "character" "2"}
                               {:messenger (h/messenger)}))))
    (testing "prefers whole-symbol occurrence over substring match"
      (let [f (fs/create-temp-file {:prefix "eca-editor-test-" :suffix ".txt"})
            other-path (str f)
            captured* (atom nil)]
        (fs/delete-on-exit f)
        (spit (fs/file f) "foobar foo my-foo\n")
        (reset! (:definition-response* (h/messenger))
                (fn [_uri position]
                  (reset! captured* position)
                  {:status "success" :locations []}))
        (definition-handler {"path" other-path "line" 1 "symbol" "foo"}
                            {:messenger (h/messenger)})
        (is (match? {:line 1 :character 8} @captured*))))
    (testing "falls back to substring occurrence when no whole-symbol match"
      (let [f (fs/create-temp-file {:prefix "eca-editor-test-" :suffix ".txt"})
            other-path (str f)
            captured* (atom nil)]
        (fs/delete-on-exit f)
        (spit (fs/file f) "foobar\n")
        (reset! (:definition-response* (h/messenger))
                (fn [_uri position]
                  (reset! captured* position)
                  {:status "success" :locations []}))
        (definition-handler {"path" other-path "line" 1 "symbol" "foo"}
                            {:messenger (h/messenger)})
        (is (match? {:line 1 :character 1} @captured*))))))

(deftest definition-success-test
  (let [path (temp-source-file!)]
    (testing "locations with preview text from readable files"
      (reset! (:definition-response* (h/messenger))
              {:status "success"
               :locations [{:uri (shared/filename->uri path)
                            :range {:start {:line 3 :character 7}
                                    :end {:line 3 :character 14}}}]})
      (is (match?
           {:error false
            :contents [{:type :text
                        :text (format "%s:3:7: (defn my-func [x]" path)}]}
           (definition-handler {"path" path "line" 6 "symbol" "my-func"}
                               {:messenger (h/messenger)}))))
    (testing "locations without readable file have no preview"
      (reset! (:definition-response* (h/messenger))
              {:status "success"
               :locations [{:uri (h/file-uri "file:///project/src/app.clj")
                            :range {:start {:line 10 :character 2}
                                    :end {:line 10 :character 9}}}]})
      (is (match?
           {:error false
            :contents [{:type :text
                        :text (format "%s:10:2" (h/file-path "/project/src/app.clj"))}]}
           (definition-handler {"path" path "line" 6 "symbol" "my-func"}
                               {:messenger (h/messenger)}))))
    (testing "no locations found is not an error"
      (reset! (:definition-response* (h/messenger)) {:status "success" :locations []})
      (is (match?
           {:error false
            :contents [{:type :text
                        :text (format "No definition found for 'my-func' at %s:6" path)}]}
           (definition-handler {"path" path "line" 6 "symbol" "my-func"}
                               {:messenger (h/messenger)}))))))

(deftest definition-editor-failures-test
  (let [path (temp-source-file!)]
    (testing "no-server status"
      (reset! (:definition-response* (h/messenger)) {:status "no-server" :message "no server for clojure"})
      (is (match?
           {:error true
            :contents [{:type :text
                        :text "No language server available in the editor for this file: no server for clojure. Use eca__grep as fallback."}]}
           (definition-handler {"path" path "line" 3 "symbol" "my-func"}
                               {:messenger (h/messenger)}))))
    (testing "error status"
      (reset! (:definition-response* (h/messenger)) {:status "error" :message "lsp crashed"})
      (is (match?
           {:error true
            :contents [{:type :text
                        :text "Editor failed to find definition: lsp crashed. Use eca__grep as fallback."}]}
           (definition-handler {"path" path "line" 3 "symbol" "my-func"}
                               {:messenger (h/messenger)}))))
    (testing "unexpected response"
      (reset! (:definition-response* (h/messenger)) {:something "else"})
      (is (match?
           {:error true
            :contents [{:type :text
                        :text "Editor failed to find definition. Use eca__grep as fallback."}]}
           (definition-handler {"path" path "line" 3 "symbol" "my-func"}
                               {:messenger (h/messenger)}))))
    (testing "timeout when editor never answers"
      (reset! (:definition-response* (h/messenger)) :block)
      (is (match?
           {:error true
            :contents [{:type :text
                        :text "Timeout waiting for editor definition response. The editor may be unresponsive; use eca__grep as fallback."}]}
           (definition-handler {"path" path "line" 3 "symbol" "my-func"}
                               {:messenger (h/messenger)
                                :config {:lspTimeoutSeconds 1}}))))))

(deftest definition-starting-language-server-test
  (let [path (temp-source-file!)]
    (testing "starting then success re-requests until the server is ready"
      (let [calls* (atom 0)]
        (reset! (:definition-response* (h/messenger))
                (fn [_uri _position]
                  (if (< (swap! calls* inc) 3)
                    {:status "starting"}
                    {:status "success"
                     :locations [{:uri (h/file-uri "file:///project/src/app.clj")
                                  :range {:start {:line 1 :character 1}
                                          :end {:line 1 :character 2}}}]})))
        (binding [f.tools.editor/*starting-poll-interval-ms* 10]
          (is (match?
               {:error false
                :contents [{:type :text
                            :text (format "%s:1:1" (h/file-path "/project/src/app.clj"))}]}
               (definition-handler {"path" path "line" 3 "symbol" "my-func"}
                                   {:messenger (h/messenger)}))))
        (is (= 3 @calls*))))
    (testing "still starting when budget is exhausted"
      (reset! (:definition-response* (h/messenger)) {:status "starting"})
      (binding [f.tools.editor/*starting-poll-interval-ms* 10]
        (is (match?
             {:error true
              :contents [{:type :text
                          :text "The editor is still starting a language server for this file. Retry this tool call soon or use eca__grep as fallback."}]}
             (definition-handler {"path" path "line" 3 "symbol" "my-func"}
                                 {:messenger (h/messenger)
                                  :config {:lspTimeoutSeconds 1}})))))))

(deftest references-test
  (let [path (temp-source-file!)]
    (testing "references found with include_declaration forwarded"
      (let [include* (atom ::unset)]
        (reset! (:references-response* (h/messenger))
                (fn [_uri _position include-declaration]
                  (reset! include* include-declaration)
                  {:status "success"
                   :locations [{:uri (h/file-uri "file:///project/src/a.clj")
                                :range {:start {:line 5 :character 3}
                                        :end {:line 5 :character 10}}}]}))
        (is (match?
             {:error false
              :contents [{:type :text
                          :text (format "%s:5:3" (h/file-path "/project/src/a.clj"))}]}
             (references-handler {"path" path "line" 3 "symbol" "my-func" "include_declaration" false}
                                 {:messenger (h/messenger)})))
        (is (false? @include*))))
    (testing "no references found is not an error"
      (reset! (:references-response* (h/messenger)) {:status "success" :locations []})
      (is (match?
           {:error false
            :contents [{:type :text
                        :text (format "No references found for 'my-func' at %s:3" path)}]}
           (references-handler {"path" path "line" 3 "symbol" "my-func"}
                               {:messenger (h/messenger)}))))
    (testing "results are capped"
      (reset! (:references-response* (h/messenger))
              {:status "success"
               :locations (mapv (fn [i]
                                  {:uri (h/file-uri "file:///project/src/a.clj")
                                   :range {:start {:line (inc i) :character 1}
                                           :end {:line (inc i) :character 2}}})
                                (range 105))})
      (let [text (-> (references-handler {"path" path "line" 3 "symbol" "my-func"}
                                         {:messenger (h/messenger)})
                     :contents
                     first
                     :text)]
        (is (= 101 (count (string/split-lines text))))
        (is (string/includes? text "... and 5 more references omitted (105 total). Narrow your search if needed."))))))

(deftest editor-nav-summary-test
  (testing "summary is generic while args are streaming and specific when parsed"
    (let [def-summary (get-in f.tools.editor/definitions ["editor_definition" :summary-fn])
          ref-summary (get-in f.tools.editor/definitions ["editor_references" :summary-fn])]
      (is (= "LSP definition" (def-summary {:args nil})))
      (is (= "LSP definition: foo" (def-summary {:args {"symbol" "foo"}})))
      (is (= "LSP references" (ref-summary {:args nil})))
      (is (= "LSP references: foo" (ref-summary {:args {"symbol" "foo"}}))))))

(deftest editor-nav-capability-gating-test
  (testing "tools enabled only when client declares capabilities"
    (let [enabled-def? (get-in f.tools.editor/definitions ["editor_definition" :enabled-fn])
          enabled-ref? (get-in f.tools.editor/definitions ["editor_references" :enabled-fn])]
      (is (true? (boolean (enabled-def? {:db {:client-capabilities {:code-assistant {:editor {:definition true}}}}}))))
      (is (not (enabled-def? {:db {:client-capabilities {:code-assistant {:editor {:diagnostics true}}}}})))
      (is (true? (boolean (enabled-ref? {:db {:client-capabilities {:code-assistant {:editor {:references true}}}}}))))
      (is (not (enabled-ref? {:db {}})))
      (testing "disabled via config even when capability is declared"
        (let [config {:toolCall {:editorNav {:enabled false}}}]
          (is (not (enabled-def? {:db {:client-capabilities {:code-assistant {:editor {:definition true}}}}
                                  :config config})))
          (is (not (enabled-ref? {:db {:client-capabilities {:code-assistant {:editor {:references true}}}}
                                  :config config}))))))))
