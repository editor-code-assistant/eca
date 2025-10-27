(ns eca.features.tools.smart-edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as string]
            [eca.features.tools.smart-edit :as smart-edit]))

(deftest regex-start-of-line-flexible-whitespace-test
  (testing "Regex tier matches start-of-line with flexible inner whitespace and preserves indentation when unique"
    (let [file-content (str "pre\n"
                            "  function  myFunc( a, b ) {\n"
                            "    return a + b;\n"
                            "  }\n"
                            "post\n")
          original "function myFunc(a, b) {"
          new "const yourFunc = (a, b) => {"
          res (smart-edit/apply-smart-edit file-content original new "/tmp/file.js")]
      (is (= file-content (:original-full-content res)))
      (is (string/includes? (:new-full-content res) "  const yourFunc = (a, b) => {"))
      (is (= :regex (:strategy res)))
      ;; Ensure only one replacement occurred
      (is (= 1 (count (re-seq #"const yourFunc" (:new-full-content res))))))))

(deftest regex-replacement-quote-dollar-backslash-test
  (testing "Regex tier treats $ and \\ literally in replacement"
    (let [file-content (str "pre\n"
                            "  function  m ( a ) {\n"
                            "  }\n")
          original "function m(a) {"
          new "const price = '$100' and \\path"
          res (smart-edit/apply-smart-edit file-content original new "/tmp/file.js")
          expected (str "pre\n"
                        "  const price = '$100' and \\path\n"
                        "  }\n")]
      (is (= expected (:new-full-content res)))
      (is (= :regex (:strategy res))))))

(deftest flexible-indentation-with-blank-lines-test
  (testing "Flexible tier preserves indentation across blank lines"
    (let [file-content (str "    a\n"
                            "    \n"
                            "    c\n"
                            "    next\n")
          original "a\n\nc"
          new "x\n\ny"
          res (smart-edit/apply-smart-edit file-content original new "/tmp/file.txt")
          expected (str "    x\n"
                        "    \n"
                        "    y\n"
                        "    next\n")]
      (is (= expected (:new-full-content res)))
      (is (= :flexible (:strategy res))))))

(deftest crlf-preservation-and-original-content-test
  (testing "CRLF inputs preserve CRLF and original_full_content"
    (let [file-content (str "head\r\n"
                            "  function  m ( a ) {\r\n"
                            "  }\r\n")
          original "function m(a) {"
          new "const y = (a) => {"
          res (smart-edit/apply-smart-edit file-content original new "/tmp/file.js")
          expected (str "head\r\n"
                        "  const y = (a) => {\r\n"
                        "  }\r\n")]
      ;; new content uses CRLF
      (is (= expected (:new-full-content res)))
      ;; original_full_content must match exactly
      (is (= file-content (:original-full-content res))))))

(deftest flexible-ambiguity-test
  (testing "Flexible matching returns :ambiguous when multiple regions match"
    (let [file-content (str "  a\n"
                            "    b\n"
                            "x\n"
                            " a \n"
                            "  b\n")
          original (str "a\n"
                        "b")
          res (smart-edit/apply-smart-edit file-content original "X" "/tmp/file.txt")]
      (is (= :ambiguous (:error res)))
      (is (= 2 (:match-count res))))))

(deftest regex-ambiguity-test
  (testing "Regex matching returns :ambiguous when multiple regions match"
    (let [file-content (str "  function  myFunc( a, b ) {\n"
                            "}\n"
                            "  function myFunc(a,b){\n"
                            "}\n")
          original "function myFunc(a, b) {"
          res (smart-edit/apply-smart-edit file-content original "const z=(a,b)=>{" "/tmp/file.js")]
      (is (= :ambiguous (:error res)))
      (is (= 2 (:match-count res))))))

(deftest regex-delimiters-comma-semicolon-test
  (testing "Regex tier handles commas and semicolons as tokens with flexible whitespace"
    (let [file-content "function  f ( a , b ) ;\n"
          original "function f(a,b);"
          new "function g(a,b);"
          res (smart-edit/apply-smart-edit file-content original new "/tmp/file.js")]
      (is (= :regex (:strategy res)))
      (is (= "function g(a,b);\n" (:new-full-content res))))))

(deftest regex-not-mid-line-negative-test
  (testing "Smart-edit does not replace mid-line when only unanchored regex could match (we require start-of-line)"
    (let [file-content (str "const x = myFunc(a, b) {\n"
                            "  return 1;\n"
                            "}\n")
          ;; Include leading spaces so exact/normalized substring won't match mid-line
          original "  myFunc(a, b) {"
          new "  myFuncRenamed(a, b) {"
          res (smart-edit/apply-smart-edit file-content original new "/tmp/file.js")]
      (is (= :not-found (:error res))))))
