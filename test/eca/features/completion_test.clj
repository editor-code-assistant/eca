(ns eca.features.completion-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.completion :as f.completion]
   [eca.features.login :as f.login]
   [eca.features.prompt :as f.prompt]
   [eca.llm-api :as llm-api]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn ^:private with-stubs [output-text body-fn]
  (with-redefs [llm-api/sync-prompt! (fn [_] {:output-text output-text})
                f.login/maybe-renew-auth-token! (fn [& _] nil)
                f.prompt/inline-completion-prompt (constantly "INSTR")
                f.prompt/inline-completion-region-replace-prompt (constantly "INSTR-RR")]
    (body-fn)))

(defn ^:private set-region-replace-capability! [enabled?]
  (swap! (h/db*) assoc-in
         [:client-capabilities :code-assistant :completion-capabilities :region-replace]
         enabled?))

(deftest complete-legacy-mode-test
  (testing "without regionReplace capability, returns a zero-width range at the cursor (legacy)"
    (h/reset-components!)
    (h/config! {:completion {:model "openai/gpt-4.1"}})
    (let [resp (with-stubs "more text"
                 (fn []
                   (f.completion/complete
                    {:doc-text "first line\nsecond"
                     :doc-version 7
                     :position {:line 2 :character 7}}
                    (h/db*) (h/config) (h/messenger) (h/metrics))))]
      (is (match? {:items [{:text "more text"
                            :doc-version 7
                            :range {:start {:line 2 :character 7}
                                    :end {:line 2 :character 7}}}]}
                  resp)))))

(deftest complete-region-replace-no-change-test
  (testing "with regionReplace capability, identical rewritten window yields no suggestions"
    (h/reset-components!)
    (h/config! {:completion {:model "openai/gpt-4.1" :windowRadius 6}})
    (set-region-replace-capability! true)
    ;; Model echoes the original window unchanged.
    (let [resp (with-stubs "alpha\nbeta\ngamma"
                 (fn []
                   (f.completion/complete
                    {:doc-text "alpha\nbeta\ngamma"
                     :doc-version 1
                     :position {:line 2 :character 5}}
                    (h/db*) (h/config) (h/messenger) (h/metrics))))]
      (is (match? {:error {:type :info}} resp)))))

(deftest complete-region-replace-before-cursor-test
  (testing "with regionReplace capability, edits before the cursor produce a precise replacement range"
    (h/reset-components!)
    (h/config! {:completion {:model "openai/gpt-4.1" :windowRadius 6}})
    (set-region-replace-capability! true)
    ;; Doc:    "thersholdd"  (cursor right after the trailing 'd', column 11)
    ;; Model rewrites the window dropping the typo trailing 'd':
    (let [resp (with-stubs "thershold"
                 (fn []
                   (f.completion/complete
                    {:doc-text "thersholdd"
                     :doc-version 3
                     :position {:line 1 :character 11}}
                    (h/db*) (h/config) (h/messenger) (h/metrics))))]
      (is (match? {:items [{:text ""
                            :doc-version 3
                            :range {:start {:line 1 :character 10}
                                    :end {:line 1 :character 11}}}]}
                  resp)))))

(deftest complete-region-replace-multi-line-test
  (testing "with regionReplace capability, multi-line rewrites are returned as a single replacement"
    (h/reset-components!)
    (h/config! {:completion {:model "openai/gpt-4.1" :windowRadius 6}})
    (set-region-replace-capability! true)
    (let [doc "line1\ntodo\nline3"
          new-window "line1\nDONE\nline3"
          resp (with-stubs new-window
                 (fn []
                   (f.completion/complete
                    {:doc-text doc
                     :doc-version 9
                     :position {:line 2 :character 1}}
                    (h/db*) (h/config) (h/messenger) (h/metrics))))]
      (is (match? {:items [{:text "DONE"
                            :doc-version 9
                            :range {:start {:line 2 :character 1}
                                    :end {:line 2 :character 5}}}]}
                  resp)))))

(deftest complete-region-replace-strips-fences-and-markers-test
  (testing "the rewritten window is sanitized: code fences and stray markers are stripped"
    (h/reset-components!)
    (h/config! {:completion {:model "openai/gpt-4.1" :windowRadius 6}})
    (set-region-replace-capability! true)
    (let [resp (with-stubs "```\n<ECA_WINDOW_START>\nhello WORLD<ECA_CURSOR>\n<ECA_WINDOW_END>\n```"
                 (fn []
                   (f.completion/complete
                    {:doc-text "hello world"
                     :doc-version 1
                     :position {:line 1 :character 12}}
                    (h/db*) (h/config) (h/messenger) (h/metrics))))]
      (is (match? {:items [{:text "WORLD"
                            :range {:start {:line 1 :character 7}
                                    :end {:line 1 :character 12}}}]}
                  resp)))))

(deftest complete-prompt-selection-test
  (testing "the legacy path uses inline-completion-prompt"
    (h/reset-components!)
    (h/config! {:completion {:model "openai/gpt-4.1"}})
    (let [captured* (atom nil)]
      (with-redefs [llm-api/sync-prompt! (fn [opts]
                                           (reset! captured* opts)
                                           {:output-text "x"})
                    f.login/maybe-renew-auth-token! (fn [& _] nil)
                    f.prompt/inline-completion-prompt (constantly "LEGACY-INSTR")
                    f.prompt/inline-completion-region-replace-prompt (constantly "RR-INSTR")]
        (f.completion/complete
         {:doc-text "hi"
          :doc-version 1
          :position {:line 1 :character 1}}
         (h/db*) (h/config) (h/messenger) (h/metrics)))
      (is (= "LEGACY-INSTR" (:instructions @captured*)))))

  (testing "the region-replace path uses inline-completion-region-replace-prompt"
    (h/reset-components!)
    (h/config! {:completion {:model "openai/gpt-4.1" :windowRadius 6}})
    (set-region-replace-capability! true)
    (let [captured* (atom nil)]
      (with-redefs [llm-api/sync-prompt! (fn [opts]
                                           (reset! captured* opts)
                                           {:output-text "hi"})
                    f.login/maybe-renew-auth-token! (fn [& _] nil)
                    f.prompt/inline-completion-prompt (constantly "LEGACY-INSTR")
                    f.prompt/inline-completion-region-replace-prompt (constantly "RR-INSTR")]
        (f.completion/complete
         {:doc-text "hi"
          :doc-version 1
          :position {:line 1 :character 1}}
         (h/db*) (h/config) (h/messenger) (h/metrics)))
      (is (= "RR-INSTR" (:instructions @captured*))))))

(deftest complete-error-paths-test
  (testing "LLM message error becomes a warning"
    (h/reset-components!)
    (h/config! {:completion {:model "openai/gpt-4.1"}})
    (let [resp (with-redefs [llm-api/sync-prompt! (fn [_] {:error {:message "boom"}})
                             f.login/maybe-renew-auth-token! (fn [& _] nil)
                             f.prompt/inline-completion-prompt (constantly "INSTR")
                             f.prompt/inline-completion-region-replace-prompt (constantly "INSTR-RR")]
                 (f.completion/complete
                  {:doc-text "abc"
                   :doc-version 1
                   :position {:line 1 :character 1}}
                  (h/db*) (h/config) (h/messenger) (h/metrics)))]
      (is (match? {:error {:type :warning :message "boom"}} resp))))

  (testing "no output yields an info-level no-suggestions error"
    (h/reset-components!)
    (h/config! {:completion {:model "openai/gpt-4.1"}})
    (let [resp (with-stubs nil
                 (fn []
                   (f.completion/complete
                    {:doc-text "abc"
                     :doc-version 1
                     :position {:line 1 :character 1}}
                    (h/db*) (h/config) (h/messenger) (h/metrics))))]
      (is (match? {:error {:type :info}} resp)))))
