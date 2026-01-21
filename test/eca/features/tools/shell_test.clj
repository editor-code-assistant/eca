(ns eca.features.tools.shell-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.test :refer [are deftest is testing]]
   [clojure.string :as string]
   [eca.features.tools.shell :as f.tools.shell]
   [eca.features.tools.util :as tools.util]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]
   [clojure.java.io :as io]))

(def ^:private call-state-fn (constantly {:status :executing}))
(def ^:private state-transition-fn (constantly nil))

(deftest shell-command-test
  (testing "non-existent working_directory"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text (format "working directory %s does not exist" (h/file-path "/baz"))}]}
         (with-redefs [fs/exists? (constantly false)]
           ((get-in f.tools.shell/definitions ["shell_command" :handler])
            {"command" "ls -lh"
             "working_directory" (h/file-path "/baz")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
             :call-state-fn call-state-fn
             :state-transition-fn state-transition-fn})))))
  (testing "command exited with non-zero exit code"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text "Exit code: 1"}
                     {:type :text
                      :text "Stderr:\nSome error"}]}
         (with-redefs [fs/exists? (constantly true)
                       p/process (constantly (future {:exit 1 :err "Some error"}))]
           ((get-in f.tools.shell/definitions ["shell_command" :handler])
            {"command" "ls -lh"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
             :call-state-fn call-state-fn
             :state-transition-fn state-transition-fn})))))
  (testing "command succeeds"
    (is (match?
         {:error false
          :contents [{:type :text
                      :text "Exit code: 0"}
                     {:type :text
                      :text "Stderr:\nOther text"}
                     {:type :text
                      :text "Stdout:\nSome text"}]}
         (with-redefs [fs/exists? (constantly true)
                       p/process (constantly (future {:exit 0 :out "Some text" :err "Other text"}))]
           ((get-in f.tools.shell/definitions ["shell_command" :handler])
            {"command" "ls -lh"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
             :call-state-fn call-state-fn
             :state-transition-fn state-transition-fn})))))
  (testing "command succeeds with different working directory"
    (is (match?
         {:error false
          :contents [{:type :text
                      :text "Exit code: 0"}
                     {:type :text
                      :text "Stdout:\nSome text"}]}
         (with-redefs [fs/exists? (constantly true)
                       p/process (constantly (future {:exit 0 :out "Some text"}))]
           ((get-in f.tools.shell/definitions ["shell_command" :handler])
            {"command" "ls -lh"
             "working_directory" (h/file-path "/project/foo/src")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
             :call-state-fn call-state-fn
             :state-transition-fn state-transition-fn})))))
  (testing "command exceeds timeout"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text "Command timed out after 50 ms"}]}
         (with-redefs [fs/exists? (constantly true)
                       p/process (constantly (future (Thread/sleep 1000) {:exit 0 :err "ok"}))]
           ((get-in f.tools.shell/definitions ["shell_command" :handler])
            {"command" "ls -lh"
             "timeout" 50}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
             :call-state-fn call-state-fn
             :state-transition-fn state-transition-fn}))))))

(deftest shell-stores-process-test
  (testing "Shell command stores the process as a resource in the tool call state"
    (let [call-state (atom nil)
          proc (atom nil)
          state-transition-fn (fn [event event-data]
                                (when-not (= :resources-destroyed event)
                                  (reset! call-state event-data)))]
      (is (match?
           {:error false
            :contents [{:type :text
                        :text "Exit code: 0"}
                       {:type :text
                        :text "Stdout:\nalso ok"}]}
           (with-redefs [fs/exists? (constantly true)
                         p/process (constantly (reset! proc (future {:exit 0 :out "also ok"})))]
             ((get-in f.tools.shell/definitions ["shell_command" :handler])
              {"command" "ls -lh"}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
               :call-state-fn call-state-fn
               :state-transition-fn state-transition-fn})))
          "Expected the shell command to return the expected values")
      (is (= {:resources {:process @proc}}
             @call-state)
          "Expected the resource in the call state to match the process"))))

(deftest shell-require-approval-fn-test
  (let [approval-fn (get-in f.tools.shell/definitions ["shell_command" :require-approval-fn])
        db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}]
    (testing "returns nil when working_directory is not provided"
      (is (nil? (approval-fn nil {:db db})))
      (is (nil? (approval-fn {} {:db db}))))
    (testing "returns nil when working_directory equals a workspace root"
      (is (nil? (approval-fn {"working_directory" (h/file-path "/project/foo")} {:db db}))))
    (testing "returns nil when working_directory is a subdirectory of a workspace root"
      (is (nil? (approval-fn {"working_directory" (h/file-path "/project/foo/src")} {:db db}))))
    (testing "returns true when working_directory is outside any workspace root"
      (is (true? (approval-fn {"working_directory" (h/file-path "/other/place")} {:db db}))))))

(deftest plan-mode-restrictions-test
  (testing "safe commands allowed in plan mode"
    (are [command] (match?
                    {:error false
                     :contents [{:type :text
                                 :text "Exit code: 0"}
                                {:type :text
                                 :text "Stdout:\nSome output"}]}
                    (with-redefs [fs/exists? (constantly true)
                                  p/process (constantly (future {:exit 0 :out "Some output"}))]
                      ((get-in f.tools.shell/definitions ["shell_command" :handler])
                       {"command" command}
                       {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
                        :behavior "plan"
                        :call-state-fn call-state-fn
                        :state-transition-fn state-transition-fn})))
      "git status"
      "ls -la"
      "find . -name '*.clj'"
      "grep 'test' file.txt"
      "cat file.txt"
      "head -10 file.txt"
      "pwd"
      "date"
      "env")))

(deftest large-output-test
  (testing "Small output (below threshold) is inlined directly"
    (let [small-output "This is a small output"]
      (is (match?
           {:error false
            :contents [{:type :text :text "Exit code: 0"}
                       {:type :text :text (str "Stdout:\n" small-output)}]}
           (with-redefs [fs/exists? (constantly true)
                         p/process (constantly (future {:exit 0 :out small-output}))]
             ((get-in f.tools.shell/definitions ["shell_command" :handler])
              {"command" "echo 'hello'"}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
               :config {:toolCall {:shellCommand {:outputFileThreshold 2000}}}
               :chat-id "test-chat-123"
               :tool-call-id "test-tool-456"
               :call-state-fn call-state-fn
               :state-transition-fn state-transition-fn}))))))

  (testing "Large output (above threshold) with zero exit is written to file"
    (let [large-output (apply str (repeat 3000 "x"))
          written-files (atom [])]
      (is (match?
           {:error false
            :contents [{:type :text
                        :text #"Shell command output saved to file due to size"}]}
           (with-redefs [fs/exists? (constantly true)
                         p/process (constantly (future {:exit 0 :out large-output}))
                         io/make-parents (constantly nil)
                         clojure.core/spit (fn [path content]
                                             (swap! written-files conj {:path (str path) :content content}))]
             ((get-in f.tools.shell/definitions ["shell_command" :handler])
              {"command" "find ."}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
               :config {:toolCall {:shellCommand {:outputFileThreshold 2000}}}
               :chat-id "test-chat-123"
               :tool-call-id "test-tool-456"
               :call-state-fn call-state-fn
               :state-transition-fn state-transition-fn}))))
      (is (= 1 (count @written-files)) "Should write exactly one file")
      (is (= large-output (:content (first @written-files))) "File content should match output")))

  (testing "Large output (above threshold) with non-zero exit is written to file"
    (let [large-output (apply str (repeat 3000 "x"))
          written-files (atom [])]
      (is (match?
           {:error true
            :contents [{:type :text
                        :text #"(?s)Command failed.*exit code 1.*Output saved.*Last 20 lines.*"}]}
           (with-redefs [fs/exists? (constantly true)
                         io/make-parents (constantly nil)
                         clojure.core/spit (fn [path content]
                                             (swap! written-files conj {:path (str path) :content content}))
                         p/process (constantly (future {:exit 1 :out large-output :err ""}))]
             ((get-in f.tools.shell/definitions ["shell_command" :handler])
              {"command" "false"}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
               :config {:toolCall {:shellCommand {:outputFileThreshold 2000}}}
               :chat-id "test-chat-123"
               :tool-call-id "test-tool-456"
               :call-state-fn call-state-fn
               :state-transition-fn state-transition-fn}))))
      (is (= 1 (count @written-files)) "Should write exactly one file")
      (is (= large-output (:content (first @written-files))) "File content should match output")))

  (testing "File write failure falls back to inline output with warning"
    (let [large-output (apply str (repeat 3000 "x"))]
      (is (match?
           {:error false
            :contents [{:type :text
                        :text #"Warning: Failed to write output to file"}]}
           (with-redefs [fs/exists? (constantly true)
                         p/process (constantly (future {:exit 0 :out large-output}))
                         io/make-parents (constantly nil)
                         clojure.core/spit (fn [_ _] (throw (Exception. "Write failed")))]
             ((get-in f.tools.shell/definitions ["shell_command" :handler])
              {"command" "find ."}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
               :config {:toolCall {:shellCommand {:outputFileThreshold 2000}}}
               :chat-id "test-chat-123"
               :tool-call-id "test-tool-456"
               :call-state-fn call-state-fn
               :state-transition-fn state-transition-fn})))))))

(deftest format-file-based-response-test
  (testing "Formats response with default tail of 20 lines"
    (let [output (string/join "\n" (map #(str "Line " %) (range 1 51)))
          file-path "/path/to/output.txt"
          response (#'f.tools.shell/format-file-based-response file-path output 0 1234)]
      (is (string/includes? response "Shell command output saved to file"))
      (is (string/includes? response file-path))
      (is (string/includes? response "1,234 characters"))
      (is (string/includes? response "Exit code: 0"))
      (is (string/includes? response "Last 20 lines:"))
      (is (string/includes? response "Line 50"))
      (is (not (string/includes? response "Line 20")))))

  (testing "Formats response with custom tail line count"
    (let [output (string/join "\n" (map #(str "Line " %) (range 1 51)))
          file-path "/path/to/output.txt"
          response (#'f.tools.shell/format-file-based-response file-path output 0 1234 5)]
      (is (string/includes? response "Last 5 lines:"))
      (is (string/includes? response "Line 50"))
      (is (not (string/includes? response "Line 40"))))))

(deftest workspaces-hash-test
  (testing "Generates consistent 8-char hash for same workspaces"
    (let [workspaces [{:uri "file:///path/to/workspace"}]
          hash1 (#'f.tools.shell/workspaces-hash workspaces)
          hash2 (#'f.tools.shell/workspaces-hash workspaces)]
      (is (= hash1 hash2))
      (is (= 8 (count hash1)))))

  (testing "Generates different hashes for different workspaces"
    (let [workspaces1 [{:uri "file:///path/to/workspace1"}]
          workspaces2 [{:uri "file:///path/to/workspace2"}]
          hash1 (#'f.tools.shell/workspaces-hash workspaces1)
          hash2 (#'f.tools.shell/workspaces-hash workspaces2)]
      (is (not= hash1 hash2)))))

(deftest shell-output-cache-dir-test
  (testing "Returns correct cache directory path"
    (let [workspaces [{:uri "file:///path/to/workspace"}]
          dir (#'f.tools.shell/shell-output-cache-dir workspaces)
          dir-str (str dir)
          ;; Normalize to forward slashes for cross-platform test
          normalized-str (string/replace dir-str "\\" "/")]
      (is (string/includes? normalized-str ".cache/eca"))
      (is (string/includes? normalized-str "shell-output")))))

(deftest eca-shell-output-cache-file-test
  (testing "Returns true for paths in the ECA shell output cache directory"
    (let [cache-path (str (System/getProperty "user.home") "/.cache/eca/abc123/shell-output/chat-1-tool-1.txt")]
      (with-redefs [fs/canonicalize (fn [p] (fs/path p))]
        (is (true? (tools.util/eca-shell-output-cache-file? cache-path))))))
  (testing "Returns false for paths outside ECA cache"
    (is (false? (tools.util/eca-shell-output-cache-file? "/some/other/path.txt")))
    (is (false? (tools.util/eca-shell-output-cache-file? "/home/user/project/file.clj"))))
  (testing "Returns false for paths in ECA cache but not shell-output"
    (let [cache-path (str (System/getProperty "user.home") "/.cache/eca/abc123/other/file.txt")]
      (with-redefs [fs/canonicalize (fn [p] (fs/path p))]
        (is (false? (tools.util/eca-shell-output-cache-file? cache-path))))))
  (testing "Returns falsy for nil path"
    (is (not (tools.util/eca-shell-output-cache-file? nil)))))
