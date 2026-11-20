(ns eca.base-configs-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.base-configs :as base-configs]
   [eca.config :as config]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]
   [org.httpkit.server :as http-server]))

(h/reset-components-before-test)

(defn ^:private with-server
  "Calls `f` with the URL of a local server answering each path with its
   response in `responses*`, or a function returning it, recording the
   requests in `requests*`."
  [responses* requests* f]
  (let [server (http-server/run-server
                (fn [{:keys [uri headers]}]
                  (swap! requests* conj {:uri uri :headers headers})
                  (let [response (get @responses* uri {:status 404 :body "not found"})]
                    (if (fn? response) (response) response)))
                {:port 0 :legacy-return-value? false})]
    (try
      (f (str "http://localhost:" (http-server/server-port server)))
      (finally
        (http-server/server-stop! server)))))

(defn ^:private warnings []
  (mapv :message (:show-message (h/messages))))

(defn ^:private session-config!
  "Makes `base-config-entries` the session's `baseConfigs`."
  [base-config-entries]
  (reset! config/initialization-config* {:pureConfig true :baseConfigs base-config-entries})
  (config/clear-cache!))

(defn ^:private git!
  "Runs git in `dir`, without signing, as a fixed author. Throws when it fails."
  [dir & args]
  (:out (apply p/shell {:dir (str dir) :out :string :err :string}
               "git" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false"
               "-c" "user.name=ECA" "-c" "user.email=eca@example.com" args)))

(defn ^:private commit!
  "Commits `files` (path → content, nil deletes it) in the `work` clone and
   pushes them to its origin's `branch`."
  ([work files] (commit! work "main" files))
  ([work branch files]
   (doseq [[path content] files
           :let [file (fs/file work path)]]
     (if content
       (do (fs/create-dirs (fs/parent file))
           (spit file content))
       (fs/delete file)))
   (git! work "add" "--all")
   (git! work "commit" "--quiet" "--message" "change")
   (git! work "push" "--quiet" "origin" (str "HEAD:" branch))))

(defn ^:private with-git-repo
  "Calls `f` with a repository with `files` on `main`, served as GitHub does:
   a clone can leave files out, fetching each one when read. `f` gets its
   `file://` `:url` and a `:work` repository to commit to."
  [files f]
  (let [dir (fs/create-temp-dir {:prefix "eca-base-config-repo"})
        origin (fs/file dir "origin.git")
        work (fs/file dir "work")]
    (try
      (git! dir "init" "--quiet" "--bare" "--initial-branch=main" (str origin))
      (git! origin "config" "uploadpack.allowFilter" "true")
      (git! origin "config" "uploadpack.allowAnySHA1InWant" "true")
      (git! dir "init" "--quiet" "--initial-branch=main" (str work))
      (git! work "remote" "add" "origin" (str origin))
      (commit! work files)
      (f {:url (str (.toUri (fs/path origin))) :work work})
      (finally
        (fs/delete-tree dir {:force true})))))

(defn ^:private head [work]
  (string/trim (git! work "rev-parse" "HEAD")))

(defn ^:private git-commands
  "The git subcommands base configs ran while calling `f`."
  [f]
  (let [commands* (atom [])
        run-git! @#'base-configs/run-git!]
    (with-redefs-fn {#'base-configs/run-git! (fn [dir & args]
                                               (swap! commands* conj (first args))
                                               (apply run-git! dir args))}
      f)
    @commands*))

(deftest sources-test
  (testing "URLs and {url, headers} maps, in order, trimmed, without repeats"
    (is (= [{:url "https://a.example/base.json"}
            {:url "https://b.example/base.json" :headers {"Authorization" "Bearer t0k" "X-Count" "1"}}]
           (map #(select-keys % [:url :headers])
                (base-configs/sources {:baseConfigs [" https://a.example/base.json "
                                                     {:url "https://b.example/base.json"
                                                      :headers {"Authorization" "Bearer t0k" :X-Count 1}}
                                                     "https://a.example/base.json"]})))))
  (testing "a single URL"
    (is (= ["https://a.example/base.json"]
           (map :url (base-configs/sources {:baseConfigs "https://a.example/base.json"})))))
  (testing "files in git repositories, with an optional ref, without repeats"
    (is (= [{:git "git@github.com:nubank/nudev.git" :path "ides/eca/config.json"
             :display "ides/eca/config.json from git@github.com:nubank/nudev.git"}
            {:git "https://x:ghp_s3cr3t@github.com/acme/eca.git" :path "base.json" :ref "v1"
             :display "base.json from https://github.com/acme/eca.git at v1"}]
           (map #(dissoc % :id)
                (base-configs/sources {:baseConfigs [{:git "git@github.com:nubank/nudev.git" :path "/ides/eca/config.json"}
                                                     {:git "git@github.com:nubank/nudev.git" :path "ides/eca/config.json"}
                                                     {:git "https://x:ghp_s3cr3t@github.com/acme/eca.git" :path "base.json" :ref "v1"}]})))))
  (testing "git entries that can't be fetched are left out"
    (is (= [] (base-configs/sources {:baseConfigs [{:git "http://example.com/eca.git" :path "base.json"}
                                                   {:git "git://example.com/eca.git" :path "base.json"}
                                                   {:git "ext::sh -c touch% /tmp/pwned" :path "base.json"}
                                                   {:git "--upload-pack=touch /tmp/pwned" :path "base.json"}
                                                   {:git "git@github.com:acme/eca.git" :path "base.json" :ref "--output=x"}
                                                   {:git "git@github.com:acme/eca.git"}
                                                   {:git " " :path "base.json"}]}))))
  (testing "plain HTTP only for localhost"
    (is (= ["http://localhost:5550/base.json" "http://127.0.0.1/base.json"]
           (map :url (base-configs/sources {:baseConfigs ["http://localhost:5550/base.json"
                                                          "http://127.0.0.1/base.json"
                                                          "http://evil.example/base.json"]})))))
  (testing "entries that aren't HTTPS URLs are left out"
    (is (= [] (base-configs/sources {:baseConfigs ["file:///etc/eca.json" "base.json" "not a url"
                                                   {:headers {"A" "b"}} 42]})))))

(deftest display-url-test
  (testing "without user info, query and fragment, which may hold secrets"
    (is (= "https://b.example:8443/base.json?…"
           (base-configs/display-url "https://user:pass@b.example:8443/base.json?token=s3cr3t#top"))))
  (is (= "https://a.example/base.json" (base-configs/display-url "https://a.example/base.json"))))

(deftest refresh-test
  (let [responses* (atom {"/base.json" {:status 200 :body "{\n  // shared defaults\n  \"defaultModel\": \"base/model\"\n}"}})
        requests* (atom [])]
    (with-server
      responses* requests*
      (fn [url]
        (let [base-url (str url "/base.json")
              config {:baseConfigs [{:url base-url :headers {"Authorization" "Bearer t0k"}}]}]
          (session-config! (:baseConfigs config))
          (testing "a fetched config is merged, comments allowed"
            (base-configs/refresh! config (h/messenger))
            (is (= [{"defaultModel" "base/model"}] @config/base-config-layers*))
            (is (= "base/model" (:defaultModel (config/all (h/db)))))
            (is (= "Bearer t0k" (get-in (last @requests*) [:headers "authorization"])))
            (is (= [] (warnings))))
          (testing "a failed fetch keeps the last good copy, warning once"
            (swap! responses* assoc "/base.json" {:status 503 :body "down"})
            (base-configs/refresh! config (h/messenger))
            (base-configs/refresh! config (h/messenger))
            (is (= [{"defaultModel" "base/model"}] @config/base-config-layers*))
            (is (match? [#"Could not refresh the base config http://localhost:\d+/base.json \(HTTP status 503\), using its copy from "]
                        (warnings)))
            (is (string/includes? (base-configs/status-text config) "the last fetch failed: HTTP status 503")))
          (testing "once fetched again, a later failure warns again"
            (swap! responses* assoc "/base.json" {:status 200 :body "{\"defaultModel\": \"base/new\"}"})
            (base-configs/refresh! config (h/messenger))
            (is (= [{"defaultModel" "base/new"}] @config/base-config-layers*))
            (swap! responses* assoc "/base.json" {:status 500 :body "boom"})
            (base-configs/refresh! config (h/messenger))
            (is (= 2 (count (warnings)))))
          (testing "a new session starts with the copy saved on disk, without fetching"
            (let [request-count (count @requests*)]
              (base-configs/reset-state!)
              (is (= [] @config/base-config-layers*))
              (base-configs/load-cached! config)
              (is (= [{"defaultModel" "base/new"}] @config/base-config-layers*))
              (is (= request-count (count @requests*)))
              (is (string/includes? (base-configs/status-text config) "not fetched yet"))))
          (testing "a base config no longer listed is no longer merged"
            (base-configs/refresh! {:baseConfigs []} (h/messenger))
            (is (= [] @config/base-config-layers*))))))))

(deftest refresh-failures-test
  (let [responses* (atom {"/500.json" {:status 500 :body "boom"}
                          "/invalid.json" {:status 200 :body "{not json"}
                          "/array.json" {:status 200 :body "[1, 2]"}})
        requests* (atom [])]
    (with-server
      responses* requests*
      (fn [url]
        (let [config {:baseConfigs (mapv #(str url %) ["/500.json" "/invalid.json" "/array.json"])}]
          (base-configs/refresh! config (h/messenger))
          (base-configs/refresh! config (h/messenger))
          (testing "nothing is merged"
            (is (= [] @config/base-config-layers*)))
          (testing "each failure warns once, saying ECA goes on without it"
            (is (match? [#"/500.json \(HTTP status 500\), ECA goes on without it"
                         #"/invalid.json \(invalid JSON: .+\), ECA goes on without it"
                         #"/array.json \(the response is not a JSON object\), ECA goes on without it"]
                        (warnings)))))))))

(deftest invalid-entries-test
  (testing "an entry that isn't an HTTPS URL is skipped, warning once"
    (base-configs/refresh! {:baseConfigs ["http://evil.example/base.json"]} (h/messenger))
    (base-configs/refresh! {:baseConfigs ["http://evil.example/base.json"]} (h/messenger))
    (is (= ["Skipping the baseConfigs entry http://evil.example/base.json: only https:// URLs are supported (http:// only for localhost)."]
           (warnings)))
    (is (= [] @config/base-config-layers*))))

(deftest git-refresh-test
  (with-git-repo
    {"eca/config.json" "{\n  // shared defaults\n  \"defaultModel\": \"base/v1\"\n}"}
    (fn [{:keys [url work]}]
      (let [config {:baseConfigs [{:git url :path "eca/config.json"}]}
            display (str "eca/config.json from " url)]
        (testing "the file at the default branch is merged, comments allowed"
          (is (= ["clone" "cat-file" "rev-parse"]
                 (git-commands #(base-configs/refresh! config (h/messenger)))))
          (is (= [{"defaultModel" "base/v1"}] @config/base-config-layers*))
          (is (re-find (re-pattern (str "\\Q" display ": fetched at \\E.+\\Q, commit " (subs (head work) 0 7) "\\E"))
                       (base-configs/status-text config)))
          (is (= [] (warnings))))
        (testing "while the branch stays at that commit, nothing is cloned"
          (is (= ["ls-remote"] (git-commands #(base-configs/refresh! config (h/messenger)))))
          (is (= [{"defaultModel" "base/v1"}] @config/base-config-layers*)))
        (testing "a new commit is fetched"
          (commit! work {"eca/config.json" "{\"defaultModel\": \"base/v2\"}"})
          (is (= ["ls-remote" "clone" "cat-file" "rev-parse"]
                 (git-commands #(base-configs/refresh! config (h/messenger)))))
          (is (= [{"defaultModel" "base/v2"}] @config/base-config-layers*)))
        (testing "a new session starts with the copy, which knows its commit"
          (base-configs/reset-state!)
          (is (= [] (git-commands #(base-configs/load-cached! config))))
          (is (= [{"defaultModel" "base/v2"}] @config/base-config-layers*))
          (is (= ["ls-remote"] (git-commands #(base-configs/refresh! config (h/messenger))))))
        (testing "once the file is gone, the copy stays, warning once"
          (commit! work {"eca/config.json" nil "README.md" "moved"})
          (base-configs/refresh! config (h/messenger))
          (base-configs/refresh! config (h/messenger))
          (is (= [{"defaultModel" "base/v2"}] @config/base-config-layers*))
          (is (match? [(re-pattern (str "^\\QCould not refresh the base config " display
                                        " (git cat-file failed: path 'eca/config.json' does not exist in 'HEAD'), using its copy from \\E"))]
                      (warnings)))
          (is (string/includes? (base-configs/status-text config)
                                "the last fetch failed: git cat-file failed: path 'eca/config.json' does not exist in 'HEAD'")))))))

(deftest git-ref-test
  (with-git-repo
    {"base.json" "{\"defaultModel\": \"main/model\"}"}
    (fn [{:keys [url work]}]
      (commit! work "release" {"base.json" "{\"defaultModel\": \"release/model\"}"})
      ;; `git ls-remote <url> release` lists it first, before refs/heads/release
      (commit! work "a/release" {"base.json" "{\"defaultModel\": \"other/model\"}"})
      (commit! work "tagged" {"base.json" "{\"defaultModel\": \"tag/model\"}"})
      (git! work "tag" "--annotate" "--message" "v1" "v1")
      (git! work "push" "--quiet" "origin" "v1")
      (let [config {:baseConfigs [{:git url :path "base.json" :ref "release"}
                                  {:git url :path "base.json" :ref "v1"}]}]
        (testing "the file at a branch and at an annotated tag"
          (base-configs/refresh! config (h/messenger))
          (is (= [{"defaultModel" "release/model"} {"defaultModel" "tag/model"}]
                 @config/base-config-layers*))
          (is (= [] (warnings))))
        (testing "while they stay at those commits, nothing is cloned"
          (is (= ["ls-remote" "ls-remote"]
                 (git-commands #(base-configs/refresh! config (h/messenger)))))))
      (testing "a ref that doesn't exist"
        (base-configs/refresh! {:baseConfigs [{:git url :path "base.json" :ref "nope"}]} (h/messenger))
        (is (= [] @config/base-config-layers*))
        (is (= [(str "Could not load the base config base.json from " url " at nope"
                     " (git clone failed: Remote branch nope not found in upstream origin),"
                     " ECA goes on without it: check your connection and your access to it.")]
               (warnings)))))))

(deftest git-unreachable-test
  (testing "a repository that can't be cloned, without a copy, warns ECA goes on without it"
    (let [url (str (.toUri (fs/path (fs/temp-dir) (str "eca-no-such-repo-" (random-uuid) ".git"))))]
      (base-configs/refresh! {:baseConfigs [{:git url :path "base.json"}]} (h/messenger))
      (is (= [] @config/base-config-layers*))
      (is (match? [#"^Could not load the base config base\.json from file:.+ \(git clone failed: '.+' does not appear to be a git repository\), ECA goes on without it: check your connection and your access to it\.$"]
                  (warnings))))))

(deftest git-env-test
  (testing "git gets ECA's environment without what points it elsewhere or prompts in a window, prompts off"
    (is (= {"PATH" "/usr/bin" "GIT_SSH_COMMAND" "ssh -i ~/.ssh/work"
            "GIT_TERMINAL_PROMPT" "0" "SSH_ASKPASS_REQUIRE" "never" "GCM_INTERACTIVE" "never"
            "GIT_OPTIONAL_LOCKS" "0"}
           (#'base-configs/git-env {"PATH" "/usr/bin" "GIT_SSH_COMMAND" "ssh -i ~/.ssh/work"
                                    "GIT_DIR" "/elsewhere/.git" "GIT_CONFIG_PARAMETERS" "'core.pager'='less'"
                                    "GIT_ASKPASS" "/usr/bin/ksshaskpass" "SSH_ASKPASS" "/usr/bin/ksshaskpass"})))))

(deftest invalid-git-entries-test
  (testing "a git entry that can't be fetched is skipped without running git, warning once"
    (let [config {:baseConfigs [{:git "http://example.com/eca.git" :path "base.json"}
                                {:git "ext::sh -c touch% /tmp/pwned" :path "base.json"}
                                {:git "--upload-pack=touch /tmp/pwned" :path "base.json"}
                                {:git "git@github.com:acme/eca.git"}]}]
      (is (= [] (git-commands #(dotimes [_ 2] (base-configs/refresh! config (h/messenger))))))
      (is (= ["Skipping the baseConfigs entry base.json from http://example.com/eca.git: only ssh, https or local git repositories are supported."
              "Skipping the baseConfigs entry base.json from ext::sh -c touch% /tmp/pwned: git remote helpers aren't supported."
              "Skipping the baseConfigs entry base.json from --upload-pack=touch /tmp/pwned: it's not a valid git repository or ref."
              "Skipping the baseConfigs entry git@github.com:acme/eca.git: it has no path."]
             (warnings)))
      (is (= [] @config/base-config-layers*)))))

(deftest on-config-changed-test
  (let [responses* (atom {"/base.json" {:status 200 :body "{\"defaultModel\": \"base/model\"}"}})
        requests* (atom [])]
    (with-server
      responses* requests*
      (fn [url]
        (let [config {:baseConfigs [(str url "/base.json")]}]
          (testing "nothing to do for the first config, or the same list"
            (is (nil? (base-configs/on-config-changed! nil config (h/messenger))))
            (is (nil? (base-configs/on-config-changed! config config (h/messenger)))))
          (testing "a base config added while ECA runs is fetched right away"
            @(base-configs/on-config-changed! {:baseConfigs []} config (h/messenger))
            (is (= [{"defaultModel" "base/model"}] @config/base-config-layers*)))
          (testing "one removed stops being merged"
            @(base-configs/on-config-changed! config {:baseConfigs []} (h/messenger))
            (is (= [] @config/base-config-layers*))))))))

(deftest start-test
  (with-redefs-fn {#'base-configs/start-refresh-loop! (fn [& _])}
    (fn []
      (let [gate (promise)
            responses* (atom {"/base.json" (fn []
                                             (deref gate 5000 nil)
                                             {:status 200 :body "{\"defaultModel\": \"base/fresh\"}"})})
            requests* (atom [])]
        (with-server
          responses* requests*
          (fn [url]
            (let [base-url (str url "/base.json")]
              (session-config! [base-url])
              (testing "with a copy loaded, the session goes on while the fetch updates it"
                (#'base-configs/save-copy! {:id base-url :display base-url} {"defaultModel" "base/cached"} 1000 nil)
                (base-configs/load-cached! (config/all (h/db)))
                (base-configs/start! (h/db*) (h/messenger))
                (is (= "base/cached" (:defaultModel (config/all (h/db)))))
                (deliver gate true)
                (is (= "base/fresh" (loop [tries 50]
                                      (let [model (:defaultModel (config/all (h/db)))]
                                        (if (or (= "base/fresh" model) (zero? tries))
                                          model
                                          (do (Thread/sleep 100) (recur (dec tries)))))))))
              (testing "without a copy, it waits for the fetch"
                (base-configs/reset-state!)
                (swap! responses* assoc "/base.json" (fn []
                                                       (Thread/sleep 300)
                                                       {:status 200 :body "{\"defaultModel\": \"base/first\"}"}))
                (base-configs/start! (h/db*) (h/messenger))
                (is (= "base/first" (:defaultModel (config/all (h/db)))))))))))))

(deftest status-text-test
  (testing "nothing without base configs"
    (is (nil? (base-configs/status-text {:baseConfigs []}))))
  (testing "each base config and how it stands, without its secrets"
    (swap! base-configs/state* assoc "https://a.example/base.json?token=s3cr3t"
           {:config {} :status :stale :fetched-at 0 :error "HTTP status 503"})
    (let [text (base-configs/status-text
                {:baseConfigs [{:url "https://a.example/base.json?token=s3cr3t"
                                :headers {"Authorization" "Bearer s3cr3t"}}
                               "https://b.example/base.json"
                               "http://evil.example/base.json"]})]
      (is (string/starts-with? text "Base configs:\n"))
      (is (re-find #"https://a\.example/base\.json\?…: using its copy from .+, the last fetch failed: HTTP status 503" text))
      (is (string/includes? text "https://b.example/base.json: not fetched yet"))
      (is (string/includes? text "http://evil.example/base.json: skipped, only https:// URLs are supported"))
      (is (not (string/includes? text "s3cr3t"))))))
