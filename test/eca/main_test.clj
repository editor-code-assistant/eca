(ns eca.main-test
  (:require
   [clojure.test :refer [are deftest is testing use-fixtures]]
   [eca.main :as main]
   [matcher-combinators.config]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(use-fixtures :once #(binding [matcher-combinators.config/*use-abbreviation* true] (%)))

(deftest parse-server-opts-test
  (are [args expected] (match? expected (#'main/parse-server-opts args))
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
    ["--config-file" "/dev/config.json"] {:options {:config-file "/dev/config.json"}}
    ;; positional args are handled by parse
    ["extra"] {:arguments ["extra"]}
    #_()))

(deftest parse
  (testing "commands"
    (is (= nil (:action (#'main/parse []))))
    (is (= "server" (:action (#'main/parse ["server"]))))
    (is (= "read-chat" (:action (#'main/parse ["read-chat" "--workspace" "/tmp/a"])))))
  (testing "final options"
    (is (string? (:exit-message (#'main/parse ["--help"]))))
    (is (string? (:exit-message (#'main/parse ["-h"]))))
    (is (string? (:exit-message (#'main/parse ["--version"])))))
  (testing "legacy options + server command"
    (is (match?
         {:action "server"
          :options {:log-level "debug"}}
         (#'main/parse ["--log-level" "debug" "server"]))))
  (testing "server command + options"
    (is (match?
         {:action "server"
          :options {:log-level "debug"}}
         (#'main/parse ["server" "--log-level" "debug"]))))
  (testing "option values that match commands are not treated as commands"
    (is (nil? (:action (#'main/parse ["--config-file" "server"]))))))

(deftest parse-read-chat-command-position-test
  (testing "read-chat supports options after the command"
    (is (match?
         {:action "read-chat"
          :options {:workspace ["/tmp/a"]
                    :chat-id "chat-1"}}
         (#'main/parse ["read-chat" "--workspace" "/tmp/a" "--chat-id" "chat-1"]))))
  (testing "read-chat requires command-first invocation"
    (is (nil? (:action (#'main/parse ["--workspace" "/tmp/a" "read-chat" "--chat-id" "chat-1"])))))
  (testing "server keeps previous single positional command behavior"
    (is (string? (:exit-message (#'main/parse ["server" "extra"]))))))
