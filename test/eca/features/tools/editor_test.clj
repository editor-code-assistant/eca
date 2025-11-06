(ns eca.features.tools.editor-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.editor :as f.tools.editor]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]
   [eca.messenger :as messenger]))

(h/reset-components-before-test)

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
