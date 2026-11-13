(ns eca.features.guide-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.guide :as guide]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(deftest every-docs-page-is-a-topic-test
  (testing "a new page of docs/config needs a guide topic"
    (is (= (set (map #(str (fs/strip-ext (fs/file-name %))) (fs/glob "docs/config" "*.md")))
           (set guide/doc-topics)))))

(deftest topic-test
  (testing "a docs page, without its front matter"
    (is (match? {:topic "modules"
                 :description #"ECA modules"
                 :text #"^# Modules\n"}
                (guide/topic "modules"))))
  (testing "the config schema"
    (is (match? {:properties {:modules map? :repl map?}}
                (json/parse-string (:text (guide/topic "config-schema")) true))))
  (testing "the eca.api reference, from the code"
    (let [text (:text (guide/topic "eca-api"))]
      (is (string/includes? text "## eca.api\n"))
      (is (string/includes? text "- `(config ctx)`: ECA's config, with defaults applied"))
      (is (string/includes? text "- `(notify! ctx text)`, `(notify! ctx text {:keys [level]})`: Shows `text`"))
      (doseq [ns-sym ['eca.api.json 'eca.api.http 'eca.api.fs 'eca.api.dev 'eca.api.debug 'eca.api.test]]
        (is (string/includes? text (str "## " ns-sym "\n"))))))
  (testing "no such topic"
    (is (nil? (guide/topic "nope")))))

(deftest index-test
  (is (match? (into [] (map (fn [t] {:topic t :description (m/pred #(not (string/blank? %)))})) guide/topics)
              (guide/index))))

(deftest api-docstrings-test
  (testing "every eca.api function has a docstring, for the reference"
    (doseq [ns-sym ['eca.api 'eca.api.json 'eca.api.http 'eca.api.fs 'eca.api.dev 'eca.api.debug 'eca.api.test]
            [sym v] (ns-publics ns-sym)]
      (is (not (string/blank? (:doc (meta v)))) (str ns-sym "/" sym " has no docstring")))))
