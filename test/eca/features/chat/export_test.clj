(ns eca.features.chat.export-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat.export :as sut]))

(def ^:private sample-chat
  {:id "chat-1"
   :title "Fix the Login Bug!"
   :title-custom? true
   :status :idle
   :created-at 1
   :updated-at 2
   :login-provider "anthropic"
   :model "anthropic/claude-sonnet-4-6"
   :variant "high"
   :last-api :anthropic
   :trust true
   :prompt-id #uuid "00000000-0000-0000-0000-000000000001"
   :user-prompt-count 2
   :subagent false
   :usage {:total-input-tokens 100 :total-output-tokens 50}
   :messages [{:role "user" :content "hello there" :content-id "c1" :created-at 1}
              {:role "assistant" :content [{:type :text :text "general kenobi"}] :content-id "c2" :created-at 2}
              {:role "tool_call" :content {:id "t1" :name "read_file" :full-name "eca__read_file"
                                           :origin :native :arguments {:path "/tmp/x"} :api :anthropic} :content-id "c3" :created-at 3}]
   :task {:next-id 2 :active-summary nil
          :tasks [{:id 1 :subject "do" :description "d" :status :done :priority :high}]}
   ;; live/transient state that must NOT be exported:
   :tool-calls {"t1" {:status :completed :approved?* (promise) :future-cleanup-complete?* (promise) :future (future 1)}}
   :last-status-payload {:x 1}
   :prompt-cache {:provider :stuff}})

(def ^:private sample-db
  {:chats {"chat-1" sample-chat}})

(deftest build-export-test
  (let [export (sut/build-export sample-db "chat-1")
        chat (:chat export)]
    (testing "envelope carries version tags"
      (is (contains? export :eca/version))
      (is (contains? export :eca/export-version)))
    (testing "resumable fields preserved verbatim (no obfuscation)"
      (is (= "chat-1" (:id chat)))
      (is (= "Fix the Login Bug!" (:title chat)))
      (is (= "anthropic/claude-sonnet-4-6" (:model chat)))
      (is (= "high" (:variant chat)))
      (is (= :anthropic (:last-api chat)))
      (is (= true (:trust chat)))
      (is (= 2 (:user-prompt-count chat)))
      (is (= {:total-input-tokens 100 :total-output-tokens 50} (:usage chat)))
      (is (= "do" (get-in chat [:task :tasks 0 :subject]))))
    (testing "message content kept verbatim and in order"
      (is (= 3 (count (:messages chat))))
      (is (= "hello there" (:content (first (:messages chat)))))
      (is (= ["user" "assistant" "tool_call"] (mapv :role (:messages chat))))
      (is (= "/tmp/x" (get-in (nth (:messages chat) 2) [:content :arguments :path]))))
    (testing "live/transient state excluded"
      (is (not (contains? chat :tool-calls)))
      (is (not (contains? chat :last-status-payload)))
      (is (not (contains? chat :prompt-cache))))))

(deftest export->edn-round-trips-test
  (testing "the export serializes and reads back equal (incl. #uuid prompt-id)"
    (let [export (sut/build-export sample-db "chat-1")
          read-back (edn/read-string (sut/export->edn export))]
      (is (= export read-back))
      (is (= #uuid "00000000-0000-0000-0000-000000000001" (get-in read-back [:chat :prompt-id]))))))

(deftest export-import-round-trip-test
  (testing "export-chat! then import-chat! preserves the chat"
    (let [tmp (str (fs/path (fs/temp-dir) (str "eca-export-test-" (random-uuid) ".edn")))
          {:keys [path message-count error]} (sut/export-chat! {:db sample-db :chat-id "chat-1" :filepath tmp})]
      (is (nil? error))
      (is (= 3 message-count))
      (is (fs/exists? path))
      (let [{:keys [chat error]} (sut/import-chat! {:filepath path})]
        (is (nil? error))
        (is (= "chat-1" (:id chat)))
        (is (= "anthropic/claude-sonnet-4-6" (:model chat)))
        (is (= "high" (:variant chat)))
        (is (= {:total-input-tokens 100 :total-output-tokens 50} (:usage chat)))
        (is (= (get-in (sut/build-export sample-db "chat-1") [:chat :messages]) (:messages chat))))
      (fs/delete-if-exists path))))

(deftest export-chat!-defaults-and-errors-test
  (testing "blank filepath falls back to a slugified temp path"
    (let [{:keys [path error]} (sut/export-chat! {:db sample-db :chat-id "chat-1" :filepath ""})]
      (is (nil? error))
      (is (fs/exists? path))
      (is (string/includes? path "eca-chat-fix-the-login-bug"))
      (fs/delete-if-exists path)))
  (testing "missing chat returns an error"
    (is (:error (sut/export-chat! {:db sample-db :chat-id "nope" :filepath "/tmp/x.edn"})))))

(deftest export-chat!-directory-target-test
  (testing "an existing directory writes the default filename inside it"
    (let [dir (str (fs/path (fs/temp-dir) (str "eca-export-dir-" (random-uuid))))]
      (fs/create-dirs dir)
      (let [{:keys [path error]} (sut/export-chat! {:db sample-db :chat-id "chat-1" :filepath dir})]
        (is (nil? error))
        (is (= "eca-chat-fix-the-login-bug.edn" (fs/file-name path)))
        (is (string/starts-with? path dir))
        (is (fs/exists? path)))
      (fs/delete-tree dir)))
  (testing "a trailing-separator path creates the directory and writes inside it"
    (let [dir (str (fs/path (fs/temp-dir) (str "eca-export-newdir-" (random-uuid))))
          {:keys [path error]} (sut/export-chat! {:db sample-db :chat-id "chat-1" :filepath (str dir "/")})]
      (is (nil? error))
      (is (= "eca-chat-fix-the-login-bug.edn" (fs/file-name path)))
      (is (string/starts-with? path dir))
      (is (fs/exists? path))
      (fs/delete-tree dir))))

(deftest import-chat!-errors-test
  (testing "blank filepath errors"
    (is (:error (sut/import-chat! {:filepath ""}))))
  (testing "non-existent file errors"
    (is (:error (sut/import-chat! {:filepath (str (fs/path (fs/temp-dir) (str "nope-" (random-uuid) ".edn")))}))))
  (testing "content that is not an ECA export errors"
    (let [tmp (str (fs/path (fs/temp-dir) (str "eca-bad-" (random-uuid) ".edn")))]
      (spit tmp (pr-str {:not "an export"}))
      (is (:error (sut/import-chat! {:filepath tmp})))
      (fs/delete-if-exists tmp))))
