(ns eca.features.tools.shell-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.test :refer [are deftest is testing]]
   [eca.features.tools.shell :as f.tools.shell]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

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
