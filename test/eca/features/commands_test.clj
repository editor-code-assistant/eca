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

(deftest substitute-args-test
  (testing "replaces $ARGS with all args joined"
    (is (= "Review https://github.com/org/repo/pull/1"
           (#'f.commands/substitute-args "Review $ARGS" ["https://github.com/org/repo/pull/1"]))))

  (testing "replaces $ARGUMENTS with all args joined"
    (is (= "Review https://github.com/org/repo/pull/1"
           (#'f.commands/substitute-args "Review $ARGUMENTS" ["https://github.com/org/repo/pull/1"]))))

  (testing "replaces positional $ARGn placeholders"
    (is (= "First:a Second:b"
           (#'f.commands/substitute-args "First:$ARG1 Second:$ARG2" ["a" "b"]))))

  (testing "unmatched positional placeholders remain"
    (is (= "A:x B:$ARG2"
           (#'f.commands/substitute-args "A:$ARG1 B:$ARG2" ["x"]))))

  (testing "returns content as-is when no placeholders"
    (is (= "No placeholders here"
           (#'f.commands/substitute-args "No placeholders here" ["ignored"]))))

  (testing "works with empty args"
    (is (= "Hello  world"
           (#'f.commands/substitute-args "Hello $ARGS world" []))))

  (testing "replaces Claude Code compatible $n positional placeholders"
    (is (= "First:a Second:b"
           (#'f.commands/substitute-args "First:$1 Second:$2" ["a" "b"]))))

  (testing "replaces both $ARGn and $n placeholders"
    (is (= "A:x B:x"
           (#'f.commands/substitute-args "A:$ARG1 B:$1" ["x"])))))
