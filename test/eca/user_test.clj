(ns eca.features.tools.user-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [eca.features.tools.user :as user]))

;; ============================================================================
;; Test Data
;; ============================================================================

(def sample-tool-def
  {:bash "echo {{foo}} {{bar}}"
   :schema {:required ["foo" "bar"]
            :args {"foo" {:type "string" :description "foo arg"}
                   "bar" {:type "string" :description "bar arg"}}}})

(def sample-tool-def-with-types
  {:bash "echo {{name}} {{age}} {{active}}"
   :schema {:required ["name" "age" "active"]
            :args {"name" {:type "string" :description "user name"}
                   "age" {:type "number" :description "user age"}
                   "active" {:type "boolean" :description "is active"}}}})

;; ============================================================================
;; Parameter Substitution Tests
;; ============================================================================

(deftest test-substitute-params
  (testing "basic parameter substitution"
    (is (= "echo 'hello' 'world'"
           (user/substitute-params "echo {{foo}} {{bar}}"
                                   {"foo" "hello" "bar" "world"}))))

  (testing "parameter with special characters"
    (is (= "echo 'hello'\''world'"
           (user/substitute-params "echo {{param}}"
                                   {"param" "hello'world"}))))

  (testing "no parameters to substitute"
    (is (= "echo hello"
           (user/substitute-params "echo hello" {}))))

  (testing "unused parameters"
    (is (= "echo 'hello'"
           (user/substitute-params "echo {{foo}}"
                                   {"foo" "hello" "bar" "unused"})))))

;; ============================================================================
;; Validation Tests
;; ============================================================================

(deftest test-validate-args-required
  (testing "all required arguments provided"
    (is (nil? (user/validate-args (:schema sample-tool-def)
                                  {"foo" "a" "bar" "b"}))))

  (testing "missing required arguments"
    (let [result (user/validate-args (:schema sample-tool-def) {"foo" "a"})]
      (is (str/includes? result "Missing required arguments"))
      (is (str/includes? result "bar"))))

  (testing "multiple missing arguments"
    (let [result (user/validate-args (:schema sample-tool-def) {})]
      (is (str/includes? result "Missing required arguments"))
      (is (str/includes? result "foo"))
      (is (str/includes? result "bar")))))

(deftest test-validate-args-types
  (testing "correct types"
    (is (nil? (user/validate-args (:schema sample-tool-def-with-types)
                                  {"name" "John" "age" 25 "active" true}))))

  (testing "incorrect string type"
    (let [result (user/validate-args (:schema sample-tool-def-with-types)
                                     {"name" 123 "age" 25 "active" true})]
      (is (str/includes? result "must be a string"))))

  (testing "incorrect number type"
    (let [result (user/validate-args (:schema sample-tool-def-with-types)
                                     {"name" "John" "age" "25" "active" true})]
      (is (str/includes? result "must be a number"))))

  (testing "incorrect boolean type"
    (let [result (user/validate-args (:schema sample-tool-def-with-types)
                                     {"name" "John" "age" 25 "active" "true"})]
      (is (str/includes? result "must be a boolean")))))

;; ============================================================================
;; Handler Tests
;; ============================================================================

(deftest test-user-tool-handler-success
  (testing "successful execution"
    (let [handler (user/user-tool-handler sample-tool-def)
          result (handler {"foo" "hi" "bar" "there"}
                          {:db {:workspace-folders []}
                           :config {}
                           :behavior "default"})]
      (is (not (:error result)))
      (is (str/includes? (-> result :contents first :text) "hi there")))))

(deftest test-user-tool-handler-validation-error
  (testing "missing required argument"
    (let [handler (user/user-tool-handler sample-tool-def)
          result (handler {"foo" "hi"}
                          {:db {:workspace-folders []}
                           :config {}
                           :behavior "default"})]
      (is (:error result))
      (is (str/includes? (-> result :contents first :text)
                         "Missing required arguments"))))

  (testing "type validation error"
    (let [handler (user/user-tool-handler sample-tool-def-with-types)
          result (handler {"name" "John" "age" "not-a-number" "active" true}
                          {:db {:workspace-folders []}
                           :config {}
                           :behavior "default"})]
      (is (:error result))
      (is (str/includes? (-> result :contents first :text)
                         "must be a number")))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest test-user-tool-definitions
  (testing "creates tool definitions from config"
    (let [config {:userTools {"test-tool" sample-tool-def}}
          definitions (user/user-tool-definitions config)]
      (is (contains? definitions "test-tool"))
      (let [tool (get definitions "test-tool")]
        (is (= "test-tool" (:name tool)))
        (is (contains? tool :handler))
        (is (contains? tool :parameters))
        (is (str/includes? (:description tool) "test-tool")))))

  (testing "handles empty config"
    (is (= {} (user/user-tool-definitions {}))))

  (testing "handles missing userTools"
    (is (= {} (user/user-tool-definitions {:other-config true})))))

;; ============================================================================
;; Error Handling Tests
;; ============================================================================

(deftest test-error-handling
  (testing "handles invalid tool definition gracefully"
    (let [invalid-def {:bash "echo test"}  ; missing schema
          config {:userTools {"invalid" invalid-def}}
          definitions (user/user-tool-definitions config)]
      ;; Should return empty map on error, not throw
      (is (map? definitions)))))
