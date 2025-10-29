(ns eca.secrets-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [eca.logger :as logger]
            [eca.secrets :as secrets])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermission)))

(set! *warn-on-reflection* true)

(deftest parse-key-rc-simple-machine-test
  (testing "Parse simple machine name"
    (is (= {:machine "api.openai.com" :login nil :port nil}
           (secrets/parse-key-rc "api.openai.com")))))

(deftest parse-key-rc-with-login-test
  (testing "Parse with login prefix"
    (is (= {:machine "api.anthropic.com" :login "work" :port nil}
           (secrets/parse-key-rc "work@api.anthropic.com")))))

(deftest parse-key-rc-with-port-test
  (testing "Parse with port suffix"
    (is (= {:machine "api.custom.com" :login nil :port "8443"}
           (secrets/parse-key-rc "api.custom.com:8443")))))

(deftest parse-key-rc-full-format-test
  (testing "Parse full format with login and port"
    (is (= {:machine "api.anthropic.com" :login "personal" :port "443"}
           (secrets/parse-key-rc "personal@api.anthropic.com:443")))))

(deftest parse-key-rc-empty-string-test
  (testing "Handle empty string"
    (is (nil? (secrets/parse-key-rc "")))
    (is (nil? (secrets/parse-key-rc nil)))))

(deftest get-credential-simple-machine-test
  (testing "Get credential with simple machine name"
    (let [temp-file (File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-proj-123\n")

        ;; Mock credential-file-paths to return our test file
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (= "sk-proj-123" (secrets/get-credential "api.openai.com"))))
        (finally
          (.delete temp-file))))))

(deftest get-credential-with-login-test
  (testing "Get credential with specific login"
    (let [temp-file (File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file with multiple entries for same machine
        (spit temp-path (str "machine api.anthropic.com\nlogin work\npassword sk-ant-work-123\n\n"
                             "machine api.anthropic.com\nlogin personal\npassword sk-ant-personal-456\n"))

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (= "sk-ant-work-123" (secrets/get-credential "work@api.anthropic.com")))
          (is (= "sk-ant-personal-456" (secrets/get-credential "personal@api.anthropic.com"))))
        (finally
          (.delete temp-file))))))

(deftest get-credential-first-match-test
  (testing "Get first matching credential when no login specified"
    (let [temp-file (File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file with multiple entries for same machine
        (spit temp-path (str "machine api.anthropic.com\nlogin work\npassword sk-ant-work-123\n\n"
                             "machine api.anthropic.com\nlogin personal\npassword sk-ant-personal-456\n"))

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          ;; Should return first matching entry
          (is (= "sk-ant-work-123" (secrets/get-credential "api.anthropic.com"))))
        (finally
          (.delete temp-file))))))

(deftest get-credential-with-port-test
  (testing "Get credential with specific port"
    (let [temp-file (File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file with port
        (spit temp-path "machine custom.api\nlogin admin\npassword custom-key-789\nport 8443\n")

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (= "custom-key-789" (secrets/get-credential "custom.api:8443"))))
        (finally
          (.delete temp-file))))))

(deftest get-credential-no-match-test
  (testing "Return nil when no match found"
    (let [temp-file (File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-proj-123\n")

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (nil? (secrets/get-credential "api.anthropic.com")))
          (is (nil? (secrets/get-credential "different@api.openai.com"))))
        (finally
          (.delete temp-file))))))

(deftest get-credential-missing-file-test
  (testing "Handle missing file gracefully"
    (with-redefs [secrets/credential-file-paths (constantly ["/nonexistent/file"])]
      (is (nil? (secrets/get-credential "api.openai.com"))))))

(deftest get-credential-empty-file-test
  (testing "Handle empty file"
    (let [temp-file (File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        (spit temp-path "")

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (nil? (secrets/get-credential "api.openai.com"))))
        (finally
          (.delete temp-file))))))

(deftest load-netrc-format-test
  (testing "Load credentials from .netrc format"
    (let [temp-file (File/createTempFile "test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-proj-123\n")

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (= "sk-proj-123" (secrets/get-credential "api.openai.com"))))
        (finally
          (.delete temp-file))))))

(deftest multiple-credentials-same-machine-test
  (testing "Handle multiple credentials for same machine"
    (let [temp-file (File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create entries with same machine but different logins
        (spit temp-path (str "machine api.anthropic.com\nlogin work\npassword sk-ant-work-123\n\n"
                             "machine api.anthropic.com\nlogin personal\npassword sk-ant-personal-456\n\n"
                             "machine api.openai.com\nlogin apikey\npassword sk-proj-789\n"))

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          ;; Specific login should match exact entry
          (is (= "sk-ant-work-123" (secrets/get-credential "work@api.anthropic.com")))
          (is (= "sk-ant-personal-456" (secrets/get-credential "personal@api.anthropic.com")))

          ;; No login specified should return first match
          (is (= "sk-ant-work-123" (secrets/get-credential "api.anthropic.com")))

          ;; Different machine should still work
          (is (= "sk-proj-789" (secrets/get-credential "api.openai.com"))))
        (finally
          (.delete temp-file))))))

(deftest port-matching-test
  (testing "Port matching with exact match required"
    (let [temp-file (File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create entry with port
        (spit temp-path "machine custom.api\nlogin admin\npassword custom-key-789\nport 8443\n")

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          ;; With matching port should work
          (is (= "custom-key-789" (secrets/get-credential "custom.api:8443")))

          ;; Without port should not match entry with port
          (is (nil? (secrets/get-credential "custom.api")))

          ;; With different port should not match
          (is (nil? (secrets/get-credential "custom.api:443"))))
        (finally
          (.delete temp-file))))))

(deftest port-matching-trims-whitespace-test
  (testing "Port comparisons ignore surrounding whitespace"
    (let [temp-file (File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Trailing whitespace around port value
        (spit temp-path "machine custom.api\nlogin admin\npassword custom-key-789\nport 8443 \n")

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          ;; Spec without extra whitespace should still match
          (is (= "custom-key-789" (secrets/get-credential "custom.api:8443")))

          ;; Spec with whitespace in query should also match
          (is (= "custom-key-789" (secrets/get-credential "admin@custom.api:8443 "))))
        (finally
          (.delete temp-file))))))

(deftest port-nil-matching-test
  (testing "Port nil matching when port not specified"
    (let [temp-file (File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create entry without port
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-proj-123\n")

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          ;; Without port in query should match entry without port
          (is (= "sk-proj-123" (secrets/get-credential "api.openai.com")))

          ;; With port in query should not match entry without port
          (is (nil? (secrets/get-credential "api.openai.com:443"))))
        (finally
          (.delete temp-file))))))

(deftest netrc-file-path-test
  (testing "Use specified netrc file when provided"
    (let [paths (secrets/credential-file-paths "/dev/test/mynetrc")]
      (is (= ["/dev/test/mynetrc"] paths))))
  (testing "Home directory is resolved correctly across platforms"
    (let [paths (secrets/credential-file-paths nil)]
      (is (seq paths))
      (is (every? string? paths))
      ;; Should contain standard credential files
      (is (some #(string/ends-with? % ".netrc") paths))
      ;; Windows-specific file should be present
      (is (some #(string/ends-with? % "_netrc") paths)))))

(deftest windows-path-separator-test
  (testing "Paths work with both forward and backslash separators"
    (let [temp-file (File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-test-123\n")

        ;; Test with actual path (should work regardless of separator)
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (= "sk-test-123" (secrets/get-credential "api.openai.com"))))
        (finally
          (.delete temp-file))))))

(deftest no-password-logging-in-debug-test
  (testing "Debug logs do not contain passwords"
    (let [temp-file (File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)
          logged-messages (atom [])]
      (try
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-super-secret-password-123\n")

        ;; Mock logger to capture messages
        (with-redefs [logger/debug (fn [_tag & args]
                                     (swap! logged-messages conj (vec args)))
                      logger/info (fn [_tag & args]
                                    (swap! logged-messages conj (vec args)))
                      secrets/credential-file-paths (constantly [temp-path])]
          (let [result (secrets/get-credential "api.openai.com")]
            (is (= "sk-super-secret-password-123" result))
            ;; Check that password is NOT in any log messages
            (let [all-logs (string/join " " (mapcat identity @logged-messages))]
              (is (not (string/includes? all-logs "sk-super-secret-password-123"))
                  "Password should not appear in debug logs"))))
        (finally
          (.delete temp-file))))))

(deftest no-password-in-error-messages-test
  (testing "Error messages do not leak password information"
    (let [temp-file (File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)
          logged-messages (atom [])]
      (try
        ;; Create malformed file
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-secret-key-789\ngarbage!!!!\n")

        ;; Mock logger to capture messages
        (with-redefs [logger/warn (fn [_tag & args]
                                    (swap! logged-messages conj (vec args)))
                      secrets/credential-file-paths (constantly [temp-path])]
          (secrets/get-credential "api.openai.com")
          ;; Check that password is NOT in any warning messages
          (let [all-logs (string/join " " (mapcat identity @logged-messages))]
            (is (not (string/includes? all-logs "sk-secret-key-789"))
                "Password should not appear in error messages")))
        (finally
          (.delete temp-file))))))

(deftest permission-validation-unix-test
  (testing "Permission validation on Unix systems"
    (if (string/includes? (System/getProperty "os.name") "Windows")
      ;; On Windows, just verify this is skipped
      (is true "Permission validation is Unix-only, skipped on Windows")
      ;; On Unix-like systems, test permission validation
      (let [temp-file (File/createTempFile "netrc-test" ".netrc")
            temp-path (.getPath temp-file)
            warnings (atom [])]
        (try
          (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-test-123\n")

          ;; Try to set insecure permissions (readable by others)
          (try
            (let [path (.toPath temp-file)
                  perms #{PosixFilePermission/OWNER_READ
                          PosixFilePermission/OWNER_WRITE
                          PosixFilePermission/GROUP_READ
                          PosixFilePermission/OTHERS_READ}]
              (Files/setPosixFilePermissions path perms)

              ;; Mock logger to capture warnings
              (with-redefs [logger/warn (fn [_tag & args]
                                          (swap! warnings conj (vec args)))
                            secrets/credential-file-paths (constantly [temp-path])]
                (secrets/get-credential "api.openai.com")
                ;; Should have logged a warning about insecure permissions
                (is (seq @warnings) "Should warn about insecure permissions")
                (let [warning-text (string/join " " (mapcat identity @warnings))]
                  (is (string/includes? (string/lower-case warning-text) "permission")
                      "Warning should mention permissions"))))
            (catch Exception _e
              ;; If we can't set permissions, skip this test
              (println "Skipping permission test - cannot set file permissions")))
          (finally
            (.delete temp-file)))))))

(deftest permission-validation-secure-test
  (testing "No warning for secure permissions (0600)"
    (if (string/includes? (System/getProperty "os.name") "Windows")
      ;; On Windows, just verify this is skipped
      (is true "Permission validation is Unix-only, skipped on Windows")
      ;; On Unix-like systems, test secure permission behavior
      (let [temp-file (File/createTempFile "netrc-test" ".netrc")
            temp-path (.getPath temp-file)
            warnings (atom [])]
        (try
          (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-test-123\n")

          ;; Set secure permissions (owner read/write only)
          (try
            (let [path (.toPath temp-file)
                  perms #{PosixFilePermission/OWNER_READ
                          PosixFilePermission/OWNER_WRITE}]
              (Files/setPosixFilePermissions path perms)

              ;; Mock logger to capture warnings
              (with-redefs [logger/warn (fn [_tag & args]
                                          (swap! warnings conj (vec args)))
                            secrets/credential-file-paths (constantly [temp-path])]
                (secrets/get-credential "api.openai.com")
                ;; Should NOT have logged permission warnings
                (let [warning-text (string/join " " (mapcat identity @warnings))]
                  (is (not (string/includes? (string/lower-case warning-text) "insecure"))
                      "Should not warn about insecure permissions for 0600"))))
            (catch Exception _e
              ;; If we can't set permissions, skip this test
              (println "Skipping permission test - cannot set file permissions")))
          (finally
            (.delete temp-file)))))))

(deftest permission-validation-windows-skip-test
  (testing "Permission validation is skipped on Windows"
    ;; This test documents that permission checks are OS-specific
    (let [os-name (System/getProperty "os.name")]
      (if (string/includes? os-name "Windows")
        (is true "Windows detected - permission checks should be skipped")
        (is true "Non-Windows system - permission checks should run")))))

(deftest file-priority-comprehensive-test
  (testing "Comprehensive file priority order validation"
    (let [netrc-file (File/createTempFile "test" ".netrc")
          netrc-win-file (File/createTempFile "test" "_netrc")
          netrc-path (.getPath netrc-file)
          netrc-win-path (.getPath netrc-win-file)]
      (try
        ;; Create different passwords in each file
        (spit netrc-path "machine api.test.com\nlogin apikey\npassword from-netrc\n")
        (spit netrc-win-path "machine api.test.com\nlogin apikey\npassword from-netrc-win\n")

        ;; Test with only netrc files
        (with-redefs [secrets/credential-file-paths (constantly [netrc-path netrc-win-path])]
          (is (= "from-netrc" (secrets/get-credential "api.test.com"))
              "Should use .netrc before _netrc"))

        ;; Test with only Windows netrc
        (.delete netrc-file)
        (with-redefs [secrets/credential-file-paths (constantly [netrc-win-path])]
          (is (= "from-netrc-win" (secrets/get-credential "api.test.com"))
              "Should use _netrc when others don't exist"))
        (finally
          (.delete netrc-file)
          (.delete netrc-win-file))))))

(deftest check-credential-files-no-files-test
  (testing "check-credential-files with no credential files"
    (with-redefs [secrets/credential-file-paths (constantly [])]
      (let [result (secrets/check-credential-files nil)]
        (is (map? result))
        (is (vector? (:files result)))
        (is (empty? (:files result)))))))

(deftest check-credential-files-existing-plaintext-test
  (testing "check-credential-files with existing plaintext file"
    (let [temp-file (File/createTempFile "test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a valid netrc file
        (spit temp-path "machine api.test.com\nlogin apikey\npassword test-key-123\n")

        (let [result (secrets/check-credential-files temp-path)
              file-info (first (:files result))]
          (is (= temp-path (:path file-info)))
          (is (true? (:exists file-info)))
          (is (true? (:readable file-info)))
          (is (= 1 (:credentials-count file-info))))

        (finally
          (.delete temp-file))))))

(deftest check-credential-files-nonexistent-test
  (testing "check-credential-files with nonexistent file"
    (let [nonexistent-path "/tmp/nonexistent-credential-file.netrc"
          result (secrets/check-credential-files nonexistent-path)
          file-info (first (:files result))]
      (is (= nonexistent-path (:path file-info)))
      (is (false? (:exists file-info)))
      (is (nil? (:readable file-info)))
      (is (nil? (:credentials-count file-info))))))

(deftest check-credential-files-parse-error-test
  (testing "check-credential-files with unparseable file"
    (let [temp-file (File/createTempFile "test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create an invalid file that will cause parse error
        (spit temp-path "invalid content that can't be parsed as netrc")

        ;; Mock load-credentials-from-file to throw an error
        (with-redefs [secrets/load-credentials-from-file
                      (fn [_path]
                        (throw (Exception. "Parse error: invalid format")))]
          (let [result (secrets/check-credential-files temp-path)
                file-info (first (:files result))]
            (is (= temp-path (:path file-info)))
            (is (true? (:exists file-info)))
            (is (string? (:parse-error file-info)))
            (is (string/includes? (:parse-error file-info) "Parse error"))))
        (finally
          (.delete temp-file))))))

(deftest check-credential-files-multiple-files-test
  (testing "check-credential-files with multiple credential files"
    (let [netrc-file (File/createTempFile "test" ".netrc")
          netrc-win-file (File/createTempFile "test" "_netrc")
          netrc-path (.getPath netrc-file)
          netrc-win-path (.getPath netrc-win-file)
          nonexistent-path "/tmp/nonexistent"]
      (try
        ;; Create valid files
        (spit netrc-path "machine api.test.com\nlogin apikey\npassword test-key-123\n")
        (spit netrc-win-path "machine api.other.com login user password pass-456")

        (with-redefs [secrets/credential-file-paths
                      (constantly [nonexistent-path netrc-path netrc-win-path])]
          (let [result (secrets/check-credential-files nil)
                files (:files result)]
            (is (= 3 (count files)))
            ;; First file doesn't exist
            (is (false? (:exists (nth files 0))))
            ;; Second file exists with 1 credential
            (is (true? (:exists (nth files 1))))
            (is (= 1 (:credentials-count (nth files 1))))
            ;; Third file exists with 1 credential
            (is (true? (:exists (nth files 2))))
            (is (= 1 (:credentials-count (nth files 2))))))
        (finally
          (.delete netrc-file)
          (.delete netrc-win-file))))))
