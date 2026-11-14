(ns eca.modules.custom-commands-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.modules.custom-commands :as custom-commands]))

(deftest config-commands-plugin-prefix-test
  (let [tmp-dir (fs/create-temp-dir)]
    (try
      (testing "plugin commands get prefixed with plugin name"
        (let [cmd-file (fs/file tmp-dir "deploy.md")]
          (spit cmd-file "Plugin command body")
          (let [config {:pureConfig true :commands [{:path (str cmd-file) :plugin "ui"}]}
                result (vec (custom-commands/all config []))]
            (is (= 1 (count result)))
            (is (= "ui:deploy" (:name (first result))))
            (is (= "ui" (:plugin (first result)))))))

      (testing "plugin command with same name as plugin drops the prefix"
        (let [cmd-file (fs/file tmp-dir "tdd.md")]
          (spit cmd-file "TDD body")
          (let [config {:pureConfig true :commands [{:path (str cmd-file) :plugin "tdd"}]}
                result (vec (custom-commands/all config []))]
            (is (= 1 (count result)))
            (is (= "tdd" (:name (first result))))
            (is (= "tdd" (:plugin (first result)))))))

      (testing "user-config commands without a plugin stay unprefixed"
        (let [cmd-file (fs/file tmp-dir "plain.md")]
          (spit cmd-file "Plain body")
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                result (vec (custom-commands/all config []))]
            (is (= 1 (count result)))
            (is (= "plain" (:name (first result))))
            (is (not (contains? (first result) :plugin))))))

      (testing "user-config command directories load markdown files recursively"
        (let [cmd-dir (fs/file tmp-dir "commands")
              nested-dir (fs/file cmd-dir "nested")]
          (fs/create-dirs nested-dir)
          (spit (fs/file cmd-dir "review.md") "Review body")
          (spit (fs/file nested-dir "ship.md") "Ship body")
          (spit (fs/file nested-dir "ignore.txt") "Ignored")
          (let [config {:pureConfig true :commands [{:path (str cmd-dir)}]}
                result (vec (custom-commands/all config []))]
            (is (= #{"review" "ship"} (set (map :name result))))
            (is (= #{"Review body" "Ship body"} (set (map :content result)))))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest command-arguments-test
  (let [tmp-dir (fs/create-temp-dir)]
    (try
      (testing "command with $ARG1 placeholder detects arguments"
        (let [cmd-file (fs/file tmp-dir "greet.md")]
          (spit cmd-file "Greet $ARG1!")
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                result (vec (custom-commands/all config []))]
            (is (= 1 (count result)))
            (is (= "greet" (:name (first result))))
            (is (= [{:name "arg1" :required true}]
                   (:arguments (first result)))))))

      (testing "command without placeholders has empty arguments"
        (let [cmd-file (fs/file tmp-dir "simple.md")]
          (spit cmd-file "Simple body")
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                result (vec (custom-commands/all config []))]
            (is (= [] (:arguments (first result)))))))

      (finally
        (fs/delete-tree tmp-dir)))))

(deftest command-frontmatter-test
  (let [tmp-dir (fs/create-temp-dir)]
    (try
      (testing "frontmatter description and named arguments are parsed"
        (let [cmd-file (fs/file tmp-dir "weather.md")]
          (spit cmd-file (string/join "\n"
                                      ["---"
                                       "description: Generate a weather report"
                                       "arguments:"
                                       "  - name: city"
                                       "    description: City to report on"
                                       "    required: true"
                                       "  - name: units"
                                       "    description: metric or imperial"
                                       "    required: false"
                                       "---"
                                       "Weather for {{city}} using {{units}} units."]))
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                result (first (custom-commands/all config []))]
            (is (= "weather" (:name result)))
            (is (= "Generate a weather report" (:description result)))
            (is (= "Weather for {{city}} using {{units}} units." (:content result)))
            (is (= [{:name "city" :description "City to report on" :required true}
                    {:name "units" :description "metric or imperial" :required false}]
                   (:arguments result))))))

      (testing "frontmatter arguments enrich positional args by index"
        (let [cmd-file (fs/file tmp-dir "pos.md")]
          (spit cmd-file (string/join "\n"
                                      ["---"
                                       "arguments:"
                                       "  - name: city"
                                       "    description: City name"
                                       "---"
                                       "Weather for $1"]))
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                result (first (custom-commands/all config []))]
            (is (= [{:name "city" :description "City name" :required true}]
                   (:arguments result))))))

      (testing "command without frontmatter still loads with positional args"
        (let [cmd-file (fs/file tmp-dir "plain.md")]
          (spit cmd-file "Hello $ARG1")
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}
                result (first (custom-commands/all config []))]
            (is (nil? (:description result)))
            (is (= "Hello $ARG1" (:content result)))
            (is (= [{:name "arg1" :required true}] (:arguments result))))))

      (testing "command mixing positional and named placeholders is skipped"
        (let [cmd-file (fs/file tmp-dir "bad.md")]
          (spit cmd-file "Use $1 and {{city}}")
          (let [config {:pureConfig true :commands [{:path (str cmd-file)}]}]
            (is (empty? (custom-commands/all config []))))))

      (finally
        (fs/delete-tree tmp-dir)))))
