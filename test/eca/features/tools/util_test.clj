(ns eca.features.tools.util-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [eca.cache :as cache]
   [eca.features.tools.util :as tools.util]
   [matcher-combinators.test :refer [match?]]))

(def ^:private test-tool-call-id "test-truncation-call-1")

(def ^:private ^:dynamic *temp-cache-dir* nil)

(use-fixtures :each
  (fn [f]
    (let [tmp-dir (fs/create-temp-dir {:prefix "eca-util-test"})]
      (try
        (binding [*temp-cache-dir* tmp-dir]
          (with-redefs [cache/global-dir (fn [] (io/file (str tmp-dir)))]
            (f)))
        (finally
          (fs/delete-tree tmp-dir))))))

(defn ^:private make-result [text]
  {:error false
   :contents [{:type :text :text text}]})

(defn ^:private config-with-truncation [lines size-kb]
  {:toolCall {:outputTruncation {:lines lines :sizeKb size-kb}}})

(deftest maybe-truncate-output-no-truncation-needed-test
  (testing "returns result unchanged when output is within limits"
    (let [result (make-result "line1\nline2\nline3")
          config (config-with-truncation 2000 50)]
      (is (= result (tools.util/maybe-truncate-output result config "call-1"))))))

(deftest maybe-truncate-output-truncates-by-line-count-test
  (testing "truncates output when line count exceeds limit and saves full output"
    (let [lines (string/join "\n" (map #(str "line-" %) (range 1 12)))
          result (make-result lines)
          config (config-with-truncation 5 1000)
          truncated (tools.util/maybe-truncate-output result config test-tool-call-id)
          output-text (-> truncated :contents first :text)]
      (is (string/includes? output-text "line-1"))
      (is (string/includes? output-text "line-5"))
      (is (not (string/includes? output-text "line-10")))
      (is (string/includes? output-text "[OUTPUT TRUNCATED]"))
      (is (string/includes? output-text "Full output saved to:"))
      (is (string/includes? output-text test-tool-call-id))
      ;; Verify full output was saved to file
      (let [saved-path (second (re-find #"Full output saved to: (.+)\n" output-text))]
        (is (some? saved-path))
        (is (fs/exists? saved-path))
        (is (string/includes? (slurp saved-path) "line-10"))))))

(deftest maybe-truncate-output-truncates-by-size-test
  (testing "truncates output when size exceeds limit"
    (let [;; Create ~2KB of text in 20 lines (~100 bytes each)
          lines (string/join "\n" (map (fn [i] (str "line-" i "-" (apply str (repeat 90 "x")))) (range 20)))
          result (make-result lines)
          config (config-with-truncation 10 1) ;; 1KB limit, 10 line limit
          truncated (tools.util/maybe-truncate-output result config test-tool-call-id)
          output-text (-> truncated :contents first :text)]
      (is (string/includes? output-text "[OUTPUT TRUNCATED]"))
      (is (false? (:error truncated))))))

(deftest maybe-truncate-output-skips-error-results-test
  (testing "does not truncate error results"
    (let [long-text (string/join "\n" (repeat 5000 "error line"))
          result {:error true :contents [{:type :text :text long-text}]}
          config (config-with-truncation 100 1)]
      (is (= result (tools.util/maybe-truncate-output result config "call-err"))))))

(deftest maybe-truncate-output-nil-config-test
  (testing "returns result unchanged when truncation config is nil"
    (let [result (make-result (string/join "\n" (repeat 5000 "line")))
          config {:toolCall {}}]
      (is (= result (tools.util/maybe-truncate-output result config "call-nil"))))))

(deftest maybe-truncate-output-notice-content-test
  (testing "truncation notice includes instructions for Grep and Read"
    (let [lines (string/join "\n" (repeat 100 "line"))
          result (make-result lines)
          config (config-with-truncation 5 1000)
          truncated (tools.util/maybe-truncate-output result config test-tool-call-id)
          output-text (-> truncated :contents first :text)]
      (is (string/includes? output-text "eca__grep"))
      (is (string/includes? output-text "eca__read_file"))
      (is (string/includes? output-text "offset/limit")))))

(deftest maybe-truncate-output-consolidates-to-single-content-test
  (testing "truncated result has exactly one content entry"
    (let [lines (string/join "\n" (repeat 100 "line"))
          result {:error false
                  :contents [{:type :text :text lines}
                             {:type :text :text "more text"}]}
          config (config-with-truncation 5 1000)
          truncated (tools.util/maybe-truncate-output result config test-tool-call-id)]
      (is (= 1 (count (:contents truncated))))
      (is (match? {:type :text :text string?} (first (:contents truncated)))))))
