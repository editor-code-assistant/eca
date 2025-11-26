(ns eca.features.tools.custom-test
  (:require
   [babashka.process :as p]
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.custom :as f.tools.custom]))

(deftest definitions-test
  (testing "when a valid tool is configured"
    (let [mock-custom-tools {"file-search"
                             {:description "Finds files."
                              :command     "find {{directory}} -name {{pattern}}"
                              :schema      {:properties {"directory" {:type "string"}
                                                         "pattern"   {:type "string"}}
                                            :required    ["directory" "pattern"]}}}]
      (testing "and the command executes successfully"
        (with-redefs [p/shell (fn [_opts _bash command]
                                (is (= "find /tmp -name *.clj" command))
                                {:out "mocked-output" :exit 0})]
          (let [config {:customTools mock-custom-tools}
                custom-defs (f.tools.custom/definitions config)
                custom-tool-def (get custom-defs "file-search")]
            (is (some? custom-tool-def) "The custom tool should be loaded.")
            (let [result ((:handler custom-tool-def) {"directory" "/tmp" "pattern" "*.clj"} {})]
              (is (= {:contents [{:text "Stdout:\nmocked-output", :type :text}], :error false} result) "The tool should return the mocked shell output.")))))))

  (testing "when multiple tools are configured"
    (let [mock-custom-tools {"git-status"
                             {:description "Gets git status"
                              :command "git status"}
                             "echo-message"
                             {:description "Echoes a message"
                              :command "echo {{message}}"
                              :schema {:properties {"message" {:type "string"}} :required ["message"]}}}]
      (with-redefs [p/shell (fn [_opts _bash command]
                              (condp = command
                                "git status" {:out "On branch main" :exit 0}
                                "echo Hello World" {:out "Hello World" :exit 0}
                                (is false "Unexpected command received by mock p/sh")))]
        (let [config {:customTools mock-custom-tools}
              custom-defs (f.tools.custom/definitions config)
              git-status-handler (get-in custom-defs ["git-status" :handler])
              echo-handler (get-in custom-defs ["echo-message" :handler])]
          (is (some? git-status-handler) "Git status tool should be loaded.")
          (is (some? echo-handler) "Echo message tool should be loaded.")
          (is (= {:contents [{:text "Stdout:\nOn branch main", :type :text}], :error false} (git-status-handler {} {})))
          (is (= {:contents [{:text "Stdout:\nHello World", :type :text}], :error false} (echo-handler {"message" "Hello World"} {})))))))

  (testing "when the custom tools config is empty or missing"
    (testing "with an empty map"
      (let [config {:customTools {}}
            custom-defs (f.tools.custom/definitions config)]
        (is (empty? custom-defs) "No custom tools should be loaded.")))
    (testing "with the key missing from the config"
      (let [config {}
            custom-defs (f.tools.custom/definitions config)]
        (is (empty? custom-defs) "No custom tools should be loaded.")))))
