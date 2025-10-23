(ns eca.features.commands-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.commands :as f.commands]))

(deftest get-custom-command-tests
  (testing "returns nil when command not found"
    (is (nil? (#'f.commands/get-custom-command "nope" [] []))))

  (testing "$ARGS is replaced with the joined args"
    (let [custom [{:name "greet" :content "Hello $ARGS!"}]]
      (is (= "Hello Alice Bob!"
             (#'f.commands/get-custom-command "greet" ["Alice" "Bob"] custom)))))

  (testing "numbered $ARGn placeholders are replaced and $ARGS contains all args"
    (let [custom [{:name "pair" :content "First:$ARG1 Second:$ARG2 All:$ARGS"}]]
      (is (= "First:one Second:two All:one two"
             (#'f.commands/get-custom-command "pair" ["one" "two"] custom)))))

  (testing "unmatched placeholders remain when args are missing"
    (let [custom [{:name "partial" :content "A:$ARG1 B:$ARG2 C:$ARG3"}]]
      (is (= "A:only B: C:$ARG3"
             (#'f.commands/get-custom-command "partial" ["only" ""] custom)))))

  (testing "multiple occurrences of the same placeholder are all replaced"
    (let [custom [{:name "dup" :content "$ARG1-$ARG1 $ARGS"}]]
      (is (= "x-x x y"
             (#'f.commands/get-custom-command "dup" ["x" "y"] custom)))))

  (testing "$ARGUMENTS is supported as alias for $ARGS"
    (let [custom [{:name "test" :content "Process $ARGUMENTS here"}]]
      (is (= "Process one two here"
             (#'f.commands/get-custom-command "test" ["one" "two"] custom))))))
