(ns eca.modules.memory.store-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [babashka.fs :as fs]
   [eca.cache :as cache]
   [eca.config :as config]
   [eca.modules.memory.store :as m.memory.store]
   [eca.shared :as shared]
   [eca.test-helper :as test-helper]))

(set! *warn-on-reflection* true)

(defn ^:private test-db [roots]
  {:workspace-folders (mapv (fn [r] {:uri (shared/filename->uri (str r))}) roots)})

(deftest config-accessors-test
  (testing "write-mode"
    (is (= "agent" (m.memory.store/write-mode {})))
    (is (= "agent" (m.memory.store/write-mode {:memory {:writeMode "agent"}})))
    (is (= "explicit" (m.memory.store/write-mode {:memory {:writeMode "explicit"}})))
    (is (= "explicit" (m.memory.store/write-mode {:memory {:writeMode "off"}}))
        "removed value coerces to the fail-safe mode")
    (is (= "explicit" (m.memory.store/write-mode {:memory {:writeMode "bogus"}}))
        "invalid values fail safe to explicit, never to autonomous writes"))
  (testing "index caps"
    (is (= 100 (m.memory.store/index-max-entries {})))
    (is (= 42 (m.memory.store/index-max-entries {:memory {:index {:maxEntries 42}}})))
    (is (= 2000 (m.memory.store/index-max-tokens {})))
    (is (= 500 (m.memory.store/index-max-tokens {:memory {:index {:maxTokens 500}}})))))

(deftest memory-root-dir-test
  (testing "respects $XDG_DATA_HOME when set"
    (with-redefs [config/get-env (fn [k] (when (= k "XDG_DATA_HOME") "/raw/data-x"))]
      (is (= (str (io/file "/raw/data-x" "eca" "memory"))
             (str (m.memory.store/memory-root-dir))))))
  (testing "falls back to <user-home>/.local/share when unset or empty"
    (doseq [env-val [nil ""]]
      (with-redefs [config/get-env (constantly env-val)
                    config/user-home (constantly (test-helper/file-path "/home/someuser"))]
        (is (= (str (io/file (test-helper/file-path "/home/someuser") ".local" "share" "eca" "memory"))
               (str (m.memory.store/memory-root-dir)))
            (str "XDG_DATA_HOME=" (pr-str env-val)))))))

(deftest project-slug-test
  (testing "readable slug with hash suffix, no leading/trailing dashes"
    (is (re-matches #".*memory-test-workspace-[0-9a-f]{8}"
                    (m.memory.store/project-slug (test-helper/file-path "/memory-test/workspace"))))
    (is (= (m.memory.store/project-slug "/a/c") (m.memory.store/project-slug "/a//b/../c")))
    (is (= (m.memory.store/project-slug (m.memory.store/abs-path "my-project"))
           (m.memory.store/project-slug "my-project")))
    (is (re-matches #".*pr-ject-[0-9a-f]{8}" (m.memory.store/project-slug "prøject"))))
  (testing "safe as a shell argument: never starts with a dash"
    (is (not (string/starts-with? (m.memory.store/project-slug "/home/akiz/x") "-"))))
  (testing "a run of other chars is one dash, and none is left before the hash"
    (is (re-matches #".*work-eca-fork-[0-9a-f]{8}"
                    (m.memory.store/project-slug (test-helper/file-path "/work/eca (fork)")))))
  (testing "hash suffix keeps distinct paths unique when readable slugs collide"
    (is (apply distinct?
               (map m.memory.store/project-slug ["/home/me/my.project" "/home/me/my project" "/home/me/my-project"]))))
  (testing "readable part is bounded even for very deep paths"
    (is (<= (count (m.memory.store/project-slug (str "/" (string/join "/" (repeat 40 "deep")))))
            (+ 64 1 8))))
  (testing "a long path keeps its end, which names the project"
    (is (re-matches #".*-monorepo-services-payments-gateway-[0-9a-f]{8}"
                    (m.memory.store/project-slug
                     (test-helper/file-path
                      "/home/someone/Code/Clojure/company/very/deeply/nested/monorepo/services/payments-gateway")))))
  (testing "the readable part is exactly the last 64 chars: the slug, so the dir of the notes, depends on it"
    (let [segment (apply str (take 70 (cycle "abcdefghij")))]
      (is (re-matches (re-pattern (str (subs segment 6) "-[0-9a-f]{8}"))
                      (m.memory.store/project-slug (test-helper/file-path (str "/repo/" segment))))))))

(deftest canonical-project-paths-test
  (test-helper/with-memory-root
    (fn [root]
      (let [project (fs/create-dirs (fs/path root "checkout"))
            alias (fs/path root "alias")
            dotted (str project "/./")]
        (try
          (is (= (m.memory.store/project-slug project) (m.memory.store/project-slug dotted)))
          (let [dirs (with-redefs [m.memory.store/workspace-paths (constantly [dotted (str project)])]
                       (m.memory.store/memory-dirs {}))]
            (is (= 2 (count dirs)) "project aliases contribute one personal directory")
            (is (= (m.memory.store/abs-path (m.memory.store/personal-dir project)) (:dir (first dirs))))
            (is (not (fs/exists? (m.memory.store/personal-dir project)))
                "resolving directories does not create them"))
          (when-not shared/windows-os?
            (fs/create-sym-link alias project)
            (is (= (m.memory.store/project-slug project) (m.memory.store/project-slug alias)))
            (is (= (m.memory.store/abs-path project) (m.memory.store/abs-path alias)))
            (is (= 2 (count (m.memory.store/memory-dirs (test-db [(str alias) (str project)]))))))
          (let [case-alias (fs/path root "CHECKOUT")]
            (when (fs/exists? case-alias)
              (is (= (m.memory.store/project-slug project) (m.memory.store/project-slug case-alias)))))
          (testing "linked worktrees sharing a canonical workspace path share one personal dir"
            (let [main (test-helper/file-path "/repo/main")
                  linked (test-helper/file-path "/repo/linked")]
              (with-redefs [cache/canonicalize-workspace-path #(if (= linked %) main %)]
                (let [dirs (m.memory.store/memory-dirs (test-db [main linked]))]
                  (is (= [:personal :global] (mapv :tier dirs)))
                  (is (= ["main" "global"] (mapv :label dirs)))))))
          (finally (fs/delete-tree root)))))))

(when-not shared/windows-os?
  (deftest links-in-memory-dirs-are-not-notes-test
    (test-helper/with-memory-root
      (fn [root]
        (let [db (test-db [])
              global (fs/create-dirs (m.memory.store/global-dir))
              elsewhere (fs/create-dirs (fs/path root "elsewhere"))
              note "---\nname: %s\ndescription: D\n---\n\nbody\n"
              alias (fs/path global "alias.md")
              outside-link (fs/path global "outside.md")
              dir-md-link (fs/path global "linked-dir.md")
              linked-dir (fs/path global "linked-dir")]
          (spit (str (fs/path global "own.md")) (format note "Own"))
          (spit (str (fs/path elsewhere "outside.md")) (format note "Outside"))
          (spit (str (fs/path elsewhere "nested.md")) (format note "Nested"))
          (fs/create-sym-link alias (fs/path global "own.md"))
          (fs/create-sym-link outside-link (fs/path elsewhere "outside.md"))
          (fs/create-sym-link dir-md-link elsewhere)
          (fs/create-sym-link linked-dir elsewhere)
          (let [{:keys [entries skipped]} (m.memory.store/scan-memories db)]
            (is (= ["Own"] (mapv :name entries))
                "only the regular file is a note: file links and linked subdirs are not read")
            (is (= #{(str alias) (str outside-link) (str dir-md-link)} (set (map :path skipped)))
                "each .md link (even one pointing to a directory) is listed under its own path")
            (is (every? #(string/starts-with? (:reason %) "not indexed: this is a link.") skipped)))
          (testing "locate-memory-path and indexable-memory-path agree with the scan"
            (is (= {:dir (str global) :path (str (fs/path global "own.md")) :link? false}
                   (m.memory.store/locate-memory-path db (str (fs/path global "own.md")))))
            (is (= (str (fs/path global "own.md"))
                   (m.memory.store/indexable-memory-path db (str (fs/path global "own.md")))))
            (doseq [link-path [alias outside-link dir-md-link]]
              (is (true? (:link? (m.memory.store/locate-memory-path db (str link-path)))) (str link-path))
              (is (nil? (m.memory.store/indexable-memory-path db (str link-path))) (str link-path)))
            (is (nil? (m.memory.store/locate-memory-path db (str (fs/path linked-dir "nested.md")))))
            (is (nil? (m.memory.store/indexable-memory-path db (str (fs/path linked-dir "nested.md"))))))))))

  (deftest linked-memory-dir-is-scanned-test
    (test-helper/with-memory-root
      (fn [root]
        (let [real (fs/create-dirs (fs/path root "synced-global"))
              link-root (m.memory.store/global-dir)
              db (test-db [])]
          (fs/create-sym-link link-root real)
          (spit (str (fs/path real "note.md")) "---\nname: Synced\ndescription: D\n---\n\nbody\n")
          (let [{:keys [entries skipped]} (m.memory.store/scan-memories db)
                canonical-note (m.memory.store/abs-path (fs/path real "note.md"))]
            (is (= [["Synced" canonical-note]]
                   (mapv (juxt :name :path) entries))
                "a linked memory dir is resolved before the scan, so its notes are read")
            (is (empty? skipped))
            (is (= {:dir (m.memory.store/abs-path real) :path canonical-note :link? false}
                   (m.memory.store/locate-memory-path db (str (fs/path link-root "note.md"))))
                "accessing a note through a linked whole memory dir is not flagged as a link")
            (is (= canonical-note
                   (m.memory.store/indexable-memory-path db (str (fs/path link-root "note.md")))))))))))

(deftest non-regular-file-is-skipped-test
  (test-helper/with-memory-root
    (fn [_root]
      (let [note (fs/path (fs/create-dirs (m.memory.store/global-dir)) "pipe.md")
            regular-file? fs/regular-file?]
        (spit (str note) "---\nname: Pipe\ndescription: D\n---\n\nbody\n")
        (with-redefs [fs/regular-file? (fn [p] (and (not= note (fs/path p)) (regular-file? p)))]
          (let [{:keys [entries skipped]} (m.memory.store/scan-memories (test-db []))]
            (is (empty? entries))
            (is (= [[(str note) "not indexed: not a regular file."]]
                   (mapv (juxt :path :reason) skipped)))))))))

(when-not shared/windows-os?
  (deftest unreadable-file-skip-reason-test
    (test-helper/with-memory-root
      (fn [_root]
        (let [note (fs/path (fs/create-dirs (m.memory.store/global-dir)) "locked.md")]
          (spit (str note) "---\nname: Locked\ndescription: D\n---\n\nbody\n")
          (fs/set-posix-file-permissions note "-w-------")
          (try
            ;; A root user can read the file anyway; assert that explicitly so
            ;; Kaocha never reports zero assertions.
            (if (fs/readable? note)
              (is (fs/readable? note))
              (let [{:keys [skipped]} (m.memory.store/scan-memories (test-db []))]
                (is (= [(m.memory.store/abs-path note)] (mapv :path skipped)))
                (is (string/starts-with? (:reason (first skipped)) "cannot read file:")
                    "a read error is not reported as a YAML problem")))
            (finally
              (fs/set-posix-file-permissions note "rw-------"))))))))

(deftest memory-dirs-ordering-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")
                         (test-helper/file-path "/repo/two")])
            dirs (m.memory.store/memory-dirs db)]
        (is (= [:personal :personal :global] (mapv :tier dirs))
            "two tiers only: personal per root, then global")
        (is (= ["one" "two" "global"] (mapv :label dirs)))
        (is (= (str (fs/file root "projects"
                             (m.memory.store/project-slug (test-helper/file-path "/repo/one"))))
               (:dir (first dirs))))
        (is (= (str (fs/file root "global")) (:dir (last dirs))))
        (is (every? fs/absolute? (map :dir dirs)))))))

(deftest memory-dirs-unique-labels-test
  (test-helper/with-memory-root
    (fn [_root]
      (let [a (test-helper/file-path "/a/app")
            b (test-helper/file-path "/b/app")
            hash-of #(last (string/split (m.memory.store/project-slug %) #"-"))]
        (testing "two roots with one basename get the hash of their root"
          (let [dirs (m.memory.store/memory-dirs (test-db [a b (test-helper/file-path "/c/other")]))]
            (is (= [(str "app (" (hash-of a) ")") (str "app (" (hash-of b) ")") "other" "global"]
                   (mapv :label dirs)))
            (is (string/ends-with? (:dir (first dirs)) (hash-of a))
                "the suffix matches the dir name")))
        (testing "a root named like the global tier gets the hash too"
          (let [g (test-helper/file-path "/work/global")]
            (is (= [(str "global (" (hash-of g) ")") "global"]
                   (mapv :label (m.memory.store/memory-dirs (test-db [g])))))))))))

(deftest locate-memory-path-test
  (test-helper/with-memory-root
    (fn [root]
      (let [workspace-root (fs/create-temp-dir)
            db (test-db [(str workspace-root)])
            slug (m.memory.store/project-slug (str workspace-root))]
        (testing "a path in the personal dir or the global dir is located"
          (is (some? (m.memory.store/locate-memory-path db (str (fs/file root "projects" slug "a.md")))))
          (is (some? (m.memory.store/locate-memory-path db (str (fs/file root "global" "g.md"))))))
        (testing "a repo-local .eca path is not"
          (is (nil? (m.memory.store/locate-memory-path db (str (fs/file workspace-root ".eca" "memory" "team.md"))))))
        (testing "a path outside any memory dir is not"
          (is (nil? (m.memory.store/locate-memory-path db (str (fs/file workspace-root "src" "main.clj"))))))
        (testing "nothing is a memory path without a path"
          (is (nil? (m.memory.store/locate-memory-path db nil))))
        (fs/delete-tree workspace-root)))))

(defn ^:private write-memory-file!
  [f content]
  (fs/create-dirs (fs/parent f))
  (spit (str f) content))

(defn ^:private write-memory-file-at!
  "Write a valid memory file and pin its mtime to `mtime-ms`."
  [f mtime-ms]
  (write-memory-file! f "---\nname: n\ndescription: d\n---\n\nbody\n")
  (.setLastModified (io/file (str f)) ^long mtime-ms))

(defn ^:private content-error [content]
  (m.memory.store/note-content-error content))

(deftest note-content-error-test
  (testing "valid contents return nil"
    (doseq [content ["---\nname: A\ndescription: D\n---\n\nbody\n"
                     "---\nname: A\ndescription: D\n---\n"
                     "---\nname: \" A \"\ndescription: \" D \"\n---\n\nbody\n"
                     "---\nname: A\ndescription: D\ntype: gotcha\ntags:\n  - docker\n  - ci\n---\n\nBody text here.\n"
                     "---\nname: A\ndescription: D\ntags: alpha, beta\n---\n\nbody\n"]]
      (is (nil? (content-error content)) content)))
  (testing "unparseable frontmatter"
    (let [error (content-error "---\nname: [unclosed\n---\n")]
      (is (string/includes? error "YAML frontmatter does not parse"))
      (is (string/includes? error "fix and retry")))
    (is (string/includes? (content-error "---\nname: A\nno closing frontmatter\n")
                          "Unclosed YAML frontmatter")))
  (testing "non-mapping frontmatter"
    (is (string/includes? (content-error "---\n- just\n- a\n- list\n---\n")
                          "YAML frontmatter must be a mapping")))
  (testing "missing or blank required keys"
    (is (string/includes? (content-error "---\ndescription: D\n---\n\nbody\n") "`name`"))
    (is (string/includes? (content-error "---\nname: A\n---\n\nbody\n") "`description`"))
    (is (string/includes? (content-error "no frontmatter at all") "`name`")
        "content without any frontmatter is missing `name`")
    (is (string/includes? (content-error "---\nname: A\ndescription:   \n---\n") "`description`")
        "blank description is rejected")
    (is (string/includes? (content-error "---\nname:   \ndescription: D\n---\n") "`name`")
        "blank name is rejected")))

(defn ^:private frontmatter-error
  [frontmatter]
  (content-error (str "---\n" frontmatter "\n---\n\nbody\n")))

(deftest note-content-error-non-text-fields-test
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
  (testing "a name or description that YAML reads as a date, a number or a boolean"
    (doseq [[frontmatter field] [["name: 2026-11-12\ndescription: D" "name"]
                                 ["name: 1.10\ndescription: D" "name"]
                                 ["name: A\ndescription: yes" "description"]
                                 ["name: A\ndescription: 0123" "description"]]]
      (is (string/includes? (str (frontmatter-error frontmatter))
                            (str "`" field "` has a value that YAML does not read as text"))
          frontmatter))
    (is (nil? (frontmatter-error "name: '2026-11-12'\ndescription: 'yes'"))
        "quoted values are text"))
  (testing "multiple problems come back in one message, one per line"
    (let [error (frontmatter-error "name: 2026-11-12\ndescription: D\ntags: [auth, 679]")]
      (is (string/includes? error "`name` has a value that YAML does not read as text"))
      (is (string/includes? error "`tags` has a value that YAML does not read as text"))
      (is (= 2 (count (string/split-lines error))))))
  (testing "the scan stays lenient: such files are still indexed"
    (test-helper/with-memory-root
      (fn [root]
        (let [db (test-db [(test-helper/file-path "/repo/one")])]
          (write-memory-file! (fs/file root "global" "lenient.md")
                              "---\nname: Lenient\ndescription: see #679\ntags: 679, ci\n---\nbody\n")
          (let [entry (first (:entries (m.memory.store/scan-memories db)))]
            (is (= "Lenient" (:name entry)))
            (is (= "see" (:description entry)) "YAML drops the comment; the scan does not reject")))))))

(deftest scan-entries-and-skipped-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            personal (fs/file root "projects"
                              (m.memory.store/project-slug (test-helper/file-path "/repo/one")))
            global (fs/file root "global")]
        (write-memory-file! (fs/file personal "sub" "docker.md")
                            "---\nname: Docker IPv6\ndescription: CI fails on localhost\ntype: gotcha\ntags:\n  - docker\n  - ci\n---\n\nBody text here.\n")
        (write-memory-file! (fs/file global "top.md")
                            "---\nname: Top fact\ndescription: A global fact\ntags: alpha, beta\n---\n\nGlobal body.\n")
        (write-memory-file! (fs/file global "NOTE.MD") "---\nname: Upper\ndescription: D\n---\n")
        (write-memory-file! (fs/file global "missing-description.md")
                            "---\nname: No description here\n---\n\nSkipped.\n")
        (write-memory-file! (fs/file global "unclosed.md")
                            "---\nname: Unclosed\nthis frontmatter never closes\n\n")
        (write-memory-file! (fs/file global "notes.txt") "not scanned")
        (write-memory-file! (fs/file global "README") "no extension, not scanned")
        (let [{:keys [entries skipped]} (m.memory.store/scan-memories db)]
          (testing "valid .md files (any case of the extension, also in subdirectories) are scanned"
            (let [by-name (into {} (map (juxt :name identity)) entries)
                  docker (by-name "Docker IPv6")
                  top (by-name "Top fact")]
              (is (= #{"Docker IPv6" "Top fact" "Upper"} (set (keys by-name))))
              (is (= [:personal "one"] ((juxt :tier :label) docker)))
              (is (= [:global "global"] ((juxt :tier :label) top)))
              (is (= "gotcha" (:type docker)))
              (is (= ["docker" "ci"] (:tags docker)) "YAML list tags normalize to a vector")
              (is (= "Body text here." (:body docker)))
              (is (= "note" (:type top)) "absent type defaults to note")
              (is (= ["alpha" "beta"] (:tags top)) "comma-separated tags normalize to a vector")
              (is (string? (:path docker)))
              (is (number? (:mtime-ms docker)))
              (is (= (:mtime-ms docker) (:updated-at-ms docker))
                  "the date starts as the file time; the usage ledger refines it")))
          (testing "invalid .md files are skipped with a reason; other files are ignored"
            (let [reason-of (fn [file]
                              (:reason (first (filter #(string/ends-with? (:path %) file) skipped))))]
              (is (= 2 (count skipped)))
              (is (= #{[:global "global"]} (set (map (juxt :tier :label) skipped))))
              (is (string/includes? (reason-of "missing-description.md") "`description`")
                  "skip reason names the missing key")
              (is (string/starts-with? (reason-of "unclosed.md")
                                       "Invalid memory file content: YAML frontmatter does not parse")
                  "skip reason surfaces the YAML problem, not a read error"))))))))

(deftest unknown-memory-metadata-test
  (test-helper/with-memory-root
    (fn [root]
      (let [frontmatter "---\nname: Custom\ndescription: D\ntype: custom-kind\nextra-field:\n  nested: true\n"]
        (is (nil? (content-error (str frontmatter "---\n")))
            "unknown fields and types pass the write check")
        (doseq [[tags expected] [["tags: 2025" []] ["tags: [release, 2025]" ["release"]]]]
          (let [content (str frontmatter tags "\n---\n")]
            (is (some? (content-error content))
                "a number tag does not: YAML reads it as a number and the tag would be lost")
            (write-memory-file! (fs/file root "global" "custom.md") content)
            (let [item (first (:entries (m.memory.store/scan-memories {})))]
              (is (= "Custom" (:name item)))
              (is (= "custom-kind" (:type item)))
              (is (= expected (:tags item)) "the scan stays lenient and drops only the number tag"))))))))

(deftest normalize-tags-test
  (testing "tags lowercase, dedupe and cap at 4 on the read path"
    (test-helper/with-memory-root
      (fn [root]
        (let [db (test-db [(test-helper/file-path "/repo/one")])
              global (fs/file root "global")]
          (write-memory-file! (fs/file global "tagged.md")
                              "---\nname: Tagged\ndescription: D\ntags: Docker, docker, , CI, ci, alpha, beta, gamma,\n---\n\nbody\n")
          (let [entry (first (:entries (m.memory.store/scan-memories db)))]
            (is (= ["docker" "ci" "alpha" "beta"] (:tags entry))
                "casing variants collapse, dupes and empty items drop, only the first 4 survive")))))))

(deftest newest-first-order-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            global (fs/file root "global")
            now-ms (System/currentTimeMillis)
            names #(mapv (comp fs/file-name :path) %)]
        (fs/create-dirs global)
        (doseq [[n delta] [["old.md" 300000] ["mid.md" 200000] ["new.md" 100000]]]
          (write-memory-file-at! (fs/file global n) (- now-ms delta)))
        (is (= ["new.md" "mid.md" "old.md"] (names (:entries (m.memory.store/scan-memories db))))
            "the plain scan lists the newest file first")))))

(deftest pre-create-dirs-test
  (test-helper/with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])]
        (m.memory.store/pre-create-dirs! db)
        (is (fs/directory? (fs/file root "global")))
        (is (fs/directory? (fs/file root "projects"
                                    (m.memory.store/project-slug (test-helper/file-path "/repo/one")))))
        (testing "idempotent"
          (m.memory.store/pre-create-dirs! db)
          (is (fs/directory? (fs/file root "global"))))
        (testing "nothing repo-local is created"
          (is (not (fs/exists? (fs/file (test-helper/file-path "/repo/one") ".eca")))))
        (testing "never throws, even on malformed workspace URIs"
          (is (nil? (m.memory.store/pre-create-dirs! {:workspace-folders [{:uri "bad uri with spaces"}]}))))))))
