(ns eca.features.tools.git-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.git :as f.tools.git]
   [eca.features.tools.shell :as f.tools.shell]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(def ^:private call-state-fn (constantly {:status :executing}))
(def ^:private state-transition-fn (constantly nil))

(defn ^:private run-git! [arguments ctx]
  ((get-in f.tools.git/definitions ["git" :handler])
   arguments
   (merge {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}
           :call-state-fn call-state-fn
           :state-transition-fn state-transition-fn}
          ctx)))

(deftest git-command-validation-test
  (testing "rejects commands not starting with git or gh"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text #"command must start with 'git' or 'gh'"}]}
         (run-git! {"command" "ls -lh"} {})))))

(deftest git-command-shell-config-test
  (testing "no custom shell by default"
    (let [captured* (atom nil)]
      (with-redefs [f.tools.shell/start-shell-process! (fn [opts]
                                                         (reset! captured* opts)
                                                         (future {:exit 0 :out "ok"}))]
        (run-git! {"command" "git status"} {:chat-id "chat-123"})
        (is (match? {:script "git status"
                     :chat-id "chat-123"} @captured*))
        (is (nil? (:shell-path @captured*)))
        (is (nil? (:shell-args @captured*))))))
  (testing "honors toolCall shellCommand path/args config"
    (let [captured* (atom nil)]
      (with-redefs [f.tools.shell/start-shell-process! (fn [opts]
                                                         (reset! captured* opts)
                                                         (future {:exit 0 :out "ok"}))]
        (run-git! {"command" "git status"}
                  {:config {:toolCall {:shellCommand {:path "/custom/bash" :args ["-l" "-c"]}}}})
        (is (match? {:script "git status"
                     :shell-path "/custom/bash"
                     :shell-args ["-l" "-c"]}
                    @captured*))))))

(deftest git-command-heredoc-hint-test
  (testing "hints stdin heredoc when shell fails to parse a heredoc inside $()"
    (is (match?
         {:error true
          :contents [{:type :text :text "Exit code: 2"}
                     {:type :text :text #"unexpected EOF while looking for matching"}
                     {:type :text :text #"(?s)^Hint: this shell cannot parse heredocs inside \$\(\.\.\.\).*git commit -F - <<'EOF'"}]}
         (with-redefs [f.tools.shell/start-shell-process!
                       (constantly (future {:exit 2 :err "bash: -c: line 1: unexpected EOF while looking for matching `''"}))]
           (run-git! {"command" "git commit -m \"$(cat <<EOF\nit's\nEOF\n)\""} {})))))
  (testing "no hint when the command has no heredoc inside $()"
    (is (match?
         {:error true
          :contents [{:type :text :text "Exit code: 2"}
                     {:type :text :text #"unexpected EOF while looking for matching"}]}
         (with-redefs [f.tools.shell/start-shell-process!
                       (constantly (future {:exit 2 :err "bash: -c: line 1: unexpected EOF while looking for matching `''"}))]
           (run-git! {"command" "git commit -m 'don"} {})))))
  (testing "no hint on unrelated errors"
    (is (match?
         {:error true
          :contents [{:type :text :text "Exit code: 128"}
                     {:type :text :text #"not a git repository"}]}
         (with-redefs [f.tools.shell/start-shell-process!
                       (constantly (future {:exit 128 :err "fatal: not a git repository"}))]
           (run-git! {"command" "git commit -m \"$(cat <<EOF\nhi\nEOF\n)\""} {}))))))
