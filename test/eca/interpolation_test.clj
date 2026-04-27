(ns eca.interpolation-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [eca.interpolation :as interpolation]
   [eca.logger :as logger]))

;; Reset the shell-PATH cache before every test so cache state never leaks
;; between tests. Tests that exercise the shell query path mock run-process!
;; (which short-circuits the query to nil via missing-delimiter parse failure)
;; or stub user-shell-path / query-user-shell-path directly.
(use-fixtures :each
  (fn [t]
    (interpolation/reset-shell-path-cache!)
    (t)))

(deftest augment-path-test
  (testing "non-mac OS: existing PATH returned unchanged"
    (is (= "/usr/bin:/bin"
           (interpolation/augment-path "Linux" "/usr/bin:/bin" "/home/u" ":")))
    (is (= "C:\\Windows;C:\\Program Files"
           (interpolation/augment-path "Windows 10" "C:\\Windows;C:\\Program Files" "C:\\Users\\u" ";"))))

  (testing "nil os-name: existing PATH returned unchanged"
    (is (= "/usr/bin"
           (interpolation/augment-path nil "/usr/bin" "/home/u" ":"))))

  (testing "mac, all extras already present: PATH unchanged"
    (let [existing "/opt/homebrew/bin:/usr/local/bin:/home/u/.local/bin:/usr/bin"]
      (is (= existing
             (interpolation/augment-path "Mac OS X" existing "/home/u" ":")))))

  (testing "mac, no extras present: all extras prepended in order"
    (is (= "/opt/homebrew/bin:/usr/local/bin:/home/u/.local/bin:/usr/bin:/bin"
           (interpolation/augment-path "Mac OS X" "/usr/bin:/bin" "/home/u" ":"))))

  (testing "mac, some extras present: only missing entries prepended"
    (is (= "/opt/homebrew/bin:/home/u/.local/bin:/usr/local/bin:/usr/bin"
           (interpolation/augment-path "Mac OS X" "/usr/local/bin:/usr/bin" "/home/u" ":"))))

  (testing "mac, blank existing PATH: result is just the joined extras"
    (is (= "/opt/homebrew/bin:/usr/local/bin:/home/u/.local/bin"
           (interpolation/augment-path "Mac OS X" "" "/home/u" ":"))))

  (testing "mac, nil existing PATH: treated as blank"
    (is (= "/opt/homebrew/bin:/usr/local/bin:/home/u/.local/bin"
           (interpolation/augment-path "Mac OS X" nil "/home/u" ":"))))

  (testing "mac, nil home: only homebrew/usr-local entries considered"
    (is (= "/opt/homebrew/bin:/usr/local/bin:/usr/bin"
           (interpolation/augment-path "Mac OS X" "/usr/bin" nil ":")))))

(deftest supported-query-shell-test
  (testing "POSIX shells are supported"
    (is (interpolation/supported-query-shell? "/bin/bash"))
    (is (interpolation/supported-query-shell? "/usr/bin/zsh"))
    (is (interpolation/supported-query-shell? "/bin/sh"))
    (is (interpolation/supported-query-shell? "/usr/local/bin/dash"))
    (is (interpolation/supported-query-shell? "/bin/ksh"))
    (is (interpolation/supported-query-shell? "ZSH")))

  (testing "fish and other non-POSIX shells are not supported"
    (is (not (interpolation/supported-query-shell? "/usr/local/bin/fish")))
    (is (not (interpolation/supported-query-shell? "/usr/bin/nu")))
    (is (not (interpolation/supported-query-shell? "/usr/bin/elvish"))))

  (testing "nil/blank shell is not supported"
    (is (not (interpolation/supported-query-shell? nil)))
    (is (not (interpolation/supported-query-shell? "")))))

(deftest query-user-shell-path*-test
  (testing "extracts the PATH between delimiters, even with surrounding noise"
    (with-redefs [interpolation/run-process!
                  (fn [_cmd _env _timeout]
                    {:exit 0
                     :out "some greeting\n__ECA_PATH_DELIM__/Users/u/.bin:/opt/homebrew/bin:/usr/bin__ECA_PATH_DELIM__"
                     :err ""})]
      (is (= "/Users/u/.bin:/opt/homebrew/bin:/usr/bin"
             (interpolation/query-user-shell-path* "/bin/zsh")))))

  (testing "unsupported shell (e.g. fish): returns nil without spawning"
    (let [spawned? (atom false)]
      (with-redefs [interpolation/run-process! (fn [& _] (reset! spawned? true) {:exit 0 :out "" :err ""})]
        (is (nil? (interpolation/query-user-shell-path* "/usr/local/bin/fish")))
        (is (false? @spawned?)))))

  (testing "non-zero exit: returns nil"
    (with-redefs [interpolation/run-process! (fn [& _] {:exit 1 :out "" :err "boom"})]
      (is (nil? (interpolation/query-user-shell-path* "/bin/bash")))))

  (testing "missing delimiter in stdout: returns nil"
    (with-redefs [interpolation/run-process! (fn [& _] {:exit 0 :out "no delim here" :err ""})]
      (is (nil? (interpolation/query-user-shell-path* "/bin/bash")))))

  (testing "empty captured PATH: returns nil"
    (with-redefs [interpolation/run-process! (fn [& _]
                                               {:exit 0
                                                :out "__ECA_PATH_DELIM____ECA_PATH_DELIM__"
                                                :err ""})]
      (is (nil? (interpolation/query-user-shell-path* "/bin/bash")))))

  (testing "exception during process (e.g. timeout): returns nil"
    (with-redefs [interpolation/run-process! (fn [& _] (throw (ex-info "Command timed out" {})))]
      (is (nil? (interpolation/query-user-shell-path* "/bin/bash"))))))

(deftest user-shell-path-cache-test
  (testing "first call queries; subsequent calls hit the cache"
    (interpolation/reset-shell-path-cache!)
    (let [calls (atom 0)]
      (with-redefs [interpolation/query-user-shell-path (fn []
                                                          (swap! calls inc)
                                                          "/from/shell:/usr/bin")]
        (is (= "/from/shell:/usr/bin" (interpolation/user-shell-path)))
        (is (= "/from/shell:/usr/bin" (interpolation/user-shell-path)))
        (is (= "/from/shell:/usr/bin" (interpolation/user-shell-path)))
        (is (= 1 @calls)))))

  (testing "nil result is also cached -- the query is not retried on every miss"
    (interpolation/reset-shell-path-cache!)
    (let [calls (atom 0)]
      (with-redefs [interpolation/query-user-shell-path (fn []
                                                          (swap! calls inc)
                                                          nil)]
        (is (nil? (interpolation/user-shell-path)))
        (is (nil? (interpolation/user-shell-path)))
        (is (= 1 @calls)))))

  (testing "reset-shell-path-cache! forces a re-query"
    (interpolation/reset-shell-path-cache!)
    (let [calls (atom 0)]
      (with-redefs [interpolation/query-user-shell-path (fn []
                                                          (swap! calls inc)
                                                          "/some/path")]
        (interpolation/user-shell-path)
        (interpolation/reset-shell-path-cache!)
        (interpolation/user-shell-path)
        (is (= 2 @calls))))))

(deftest effective-path-test
  (testing "non-mac: returns existing PATH unchanged (no shell query)"
    (with-redefs [interpolation/user-shell-path (fn [] (throw (ex-info "should not be called" {})))]
      (is (= "/usr/bin:/bin"
             (interpolation/effective-path "Linux" "/usr/bin:/bin" "/home/u" ":")))
      (is (= "C:\\Windows"
             (interpolation/effective-path "Windows 10" "C:\\Windows" "C:\\Users\\u" ";")))))

  (testing "mac with shell PATH available: returns shell PATH verbatim"
    (with-redefs [interpolation/user-shell-path (constantly "/from/shell:/opt/homebrew/bin:/usr/bin")]
      (is (= "/from/shell:/opt/homebrew/bin:/usr/bin"
             (interpolation/effective-path "Mac OS X" "/usr/bin" "/home/u" ":")))))

  (testing "mac with no shell PATH: falls back to augment-path"
    (with-redefs [interpolation/user-shell-path (constantly nil)]
      (is (= "/opt/homebrew/bin:/usr/local/bin:/home/u/.local/bin:/usr/bin"
             (interpolation/effective-path "Mac OS X" "/usr/bin" "/home/u" ":"))))))

(deftest resolve-cmd-test
  (testing "success: trimmed stdout"
    (with-redefs [interpolation/run-process! (fn [& _] {:exit 0 :out "secret\n" :err ""})
                  logger/warn (fn [& _] nil)]
      (is (= "secret" (interpolation/resolve-cmd "pass show eca/key")))))

  (testing "trailing whitespace (multiple newlines, spaces) is fully trimmed"
    (with-redefs [interpolation/run-process! (fn [& _] {:exit 0 :out "abc\n\n  \n" :err ""})
                  logger/warn (fn [& _] nil)]
      (is (= "abc" (interpolation/resolve-cmd "echo abc")))))

  (testing "internal whitespace and leading whitespace are preserved"
    (with-redefs [interpolation/run-process! (fn [& _] {:exit 0 :out "  multi\n  line\n" :err ""})
                  logger/warn (fn [& _] nil)]
      (is (= "  multi\n  line" (interpolation/resolve-cmd "echo")))))

  (testing "non-zero exit: returns empty string and warns"
    (let [warned (atom nil)]
      (with-redefs [interpolation/run-process! (fn [& _] {:exit 1 :out "" :err "boom"})
                    logger/warn (fn [& args] (reset! warned args))]
        (is (= "" (interpolation/resolve-cmd "false")))
        (is (some? @warned)))))

  (testing "exception during process: returns empty string and warns"
    (let [warned (atom nil)]
      (with-redefs [interpolation/run-process! (fn [& _] (throw (ex-info "spawn fail" {})))
                    logger/warn (fn [& args] (reset! warned args))]
        (is (= "" (interpolation/resolve-cmd "missing-binary")))
        (is (some? @warned)))))

  (testing "timeout: returns empty string and warns"
    (let [warned (atom nil)]
      (with-redefs [interpolation/run-process! (fn [& _]
                                                 (throw (ex-info "Command timed out"
                                                                 {:cmd ["bash" "-c" "sleep 60"]
                                                                  :timeout-ms 30000})))
                    logger/warn (fn [& args] (reset! warned args))]
        (is (= "" (interpolation/resolve-cmd "sleep 60")))
        (is (some? @warned)))))

  (testing "passes augmented PATH from effective-path to run-process! via :extra-env"
    (let [captured-env (atom nil)]
      (with-redefs [interpolation/effective-path (fn [_os _path _home _sep]
                                                   "/computed/path:/from/effective")
                    interpolation/run-process! (fn [_cmd extra-env _timeout]
                                                 (reset! captured-env extra-env)
                                                 {:exit 0 :out "ok\n" :err ""})
                    logger/warn (fn [& _] nil)]
        (is (= "ok" (interpolation/resolve-cmd "echo ok")))
        (is (= {"PATH" "/computed/path:/from/effective"} @captured-env)))))

  (testing "passes empty :extra-env when effective-path leaves the PATH unchanged"
    (let [captured-env (atom nil)]
      (with-redefs [interpolation/effective-path (fn [_os existing-path _home _sep]
                                                   existing-path)
                    interpolation/run-process! (fn [_cmd extra-env _timeout]
                                                 (reset! captured-env extra-env)
                                                 {:exit 0 :out "ok\n" :err ""})
                    logger/warn (fn [& _] nil)]
        (is (= "ok" (interpolation/resolve-cmd "echo ok")))
        (is (= {} @captured-env))))))
