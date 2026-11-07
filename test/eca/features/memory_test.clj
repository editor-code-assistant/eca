(ns eca.features.memory-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [babashka.fs :as fs]
   [eca.cache :as cache]
   [eca.config :as config]
   [eca.features.memory :as memory]
   [eca.features.memory.render :as render]
   [eca.features.memory.usage :as usage]
   [eca.shared :as shared]
   [eca.test-helper :as test-helper]))

(set! *warn-on-reflection* true)

(defn ^:private test-db [roots]
  {:workspace-folders (mapv (fn [r] {:uri (shared/filename->uri (str r))}) roots)})

(deftest config-accessors-test
  (is (false? (memory/enabled? {})))
  (is (false? (memory/enabled? {:memory {:enabled false}})))
  (is (true? (memory/enabled? {:memory {:enabled true}})))
  (testing "write-mode"
    (is (= "agent" (memory/write-mode {})))
    (is (= "agent" (memory/write-mode {:memory {:writeMode "agent"}})))
    (is (= "explicit" (memory/write-mode {:memory {:writeMode "explicit"}})))
    (is (= "explicit" (memory/write-mode {:memory {:writeMode "off"}}))
        "removed value coerces to the fail-safe mode")
    (is (= "explicit" (memory/write-mode {:memory {:writeMode "bogus"}}))
        "invalid values fail safe to explicit, never to autonomous writes"))
  (testing "index caps"
    (is (= 100 (memory/index-max-entries {})))
    (is (= 42 (memory/index-max-entries {:memory {:index {:maxEntries 42}}})))
    (is (= 2000 (memory/index-max-tokens {})))
    (is (= 500 (memory/index-max-tokens {:memory {:index {:maxTokens 500}}})))))

(deftest memory-root-dir-test
  (testing "respects $XDG_DATA_HOME when set"
    (with-redefs [config/get-env (fn [k] (when (= k "XDG_DATA_HOME") "/raw/data-x"))]
      (is (= (str (io/file "/raw/data-x" "eca" "memory"))
             (str (memory/memory-root-dir))))))
  (testing "falls back to <user-home>/.local/share when unset"
    (with-redefs [config/get-env (constantly nil)
                  config/user-home (constantly (test-helper/file-path "/home/someuser"))]
      (is (= (str (io/file (test-helper/file-path "/home/someuser") ".local" "share" "eca" "memory"))
             (str (memory/memory-root-dir)))))))

(deftest project-slug-test
  (testing "readable slug with hash suffix, no leading/trailing dashes"
    (is (re-matches #".*memory-test-workspace-[0-9a-f]{8}"
                    (memory/project-slug (test-helper/file-path "/memory-test/workspace"))))
    (is (= (memory/project-slug "/a/c") (memory/project-slug "/a//b/../c")))
    (is (= (memory/project-slug (memory/abs-path "my-project"))
           (memory/project-slug "my-project")))
    (is (re-matches #".*pr-ject-[0-9a-f]{8}" (memory/project-slug "prøject"))))
  (testing "safe as a shell argument: never starts with a dash"
    (is (not (string/starts-with? (memory/project-slug "/home/akiz/x") "-"))))
  (testing "deterministic"
    (is (= (memory/project-slug "/home/akiz/eca") (memory/project-slug "/home/akiz/eca"))))
  (testing "hash suffix keeps distinct paths unique when readable slugs collide"
    (is (apply distinct?
               (map memory/project-slug ["/home/me/my.project" "/home/me/my project" "/home/me/my-project"]))))
  (testing "readable part is bounded even for very deep paths"
    (is (<= (count (memory/project-slug (str "/" (string/join "/" (repeat 40 "deep")))))
            (+ 64 1 8))))
  (testing "a long path keeps its end, which names the project"
    (is (re-matches #".*-monorepo-services-payments-gateway-[0-9a-f]{8}"
                    (memory/project-slug
                     (test-helper/file-path
                      "/home/someone/Code/Clojure/company/very/deeply/nested/monorepo/services/payments-gateway")))))
  (testing "worktree canonicalization is delegated to cache/canonicalize-workspace-path"
    (let [main (memory/abs-path (test-helper/file-path "/main/root"))]
      (with-redefs [cache/canonicalize-workspace-path (constantly main)]
        (is (= (memory/project-slug main) (memory/project-slug "/some/worktree")))))))

(deftest canonical-project-paths-test
  (test-helper/with-memory-root
    (fn [root]
      (let [project (fs/create-dirs (fs/path root "checkout"))
            alias (fs/path root "alias")
            dotted (str project "/./")]
        (try
          (is (= (memory/project-slug project) (memory/project-slug dotted)))
          (let [dirs (with-redefs [memory/workspace-paths (constantly [dotted (str project)])]
                       (memory/memory-dirs {}))]
            (is (= 2 (count dirs)) "project aliases contribute one personal directory")
            (is (= (memory/abs-path (memory/personal-dir project)) (:dir (first dirs))))
            (is (not (fs/exists? (memory/personal-dir project)))
                "resolving directories does not create them"))
          (when-not shared/windows-os?
            (fs/create-sym-link alias project)
            (is (= (memory/project-slug project) (memory/project-slug alias)))
            (is (= (memory/abs-path project) (memory/abs-path alias)))
            (is (= 2 (count (memory/memory-dirs (test-db [(str alias) (str project)])))))
            (with-redefs [cache/canonicalize-workspace-path
                          #(if (= (memory/abs-path project) %) (str root) %)]
              (is (= (memory/project-slug root) (memory/project-slug alias)))))
          (let [case-alias (fs/path root "CHECKOUT")]
            (when (fs/exists? case-alias)
              (is (= (memory/project-slug project) (memory/project-slug case-alias)))))
          (finally (fs/delete-tree root)))))))

(deftest canonical-note-paths-are-listed-once-test
  (when-not shared/windows-os?
    (test-helper/with-memory-root
      (fn [root]
        (try
          (let [project (fs/create-dirs (fs/path root "checkout"))
                db (test-db [(str project)])
                personal (fs/create-dirs (memory/personal-dir project))
                global (fs/create-dirs (memory/global-dir))
                note (fs/path personal "note.md")
                label (:label (first (memory/memory-dirs db)))]
            (spit (str note) "---\nname: One note\ndescription: D\n---\n\nbody\n")
            (fs/create-sym-link (fs/path personal "alias.md") note)
            (doseq [across-dirs? [false true]]
              (when across-dirs?
                (fs/create-sym-link (fs/path global "alias.md") note))
              (doseq [entries [(memory/scan-memories db) (usage/ranked-memories db)]]
                (let [index (render/index-context {:entries entries :max-entries 10 :max-tokens 2000})]
                  (is (= 1 (count entries)))
                  (is (= [(memory/abs-path note) :personal label]
                         ((juxt :path :tier :label) (first entries))))
                  (is (= 1 (:item-count index)))))
              (let [entries (memory/list-memories db)]
                (is (= 1 (count entries)))
                (is (= [(memory/abs-path note) "personal" label]
                       ((juxt :path :tier :label) (first entries))))))
            (spit (str note) "---\nname: Missing description\n---\n\nbody\n")
            (let [skipped (memory/skipped-files db)]
              (is (= 1 (count skipped)) "repair lists also identify each physical file once")
              (is (= [(memory/abs-path note) :personal label]
                     ((juxt :path :tier :label) (first skipped))))))
          (finally (fs/delete-tree root)))))))

(deftest links-leaving-the-memory-dirs-are-skipped-test
  (when-not shared/windows-os?
    (test-helper/with-memory-root
      (fn [root]
        (let [db (test-db [])
              global (fs/create-dirs (memory/global-dir))
              target (fs/path (fs/create-dirs (fs/path root "elsewhere")) "linked.md")
              link (fs/path global "linked.md")
              note "---\nname: %s\ndescription: D\n---\n\nbody\n"]
          (spit (str (fs/path global "own.md")) (format note "Own"))
          (spit (str target) (format note "Linked"))
          (fs/create-sym-link link target)
          (is (= ["Own"] (mapv :name (memory/scan-memories db)))
              "memory-path? is false for the target, so it must not be indexed as a note")
          (let [skipped (memory/skipped-files db)]
            (is (= [(str link)] (mapv :path skipped))
                "the link is reported, because that is what the user can fix")
            (is (string/includes? (:reason (first skipped)) (str target)))))))))

(deftest unreadable-file-skip-reason-test
  (when-not shared/windows-os?
    (test-helper/with-memory-root
      (fn [_root]
        (let [global (fs/create-dirs (memory/global-dir))]
          ;; A link without a target: the file is listed, and reading it fails.
          (fs/create-sym-link (fs/path global "gone.md") (fs/path global "no-such-target.md"))
          (let [skipped (memory/skipped-files (test-db []))]
            (is (= 1 (count skipped)))
            (is (string/starts-with? (:reason (first skipped)) "cannot read file:")
                "a read error is not reported as a YAML problem")))))))

(deftest workspace-paths-test
  (is (= [(test-helper/file-path "/repo/one") (test-helper/file-path "/repo/two")]
         (memory/workspace-paths (test-db [(test-helper/file-path "/repo/one")
                                           (test-helper/file-path "/repo/two")]))))
  (is (= [] (memory/workspace-paths {})))
  (is (= [] (memory/workspace-paths {:workspace-folders []}))))

(deftest memory-dirs-ordering-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")
                         (test-helper/file-path "/repo/two")])
            dirs (memory/memory-dirs db)]
        (is (= [:personal :personal :global] (mapv :tier dirs))
            "two tiers only: personal per root, then global")
        (is (= ["one" "two" "global"] (mapv :label dirs)))
        (is (= (str (fs/file root "projects"
                             (memory/project-slug (test-helper/file-path "/repo/one"))))
               (:dir (first dirs))))
        (is (= (str (fs/file root "global")) (:dir (last dirs))))
        (is (every? fs/absolute? (map :dir dirs)))))))

(deftest memory-dirs-worktrees-share-one-dir-test
  (test-helper/with-memory-root
    (fn [_root]
      (let [main (test-helper/file-path "/repo/main")
            linked (test-helper/file-path "/repo/linked")]
        (with-redefs [cache/canonicalize-workspace-path #(if (= linked %) main %)]
          (let [dirs (memory/memory-dirs (test-db [main linked]))]
            (is (= [:personal :global] (mapv :tier dirs))
                "a main worktree and its linked worktree give one personal dir")
            (is (= ["main" "global"] (mapv :label dirs)))))))))

(deftest memory-dirs-unique-labels-test
  (test-helper/with-memory-root
    (fn [_root]
      (let [a (test-helper/file-path "/a/app")
            b (test-helper/file-path "/b/app")
            hash-of #(last (string/split (memory/project-slug %) #"-"))]
        (testing "two roots with one basename get the hash of their root"
          (let [dirs (memory/memory-dirs (test-db [a b (test-helper/file-path "/c/other")]))]
            (is (= [(str "app (" (hash-of a) ")") (str "app (" (hash-of b) ")") "other" "global"]
                   (mapv :label dirs)))
            (is (string/ends-with? (:dir (first dirs)) (hash-of a))
                "the suffix matches the dir name")))
        (testing "a root named like the global tier gets the hash too"
          (let [g (test-helper/file-path "/work/global")]
            (is (= [(str "global (" (hash-of g) ")") "global"]
                   (mapv :label (memory/memory-dirs (test-db [g])))))))
        (testing "the index shows one heading per root"
          (let [dirs (memory/memory-dirs (test-db [a b]))
                index (:content (render/index-context
                                 {:entries (mapv (fn [{:keys [dir label tier]}]
                                                   {:path (str dir "/n.md") :name (str "Note " label)
                                                    :type "fact" :description "d" :tags []
                                                    :tier tier :label label})
                                                 (butlast dirs))
                                  :max-entries 10 :max-tokens 2000}))]
            (is (= 2 (count (re-seq #"\n## app \(" index))))))))))

(defn ^:private write-memory-file!
  [f content]
  (fs/create-dirs (fs/parent f))
  (spit (str f) content))

(defn ^:private write-ledger!
  "Replace the on-disk usage ledger with `ledger`."
  [root ledger]
  (spit (str (fs/file root "usage.edn")) (pr-str ledger)))

(defn ^:private write-memory-file-at!
  "Write a valid memory file and pin its mtime to `mtime-ms`."
  [f mtime-ms]
  (write-memory-file! f "---\nname: n\ndescription: d\n---\n\nbody\n")
  (.setLastModified (io/file (str f)) ^long mtime-ms))

(deftest memory-content-error-test
  (testing "valid contents return nil"
    (is (nil? (memory/memory-content-error "---\nname: A\ndescription: D\n---\n\nbody\n")))
    (is (nil? (memory/memory-content-error "---\nname: A\ndescription: D\n---\n"))
        "empty body is fine")
    (is (nil? (memory/memory-content-error "---\nname: \" A \"\ndescription: \" D \"\n---\n\nbody\n"))
        "whitespace-padded values trim to non-blank")
    (is (nil? (memory/memory-content-error "---\nname: A\ndescription: D\ntype: gotcha\ntags:\n  - docker\n  - ci\n---\n\nBody text here.\n"))
        "type/tags are not validated")
    (is (nil? (memory/memory-content-error "---\nname: A\ndescription: D\ntags: alpha, beta\n---\n\nbody\n"))
        "tags format is not validated (lenient like parse-memory-file)"))
  (testing "unparseable frontmatter"
    (let [err (memory/memory-content-error "---\nname: [unclosed\n---\n")]
      (is (string/includes? err "YAML frontmatter does not parse"))
      (is (string/includes? err "fix and retry")))
    (is (string/includes? (memory/memory-content-error "---\nname: A\nno closing frontmatter\n")
                          "Unclosed YAML frontmatter")))
  (testing "non-mapping frontmatter"
    (is (string/includes? (memory/memory-content-error "---\n- just\n- a\n- list\n---\n")
                          "YAML frontmatter must be a mapping")))
  (testing "missing or blank required keys"
    (is (string/includes? (memory/memory-content-error "---\ndescription: D\n---\n\nbody\n") "`name`"))
    (is (string/includes? (memory/memory-content-error "---\nname: A\n---\n\nbody\n") "`description`"))
    (is (string/includes? (memory/memory-content-error "no frontmatter at all") "`name`")
        "content without any frontmatter is missing `name`")
    (is (string/includes? (memory/memory-content-error "---\nname: A\ndescription:   \n---\n") "`description`")
        "blank description is rejected")
    (is (string/includes? (memory/memory-content-error "---\nname:   \ndescription: D\n---\n") "`name`")
        "blank name is rejected"))
  (testing "error strings name the broken requirement"
    (is (= "Invalid memory file content: YAML frontmatter is missing required key `description` (memory files need non-blank name and description); fix and retry."
           (memory/memory-content-error "---\nname: A\n---\n\nbody\n")))))

(defn ^:private frontmatter-error
  [frontmatter]
  (memory/memory-content-error (str "---\n" frontmatter "\n---\n\nbody with #679 and C# is fine\n")))

(deftest memory-content-error-lost-text-test
  (testing "a YAML comment after text would silently lose that text"
    (doseq [[frontmatter line lost] [["name: A\ndescription: D\ntags: memory, #679" 4 "#679"]
                                     ["name: A\ndescription: D\ntags: #679" 4 "#679"]
                                     ["name: A\ndescription: D\ntags:\n  - auth\n  - #679" 6 "#679"]
                                     ["name: A\ndescription: Login fails, see #679" 3 "#679"]
                                     ["name: #679 fix\ndescription: D" 2 "#679 fix"]
                                     ["name: A\ndescription: 😀 see #679" 3 "#679"]
                                     ["name: A\ndescription: D\ntype: gotcha  # mine" 4 "# mine"]]]
      (let [error (frontmatter-error frontmatter)]
        (is (string/includes? (str error) (format "line %d (" line)) frontmatter)
        (is (string/includes? (str error) (str "would lose `" lost "`")) frontmatter))))
  (testing "the comment error wins over the missing key it causes"
    (is (string/includes? (frontmatter-error "name: A\ndescription: #679 login")
                          "would lose `#679 login`")))
  (testing "the message gives the fix and says the rule is only for memory frontmatter"
    (let [error (frontmatter-error "name: A\ndescription: D\ntags: memory, #679")]
      (is (string/includes? error "single quotes"))
      (is (string/includes? error "only to the frontmatter between the `---` lines of memory files"))
      (is (string/includes? error "`#` in the note body and in other files is fine"))))
  (testing "text that keeps every character passes"
    (doseq [frontmatter ["name: A\ndescription: D\ntags: 'memory, #679'"
                         "name: A\ndescription: 'Login fails, see #679'"
                         "name: A\ndescription: Use C# and issue#679"
                         "name: A\ndescription: |\n  see #679 here"
                         "# my own note\nname: A\ndescription: D"
                         "name: A\ndescription: D\ntags: # topics\n  - auth\n  - '#679'"
                         "name: A\ndescription: D\ntags: docker, ci"]]
      (is (nil? (frontmatter-error frontmatter)) frontmatter))))

(deftest memory-content-error-non-text-tags-test
  (testing "tags that YAML does not read as text would be lost"
    (doseq [frontmatter ["name: A\ndescription: D\ntags: 679"
                         "name: A\ndescription: D\ntags:\n  - auth\n  - 0123"
                         "name: A\ndescription: D\ntags: yes"
                         "name: A\ndescription: D\ntags:\n  key: value"]]
      (let [error (frontmatter-error frontmatter)]
        (is (string/includes? (str error) "does not read as text") frontmatter)
        (is (string/includes? (str error) "`tags: 'auth, 679'`") frontmatter))))
  (testing "quoted numbers, lists of text and empty list items pass"
    (doseq [frontmatter ["name: A\ndescription: D\ntags: 'auth, 679'"
                         "name: A\ndescription: D\ntags:\n  - auth\n  - '679'"
                         "name: A\ndescription: D\ntags:\n  - auth\n  -"
                         "name: A\ndescription: D"]]
      (is (nil? (frontmatter-error frontmatter)) frontmatter)))
  (testing "the scan stays lenient: such files are still indexed"
    (test-helper/with-memory-root
      (fn [root]
        (let [db (test-db [(test-helper/file-path "/repo/one")])]
          (write-memory-file! (fs/file root "global" "lenient.md")
                              "---\nname: Lenient\ndescription: see #679\ntags: 679, ci\n---\nbody\n")
          (let [entry (first (memory/scan-memories db))]
            (is (= "Lenient" (:name entry)))
            (is (= "see" (:description entry)) "YAML drops the comment; the scan does not reject")))))))

(deftest memory-content-error-reports-every-problem-test
  (testing "a cut description and a number tag come back in one message"
    (let [error (frontmatter-error "name: A\ndescription: see #679\ntags: [auth, 679]")]
      (is (string/includes? error "line 3 (`description: see #679`) would lose `#679`"))
      (is (string/includes? error "`tags` has a value that YAML does not read as text"))
      (is (= 2 (count (string/split-lines error))) "one line per problem")))
  (testing "two cut lines are named in one sentence"
    (let [error (frontmatter-error "name: A\ndescription: see #679\ntags: auth, #680")]
      (is (string/includes? error (str "line 3 (`description: see #679`) would lose `#679`,"
                                       " and line 4 (`tags: auth, #680`) would lose `#680`")))
      (is (= 1 (count (string/split-lines error))))))
  (testing "a name or description that YAML reads as a date, a number or a boolean"
    (doseq [[frontmatter field] [["name: 2026-11-12\ndescription: D" "name"]
                                 ["name: 1.10\ndescription: D" "name"]
                                 ["name: A\ndescription: yes" "description"]
                                 ["name: A\ndescription: 0123" "description"]]]
      (is (string/includes? (str (frontmatter-error frontmatter))
                            (str "`" field "` has a value that YAML does not read as text"))
          frontmatter))
    (is (nil? (frontmatter-error "name: '2026-11-12'\ndescription: 'yes'"))
        "quoted values are text")))

(deftest scan-memories-leniency-and-shape-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            personal (fs/file root "projects"
                              (memory/project-slug (test-helper/file-path "/repo/one")))
            global (fs/file root "global")]
        (write-memory-file! (fs/file personal "sub" "docker.md")
                            "---\nname: Docker IPv6\ndescription: CI fails on localhost\ntype: gotcha\ntags:\n  - docker\n  - ci\n---\n\nBody text here.\n")
        (write-memory-file! (fs/file global "top.md")
                            "---\nname: Top fact\ndescription: A global fact\ntags: alpha, beta\n---\n\nGlobal body.\n")
        (write-memory-file! (fs/file global "missing-description.md")
                            "---\nname: No description here\n---\n\nSkipped.\n")
        (write-memory-file! (fs/file global "unclosed.md")
                            "---\nname: Unclosed\nthis frontmatter never closes\n\n")
        (write-memory-file! (fs/file global "notes.txt") "not scanned")
        (let [entries (memory/scan-memories db)]
          (is (= 2 (count entries)) "only valid .md files are scanned")
          (is (= #{:personal :global} (set (map :tier entries))))
          (is (= #{"one" "global"} (set (map :label entries))))
          (let [docker (first (filter #(= "Docker IPv6" (:name %)) entries))
                top (first (filter #(= "Top fact" (:name %)) entries))]
            (is (= "gotcha" (:type docker)))
            (is (= ["docker" "ci"] (:tags docker)) "YAML list tags normalize to a vector")
            (is (= "Body text here." (:body docker)))
            (is (= "note" (:type top)) "absent type defaults to note")
            (is (= ["alpha" "beta"] (:tags top)) "comma-separated tags normalize to a vector")
            (is (string? (:path docker)))
            (is (number? (:mtime-ms docker)))))))))

(deftest scan-accepts-upper-case-extension-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])]
        (write-memory-file! (fs/file root "global" "NOTE.MD") "---\nname: Upper\ndescription: D\n---\n")
        (write-memory-file! (fs/file root "global" "README") "no extension, not scanned")
        (is (= ["Upper"] (mapv :name (memory/scan-memories db))))))))

(deftest unknown-memory-metadata-test
  (test-helper/with-memory-root
    (fn [root]
      (let [content "---\nname: Custom\ndescription: D\ntype: custom-kind\nextra-field:\n  nested: true\ntags: 42\n---\n"]
        (is (nil? (memory/memory-content-error (string/replace content "tags: 42\n" "")))
            "unknown fields and types pass the write check")
        (is (some? (memory/memory-content-error content))
            "a number tag does not: YAML reads it as a number and the tag would be lost")
        (write-memory-file! (fs/file root "global" "custom.md") content)
        (let [item (first (memory/list-memories {}))]
          (is (= "Custom" (:name item)))
          (is (= "custom-kind" (:type item)))
          (is (= [] (:tags item)) "the scan stays lenient and drops the number tag"))))))

(deftest normalize-tags-test
  (testing "tags lowercase, dedupe and cap at 4 on the read path"
    (test-helper/with-memory-root
      (fn [root]
        (let [db (test-db [(test-helper/file-path "/repo/one")])
              global (fs/file root "global")]
          (write-memory-file! (fs/file global "tagged.md")
                              "---\nname: Tagged\ndescription: D\ntags: Docker, docker, CI, ci, alpha, beta, gamma\n---\n\nbody\n")
          (let [entry (first (memory/scan-memories db))]
            (is (= ["docker" "ci" "alpha" "beta"] (:tags entry))
                "casing variants collapse, dupes drop, only the first 4 survive")))))))

(deftest skipped-files-reports-reasons-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            global (fs/file root "global")]
        (write-memory-file! (fs/file global "ok.md")
                            "---\nname: A\ndescription: D\n---\n\nbody\n")
        (write-memory-file! (fs/file global "missing-description.md")
                            "---\nname: No description here\n---\n\nSkipped.\n")
        (write-memory-file! (fs/file global "unclosed.md")
                            "---\nname: Unclosed\nthis frontmatter never closes\n\n")
        (write-memory-file! (fs/file global "notes.txt") "not scanned")
        (let [skipped (memory/skipped-files db)
              missing (first (filter #(string/ends-with? (:path %) "missing-description.md") skipped))
              unclosed (first (filter #(string/ends-with? (:path %) "unclosed.md") skipped))]
          (is (= 2 (count skipped)) "valid and non-.md files are not skipped")
          (is (= #{:global} (set (map :tier skipped))))
          (is (= #{"global"} (set (map :label skipped))))
          (is (string/includes? (:reason missing) "`description`")
              "skip reason names the missing key")
          (is (string/includes? (:reason unclosed) "frontmatter")
              "skip reason surfaces the YAML problem")
          (is (every? (comp string? :path) skipped)))))))

(deftest scan-memories-mtime-ordering-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            global (fs/file root "global")
            now-ms (System/currentTimeMillis)]
        (fs/create-dirs global)
        (doseq [[n delta] [["old.md" 300000] ["mid.md" 200000] ["new.md" 100000]]]
          (let [f (fs/file global n)]
            (spit (str f) (str "---\nname: " n "\ndescription: d\n---\n\nbody\n"))
            (.setLastModified (io/file (str f)) ^long (- now-ms delta))))
        (is (= ["new.md" "mid.md" "old.md"]
               (mapv :name (memory/scan-memories db))))))))

(deftest ranked-memories-zero-usage-preserves-mtime-order-test
  (testing "with an empty usage ledger the score degenerates to the mtime term,
            so ordering is exactly the plain mtime-desc order"
    (test-helper/with-memory-root
      (fn [root]
        (let [db (test-db [(test-helper/file-path "/repo/one")])
              global (fs/file root "global")
              now-ms (System/currentTimeMillis)]
          (write-ledger! root {})
          (fs/create-dirs global)
          (doseq [[n delta] [["old.md" 300000] ["mid.md" 200000] ["new.md" 100000]]]
            (write-memory-file-at! (fs/file global n) (- now-ms delta)))
          (let [entries (usage/ranked-memories db {:now-ms now-ms})]
            (is (= ["new.md" "mid.md" "old.md"]
                   (mapv #(fs/file-name (:path %)) entries)))
            (is (every? #(= 0 (:reads %)) entries))
            (is (every? #(nil? (:last-read-at %)) entries))))))))

(deftest ranked-memories-usage-score-ordering-test
  (testing "the design example, damped: a year-old 60-read entry last read 60d ago
            (~0.56) loses to a month-old 10-read entry last read yesterday (~2.69)"
    (test-helper/with-memory-root
      (fn [root]
        (let [db (test-db [(test-helper/file-path "/repo/one")])
              global (fs/file root "global")
              now-ms (System/currentTimeMillis)
              file-a (fs/file global "a.md")
              file-b (fs/file global "b.md")]
          (fs/create-dirs global)
          (write-memory-file-at! file-a (- now-ms (* 365 86400000)))
          (write-memory-file-at! file-b (- now-ms (* 30 86400000)))
          (write-ledger! root {(str (fs/absolutize file-a))
                               {:reads 60 :last-read-at (- now-ms (* 60 86400000))}
                               (str (fs/absolutize file-b))
                               {:reads 10 :last-read-at (- now-ms (* 1 86400000))}})
          (let [entries (usage/ranked-memories db {:now-ms now-ms})
                score-a (@#'usage/usage-score
                         (first (filter #(= "a.md" (fs/file-name (:path %))) entries))
                         now-ms)
                score-b (@#'usage/usage-score
                         (first (filter #(= "b.md" (fs/file-name (:path %))) entries))
                         now-ms)]
            (is (< 0.5 score-a 0.6) (str "A scores ~0.56, got " score-a))
            (is (< 2.6 score-b 2.75) (str "B scores ~2.69, got " score-b))
            (is (> score-b score-a))
            (is (= ["b.md" "a.md"] (mapv #(fs/file-name (:path %)) entries))
                "the higher-scoring entry sorts first")
            (is (= [score-b score-a] (mapv :score entries))
                "each entry keeps its score for the index selection")))))))

(deftest ranked-memories-stale-habit-fades-below-fresh-test
  (testing "log damping: a stale habit (60 reads, last read 60d ago, ~0.56)
            fades below a fresh single read (~1.64) instead of squatting on top"
    (test-helper/with-memory-root
      (fn [root]
        (let [db (test-db [(test-helper/file-path "/repo/one")])
              global (fs/file root "global")
              now-ms (System/currentTimeMillis)
              habit (fs/file global "habit.md")
              once (fs/file global "once.md")]
          (fs/create-dirs global)
          (write-memory-file-at! habit (- now-ms (* 365 86400000)))
          (write-memory-file-at! once (- now-ms 86400000))
          (write-ledger! root {(str (fs/absolutize habit))
                               {:reads 60 :last-read-at (- now-ms (* 60 86400000))}
                               (str (fs/absolutize once))
                               {:reads 1 :last-read-at (- now-ms 86400000)}})
          (is (= ["once.md" "habit.md"]
                 (mapv #(fs/file-name (:path %))
                       (usage/ranked-memories db {:now-ms now-ms})))))))))

(deftest usage-score-log-damping-test
  (let [now 1755000000000
        score (fn [reads last-read-offset-days updated-offset-days at-now-ms]
                (@#'usage/usage-score
                 {:reads reads
                  :last-read-at (- at-now-ms (* last-read-offset-days 86400000))
                  :updated-at-ms (- at-now-ms (* updated-offset-days 86400000))}
                 at-now-ms))]
    (testing "a hot burst still ranks on top while it is hot"
      (is (> (score 40 0 0 now) 1.0)))
    (testing "but a 40-read burst fades below a fresh memory after ~7 weeks
              (linear would squat on top for ~110 days)"
      (let [later (+ now (* 48 86400000))]
        (is (< (score 40 48 48 later) 1.0))))
    (testing "more reads still wins, with diminishing returns:
              40 reads is NOT 8x a 5-read memory, roughly 2x"
      (let [s40 (score 40 1 30 now)
            s5 (score 5 1 30 now)
            s1 (score 1 1 30 now)]
        (is (> s40 s5 s1))
        (is (< (/ s40 s5) 3.0) (str "ratio " (/ s40 s5) " should be far below the linear 8x"))))))

(deftest ranked-memories-usage-ordering-stays-per-group-test
  (testing "a heavily-used global entry must not leapfrog an unused personal one"
    (test-helper/with-memory-root
      (fn [root]
        (let [db (test-db [(test-helper/file-path "/repo/one")])
              now-ms (System/currentTimeMillis)
              personal (fs/file root "projects"
                                (memory/project-slug (test-helper/file-path "/repo/one")))
              global (fs/file root "global")]
          (fs/create-dirs personal global)
          (write-memory-file-at! (fs/file personal "p.md") (- now-ms 86400000))
          (write-memory-file-at! (fs/file global "g.md") (- now-ms 3600000))
          (write-ledger! root {(str (fs/absolutize (fs/file global "g.md")))
                               {:reads 100 :last-read-at (- now-ms 3600000)}})
          (let [entries (usage/ranked-memories db {:now-ms now-ms})]
            (is (= ["p.md" "g.md"] (mapv #(fs/file-name (:path %)) entries))
                "usage data must not reorder across group boundaries")
            (is (= [:personal :global] (mapv :tier entries)))))))))

(deftest scan-uses-fingerprint-cache-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            global-dir (fs/file root "global")
            file-a (fs/file global-dir "a.md")
            file-b (fs/file global-dir "b.md")
            file-bad (fs/file global-dir "bad.md")]
        (write-memory-file! file-a "---\nname: A\ndescription: D\n---\n\nbody a\n")
        (write-memory-file! file-b "---\nname: B\ndescription: D\n---\n\nbody b\n")
        ;; isolate from other tests sharing the process-wide cache
        (reset! @#'memory/entry-cache* {})
        (let [parse-calls* (atom [])
              synthetic-parse (fn [f]
                                (swap! parse-calls* conj (str f))
                                (if (string/ends-with? (str f) "bad.md")
                                  {:skip "mock reason"}
                                  {:ok {:path (str f)
                                        :name "n" :description "d"
                                        :type "note" :tags [] :body ""}}))]
          (with-redefs-fn {#'memory/parse-memory-file synthetic-parse}
            (fn []
              (let [scan #(memory/scan-memories db)]
                (is (= 2 (count (scan))))
                (is (= 2 (count @parse-calls*)) "first scan parses both files")
                (scan)
                (is (= 2 (count @parse-calls*)) "unchanged files are not re-parsed")
                (write-memory-file! file-bad "---\nname: Bad\nno closing frontmatter\n")
                (scan)
                (is (= 3 (count @parse-calls*)))
                (scan)
                (is (= 3 (count @parse-calls*))
                    "failed parses are cached too — broken files don't re-warn every scan")
                (write-memory-file! file-a "---\nname: A\ndescription: D\n---\n\nan edited, longer body\n")
                (is (= 2 (count (scan))))
                (is (= 4 (count @parse-calls*))
                    "only the edited file is re-parsed (mtime/size fingerprint changed)")))))))))

(deftest list-memories-mtime-order-per-directory-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/first/repo")
                        (test-helper/file-path "/second/repo")])
            dirs (memory/memory-dirs db)
            now-ms 1755000000000
            files (mapv (fn [{:keys [dir]}]
                          [(fs/file dir "old.md") (fs/file dir "new.md")]) dirs)]
        (is (every? #(re-matches #"repo \([0-9a-f]{8}\)" %) (map :label (butlast dirs))))
        (is (= "global" (:label (last dirs))))
        (is (apply distinct? (mapv :label dirs)) "two roots named repo get distinct labels")
        (doseq [[i [old new]] (map-indexed vector files)]
          (write-memory-file-at! old (- now-ms (* (- 10 i) 86400000)))
          (write-memory-file-at! new (- now-ms (* (- 5 i) 86400000))))
        (write-ledger! root
                       (into {} (map (fn [[old _]]
                                       [(str (fs/absolutize old))
                                        {:reads 100 :last-read-at now-ms}]) files)))
        (let [paths #(mapv (comp str fs/absolutize) %)]
          (is (= (paths (mapcat identity files))
                 (mapv :path (usage/ranked-memories db {:now-ms now-ms})))
              "older highly read files lead each prompt scan group")
          (is (= (paths (mapcat reverse files))
                 (mapv :path (memory/list-memories db)))
              "list uses actual mtime and keeps same-label roots separate"))))))

(deftest list-memories-shape-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            global (fs/file root "global")]
        (write-memory-file! (fs/file global "a.md")
                            "---\nname: A\ndescription: D\ntype: fact\ntags: x, y\n---\n\nbody\n")
        (let [item (first (memory/list-memories db))]
          (is (= [:path :tier :name :description :type :tags :label :mtime-ms]
                 (vec (keys item))))
          (is (= "global" (:tier item)))
          (is (= "A" (:name item)))
          (is (= "D" (:description item)))
          (is (= "fact" (:type item)))
          (is (= ["x" "y"] (:tags item)))
          (is (= "global" (:label item)))
          (is (number? (:mtime-ms item)))
          (is (string? (:path item))))))))

(deftest pre-create-dirs-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])]
        (memory/pre-create-dirs! db)
        (is (fs/directory? (fs/file root "global")))
        (is (fs/directory? (fs/file root "projects"
                                    (memory/project-slug (test-helper/file-path "/repo/one")))))
        (testing "idempotent"
          (memory/pre-create-dirs! db)
          (is (fs/directory? (fs/file root "global"))))
        (testing "nothing repo-local is created"
          (is (not (fs/exists? (fs/file (test-helper/file-path "/repo/one") ".eca")))))
        (testing "never throws, even on malformed workspace URIs"
          (memory/pre-create-dirs! {:workspace-folders [{:uri "bad uri with spaces"}]}))))))
