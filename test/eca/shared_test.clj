(ns eca.shared-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.shared :as shared]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(deftest uri->filename-test
  (testing "should decode special characters in file URI"
    (is (= (h/file-path "/path+/encoded characters!")
           (shared/uri->filename (h/file-uri "file:///path%2B/encoded%20characters%21")))))
  (testing "Windows URIs"
    (is (= (when h/windows? "C:\\c.clj")
           (when h/windows? (shared/uri->filename "file:/c:/c.clj"))))
    (is (= (when h/windows? "C:\\Users\\FirstName LastName\\c.clj")
           (when h/windows? (shared/uri->filename "file:/c:/Users/FirstName%20LastName/c.clj"))))
    (is (= (when h/windows? "C:\\c.clj")
           (when h/windows? (shared/uri->filename "file:///c:/c.clj"))))))

(deftest assoc-some-test
  (testing "single association"
    (is (= {:a 1} (shared/assoc-some {} :a 1)))
    (is (= {} (shared/assoc-some {} :a nil))))
  (testing "multiple associations"
    (is (= {:a 1 :b 2}
           (shared/assoc-some {} :a 1 :b 2)))
    (is (= {:a 1}
           (shared/assoc-some {} :a 1 :b nil)))
    (is (= {}
           (shared/assoc-some {} :a nil :b nil))))
  (testing "throws on uneven kvs"
    (is (thrown? IllegalArgumentException
                 (shared/assoc-some {} :a 1 :b)))))

(deftest tokens->cost-test
  (let [model-capabilities {:input-token-cost 0.01
                            :output-token-cost 0.02
                            :input-cache-creation-token-cost 0.005
                            :input-cache-read-token-cost 0.001}]
    (testing "basic input/output cost"
      (is (= "0.70" (shared/tokens->cost 30 nil nil 20 model-capabilities))))
    (testing "with cache creation tokens"
      (is (= "0.75" (shared/tokens->cost 30 10 nil 20 model-capabilities))))
    (testing "with cache read tokens"
      (is (= "0.73" (shared/tokens->cost 30 nil 30 20 model-capabilities))))
    (testing "with both cache creation and read tokens"
      (is (= "0.78" (shared/tokens->cost 30 10 30 20 model-capabilities))))
    (testing "returns nil when model is missing from db"
      (is (nil? (shared/tokens->cost 30 nil nil 20 {}))))
    (testing "returns nil when mandatory costs are missing"
      (is (nil? (shared/tokens->cost 30 nil nil 20 {:input-token-cost 0.01}))))))

(deftest deep-merge-test
  (testing "second map as nil returns first map"
    (is (match?
         {:a 1}
         (shared/deep-merge {:a 1}
                            nil))))
  (testing "basic merge"
    (is (match?
         {:a 1
          :b 4
          :c 3
          :d 1}
         (shared/deep-merge {:a 1}
                            {:b 2}
                            {:c 3}
                            {:b 4 :d 1}))))
  (testing "deep merging"
    (is (match?
         {:a 1
          :b {:c {:d 3
                  :e 4}}}
         (shared/deep-merge {:a 1
                             :b {:c {:d 3}}}
                            {:b {:c {:e 4}}}))))
  (testing "deep merging maps with other keys"
    (is (match?
         {:a 1
          :b {:c {:e 3
                  :f 4}
              :d 2}}
         (shared/deep-merge {:a 1
                             :b {:c {:e 3}
                                 :d 2}}
                            {:b {:c {:f 4}}}))))
  (testing "overrides when leaf values are not maps"
    (is (= {:a 2}
           (shared/deep-merge {:a {:b 1}}
                              {:a 2})))
    (is (= {:a {:b 1}}
           (shared/deep-merge {:a 2}
                              {:a {:b 1}}))))
  (testing "Dissoc nil values"
    (is (match?
         {:a 1
          :b {:c {:e 3
                  :f 4}}}
         (shared/deep-merge {:a 1
                             :b {:c {:e 3}
                                 :d 2}}
                            {:b {:c {:f 4}
                                 :d nil}})))))

(deftest future*-test
  (testing "on test env we run on same thread"
    (is (= 1
           (shared/future* {:env "test"}
             1))))
  (testing "on prod env we run a normal future"
    (is (= 1
           @(shared/future* {:env "prod"}
              1)))))

(deftest obfuscate-test
  (testing "nil and empty inputs"
    (is (nil? (shared/obfuscate nil)))
    (is (= "" (shared/obfuscate ""))))

  (testing "length <= 4 is fully obfuscated"
    (is (= "*" (shared/obfuscate "a")))
    (is (= "**" (shared/obfuscate "ab")))
    (is (= "****" (shared/obfuscate "abcd"))))

    (testing "default preserve-num=3 with various lengths"
      ;; length 5: middle forced to at least 5 stars, preserve shrinks to floor(len/2)
      (is (= "ab*****de" (shared/obfuscate "abcde")))
      ;; length 6: prefix 3, 5 stars, suffix 3 => 11 chars
      (is (= "abc*****def" (shared/obfuscate "abcdef")))
      ;; length 7: prefix 3, 5 stars, suffix 3 => 11 chars
      (is (= "abc*****efg" (shared/obfuscate "abcdefg")))
      ;; length 10: prefix 3, 5 stars, suffix 3 => 11 chars
      (is (= "abc*****hij" (shared/obfuscate "abcdefghij"))))
  
    (testing "respect (bounded) preserve-num"
      ;; preserve-num smaller than half the length
      (is (= "a*****f" (shared/obfuscate "abcdef" :preserve-num 1)))
      ;; preserve-num larger than half the length is capped
      (is (= "abc*****def" (shared/obfuscate "abcdef" :preserve-num 10)))))
