(ns eca.main-test
  (:require
   [clojure.test :refer [are deftest is testing use-fixtures]]
   [eca.main :as main]
   [matcher-combinators.config]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(use-fixtures :once #(binding [matcher-combinators.config/*use-abbreviation* true] (%)))

(deftest parse-opts-test
  (are [args expected] (match? expected (#'main/parse-opts args))
    ;; help
    [] {:options {:help m/absent}}
    ["--help"] {:options {:help true}}
    ["-h"] {:options {:help true}}
    ;; version
    [] {:options {:version m/absent}}
    ["--version"] {:options {:version true}}
    ["-v"] {:options {:version m/absent}}
    ;; verbose
    [] {:options {:verbose m/absent}}
    ["--verbose"] {:options {:verbose true}}
    ["-v"] {:options {:verbose m/absent}}
    ;; config-file
    [] {:options {:config-file m/absent}}
    ["--config-file"] {:options {:config-file m/absent}}
    ["--config-file" "/dev/config.json"] {:options {:config-file "/dev/config.json"}}
    #_()))

(deftest parse
  (testing "commands"
    (is (= nil (:action (#'main/parse []))))
    (is (= "server" (:action (#'main/parse ["server"])))))
  (testing "final options"
    (is (string? (:exit-message (#'main/parse ["--help"]))))
    (is (string? (:exit-message (#'main/parse ["-h"]))))
    (is (string? (:exit-message (#'main/parse ["--version"])))))
  (testing "options + commands"
    (is (match?
         {:action "server"
          :options {:log-level "debug"}}
         (#'main/parse ["--log-level" "debug" "server"]))))
  (testing "commands + options"
    (is (match?
         {:action "server"
          :options {:log-level "debug"}}
         (#'main/parse ["server" "--log-level" "debug"])))))
