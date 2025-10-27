(ns eca.features.tools.text-match-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca.features.tools.text-match :as text-match]
            [eca.test-helper :as h]))

(deftest normalize-for-matching-test
  (testing "CRLF to LF normalization"
    (is (= "line1\nline2\nline3"
           (text-match/normalize-for-matching "line1\r\nline2\r\nline3"))))

  (testing "CR to LF normalization"
    (is (= "line1\nline2\nline3"
           (text-match/normalize-for-matching "line1\rline2\rline3"))))

  (testing "Trailing whitespace removal"
    (is (= "line1\nline2\nline3"
           (text-match/normalize-for-matching "line1  \nline2   \nline3\t"))))

  (testing "Mixed line endings and whitespace"
    (is (= "line1\nline2\nline3"
           (text-match/normalize-for-matching "line1  \r\nline2   \r\nline3\t")))))

(deftest apply-content-change-to-string-test
  (testing "Exact match preserves line ending style - CRLF"
    (let [file-content "line1\r\nline2\r\nline3\r\nline4"
          original-content "line2"
          new-content "CHANGED"
          path "/tmp/test-file.txt"
          result (text-match/apply-content-change-to-string file-content original-content new-content false path)]
      (is (= file-content (:original-full-content result)))
      (is (= "line1\r\nCHANGED\r\nline3\r\nline4" (:new-full-content result)))
      (is (= :exact (:strategy result)))))

  (testing "Exact match preserves line ending style - LF"
    (let [file-content "line1\nline2\nline3\nline4"
          original-content "line2"
          new-content "CHANGED"
          path "/tmp/test-file.txt"
          result (text-match/apply-content-change-to-string file-content original-content new-content false path)]
      (is (= file-content (:original-full-content result)))
      (is (= "line1\nCHANGED\nline3\nline4" (:new-full-content result)))
      (is (= :exact (:strategy result)))))

  (testing "Normalized match with line ending preservation - CRLF"
    (let [file-content "line1\r\nline2\r\nline3\r\n"  ; File with trailing newline
          original-content "line1\nline2\nline3"  ; Different line endings (LF)
          new-content "REPLACED"
          path "/tmp/test-file.txt"
          result (text-match/apply-content-change-to-string file-content original-content new-content false path)]
      (is (= "REPLACED\r\n" (:new-full-content result)))  ; Preserves CRLF and trailing newline
      (is (= :normalized (:strategy result)))))

  (testing "Normalized match with line ending preservation - LF"
    (let [file-content "prefix\nline1\nline2\nsuffix"
          original-content "line1\r\nline2"  ; Matches normalized but not exact (has CRLF)
          new-content "CHANGED"
          path "/tmp/test-file.txt"
          result (text-match/apply-content-change-to-string file-content original-content new-content false path)]
      (is (= "prefix\nCHANGED\nsuffix" (:new-full-content result)))  ; Preserves LF from file
      (is (= :normalized (:strategy result)))))

  (testing "Content not found"
    (let [file-content "foo\nbar\nbaz"
          original-content "nonexistent"
          new-content "CHANGED"
          path "/tmp/test-file.txt"
          result (text-match/apply-content-change-to-string file-content original-content new-content false path)]
      (is (= :not-found (:error result)))))

  (testing "Ambiguous match (multiple occurrences, all? false)"
    (let [file-content "foo\nbar\nfoo\nbaz"
          original-content "foo"
          new-content "CHANGED"
          path "/tmp/test-file.txt"
          result (text-match/apply-content-change-to-string file-content original-content new-content false path)]
        ;; This should succeed with exact match first, not go to normalization path
        ;; So it will replace first occurrence only, not detect ambiguity
      (is (= file-content (:original-full-content result)))
      (is (= "CHANGED\nbar\nfoo\nbaz" (:new-full-content result)))
      (is (= :exact (:strategy result)))))

  (testing "Multiple occurrences with all? true"
    (let [file-content "foo\nbar\nfoo\nbaz"
          original-content "foo"
          new-content "CHANGED"
          path "/tmp/test-file.txt"
          result (text-match/apply-content-change-to-string file-content original-content new-content true path)]
      (is (= file-content (:original-full-content result)))
      (is (= "CHANGED\nbar\nCHANGED\nbaz" (:new-full-content result)))
      (is (= :exact (:strategy result)))))

  (testing "Exact match replaces first occurrence with line ending preservation"
    (let [file-content "line1\r\nline2\r\nline1\r\nline3"
          original-content "line1"  ; Will match both occurrences
          new-content "CHANGED"
          path "/tmp/test-file.txt"
          result (text-match/apply-content-change-to-string file-content original-content new-content false path)]
      ;; Exact match finds "line1", replaces first occurrence, preserves CRLF
      (is (= "CHANGED\r\nline2\r\nline1\r\nline3" (:new-full-content result)))
      (is (= :exact (:strategy result))))))

(deftest normalized-ambiguity-test
  (testing "Normalized ambiguous match detection"
    (let [file-content "line1  \nline2\nline1\t\nline2\n"
          original-content "line1\nline2"
          new-content "CHANGED"
          path "/tmp/test-file.txt"
          result (text-match/apply-content-change-to-string file-content original-content new-content false path)]
      (is (= :ambiguous (:error result))))))

(deftest multiple-occurrences-normalized-all-true-test
  (testing "Multiple occurrences replaced using normalized matching when line endings differ"
    (let [file-content "foo\r\nbar\r\nfoo\r\n"
          original-content "foo\n"  ; LF in original, CRLF in file
          new-content "Z\n"
          path "/tmp/test-file.txt"
          result (text-match/apply-content-change-to-string file-content original-content new-content true path)]
      ;; Should replace both occurrences and preserve CRLF and trailing newline
      (is (= "Z\r\nbar\r\nZ\r\n" (:new-full-content result)))
      (is (= :normalized (:strategy result))))))

;; Keep the old test for backward compatibility testing
(deftest file-change-full-content-test
  (testing "Backward compatibility - file-based function still works"
    (let [file-path (h/file-path "/tmp/test-file.txt")
          file-content "line1\nline2\nline3"
          original-content "line2"
          new-content "CHANGED"]
      (with-redefs [slurp (constantly file-content)]
        (let [result (text-match/apply-content-change-to-file file-path original-content new-content false)]
          (is (= file-content (:original-full-content result)))
          (is (= "line1\nCHANGED\nline3" (:new-full-content result)))
          (is (= :exact (:strategy result))))))))
