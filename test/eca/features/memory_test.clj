(ns eca.features.memory-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [babashka.fs :as fs]
   [eca.cache :as cache]
   [eca.features.memory :as memory]
   [eca.features.memory.usage :as usage]
   [eca.shared :as shared]
   [eca.test-helper :as test-helper])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)

(defn ^:private temp-dir []
  (.toFile (Files/createTempDirectory "eca-memory-test" (make-array FileAttribute 0))))

(defn ^:private with-memory-root [f]
  (let [root (temp-dir)]
    (with-redefs [memory/memory-root-dir (fn [] root)]
      (f root))))

(defn ^:private test-db [roots]
  {:workspace-folders (mapv (fn [r] {:uri (shared/filename->uri (str r))}) roots)})

(def enabled-config
  {:memory {:enabled true
            :writeMode "agent"
            :index {:maxEntries 100 :maxTokens 1000}}})

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

(deftest project-slug-test
  (testing "readable slug with hash suffix, no leading/trailing dashes"
    (is (re-matches #"home-akiz-Code-Clojure-eca-[0-9a-f]{8}"
                    (memory/project-slug "/home/akiz/Code/Clojure/eca")))
    (is (re-matches #"a-b-c-[0-9a-f]{8}" (memory/project-slug "/a//b/../c")))
    (is (re-matches #"my-project-[0-9a-f]{8}" (memory/project-slug "my-project")))
    (is (re-matches #"pr-ject-[0-9a-f]{8}" (memory/project-slug "prøject")))
    (is (re-matches #"C-Users-akiz-proj-[0-9a-f]{8}" (memory/project-slug "C:\\Users\\akiz\\proj"))))
  (testing "safe as a shell argument: never starts with a dash"
    (is (not (string/starts-with? (memory/project-slug "/home/akiz/x") "-")))
    (testing "root-only path degrades to just the hash"
      (is (re-matches #"[0-9a-f]{8}" (memory/project-slug "/")))))
  (testing "deterministic"
    (is (= (memory/project-slug "/home/akiz/eca") (memory/project-slug "/home/akiz/eca"))))
  (testing "hash suffix keeps distinct paths unique when readable slugs collide"
    (is (apply distinct?
               (map memory/project-slug ["/home/me/my.project" "/home/me/my project" "/home/me/my-project"]))))
  (testing "readable part is bounded even for very deep paths"
    (is (<= (count (memory/project-slug (str "/" (string/join "/" (repeat 40 "deep")))))
            (+ 64 1 8))))
  (testing "worktree canonicalization is delegated to cache/canonicalize-workspace-path"
    (with-redefs [cache/canonicalize-workspace-path (fn [_] "/main/root")]
      (is (re-matches #"main-root-[0-9a-f]{8}" (memory/project-slug "/some/worktree")))))
  (testing "GOLDEN identity lock: these exact slugs name existing on-disk memory dirs"
    ;; Any change to canonicalize-workspace-path, short-path-hash, or the
    ;; slug layout that alters one of these values silently orphans every
    ;; memory store shipped before the change. Change deliberately, with a
    ;; migration, and update these vectors as part of it. If a failure here
    ;; is a surprise, the change was accidental: revert it instead.
    (is (= "home-akiz-Code-Clojure-eca-3e80ad28"
           (memory/project-slug "/home/akiz/Code/Clojure/eca")))
    (is (= "repo-one-7c8ddcf8" (memory/project-slug "/repo/one")))
    (is (= "8a5edab2" (memory/project-slug "/")))
    (is (= "C-Users-akiz-proj-4567bea5" (memory/project-slug "C:\\Users\\akiz\\proj")))))

(deftest workspace-paths-test
  (is (= [(test-helper/file-path "/repo/one") (test-helper/file-path "/repo/two")]
         (memory/workspace-paths (test-db [(test-helper/file-path "/repo/one")
                                           (test-helper/file-path "/repo/two")]))))
  (is (= [] (memory/workspace-paths {})))
  (is (= [] (memory/workspace-paths {:workspace-folders []}))))

(deftest memory-dirs-ordering-test
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")
                         (test-helper/file-path "/repo/two")])
            dirs (memory/memory-dirs db enabled-config)]
        (is (= [:personal :personal :global] (mapv :tier dirs))
            "two tiers only: personal per root, then global")
        (is (= ["one" "two" "global"] (mapv :label dirs)))
        (is (= (str (fs/file root "projects"
                             (memory/project-slug (test-helper/file-path "/repo/one"))))
               (:dir (first dirs))))
        (is (= (str (fs/file root "global")) (:dir (last dirs))))
        (is (every? fs/absolute? (map :dir dirs)))))))

(defn ^:private write-memory-file!
  [f content]
  (fs/create-dirs (fs/parent f))
  (spit (str f) content))

;; Isolate from other tests sharing the process-wide usage ledger state; the
;; ledger is read from <memory-root>/usage.edn by scan-memories' enrichment.
(defn ^:private reset-ledger! []
  (reset! @#'usage/ledger* {})
  (reset! @#'usage/loaded?* false))

(defn ^:private write-ledger!
  "Replace the on-disk usage ledger (and the in-memory one) with `ledger`."
  [root ledger]
  (reset-ledger!)
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

(deftest scan-memories-leniency-and-shape-test
  (with-memory-root
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
        (let [entries (memory/scan-memories db enabled-config)]
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

(deftest unknown-memory-metadata-test
  (with-memory-root
    (fn [root]
      (let [content "---\nname: Custom\ndescription: D\ntype: custom-kind\nextra-field:\n  nested: true\ntags: 42\n---\n"]
        (is (nil? (memory/memory-content-error content)))
        (write-memory-file! (fs/file root "global" "custom.md") content)
        (let [item (first (memory/list-memories {} enabled-config))]
          (is (= "Custom" (:name item)))
          (is (= "custom-kind" (:type item)))
          (is (= [] (:tags item))))))))

(deftest normalize-tags-test
  (testing "tags lowercase, dedupe and cap at 3 on the read path"
    (with-memory-root
      (fn [root]
        (let [db (test-db [(test-helper/file-path "/repo/one")])
              global (fs/file root "global")]
          (write-memory-file! (fs/file global "tagged.md")
                              "---\nname: Tagged\ndescription: D\ntags: Docker, docker, CI, ci, alpha, beta, gamma\n---\n\nbody\n")
          (let [entry (first (memory/scan-memories db enabled-config))]
            (is (= ["docker" "ci" "alpha"] (:tags entry))
                "casing variants collapse, dupes drop, only the first 3 survive")))))))

(deftest skipped-files-reports-reasons-test
  (with-memory-root
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
        (let [skipped (memory/skipped-files db enabled-config)
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
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            global (fs/file root "global")
            now-ms (System/currentTimeMillis)]
        (reset-ledger!)
        (fs/create-dirs global)
        (doseq [[n delta] [["old.md" 300000] ["mid.md" 200000] ["new.md" 100000]]]
          (let [f (fs/file global n)]
            (spit (str f) (str "---\nname: " n "\ndescription: d\n---\n\nbody\n"))
            (.setLastModified (io/file (str f)) ^long (- now-ms delta))))
        (is (= ["new.md" "mid.md" "old.md"]
               (mapv :name (memory/scan-memories db enabled-config {:now-ms now-ms}))))))))

(deftest scan-memories-zero-usage-preserves-mtime-order-test
  (testing "with an empty usage ledger the score degenerates to the mtime term,
            so ordering is exactly the plain mtime-desc order"
    (with-memory-root
      (fn [root]
        (let [db (test-db [(test-helper/file-path "/repo/one")])
              global (fs/file root "global")
              now-ms (System/currentTimeMillis)]
          (write-ledger! root {})
          (fs/create-dirs global)
          (doseq [[n delta] [["old.md" 300000] ["mid.md" 200000] ["new.md" 100000]]]
            (write-memory-file-at! (fs/file global n) (- now-ms delta)))
          (let [entries (memory/scan-memories db enabled-config {:now-ms now-ms})]
            (is (= ["new.md" "mid.md" "old.md"]
                   (mapv #(fs/file-name (:path %)) entries)))
            (is (every? #(= 0 (:reads %)) entries))
            (is (every? #(nil? (:last-read-at %)) entries))))))))

(deftest scan-memories-usage-score-ordering-test
  (testing "the design example, damped: a year-old 60-read entry last read 60d ago
            (~0.56) loses to a month-old 10-read entry last read yesterday (~2.69)"
    (with-memory-root
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
          (let [entries (memory/scan-memories db enabled-config {:now-ms now-ms})
                score-a (@#'memory/usage-score
                         (first (filter #(= "a.md" (fs/file-name (:path %))) entries))
                         now-ms)
                score-b (@#'memory/usage-score
                         (first (filter #(= "b.md" (fs/file-name (:path %))) entries))
                         now-ms)]
            (is (< 0.5 score-a 0.6) (str "A scores ~0.56, got " score-a))
            (is (< 2.6 score-b 2.75) (str "B scores ~2.69, got " score-b))
            (is (> score-b score-a))
            (is (= ["b.md" "a.md"] (mapv #(fs/file-name (:path %)) entries))
                "the higher-scoring entry sorts first")))))))

(deftest scan-memories-stale-habit-fades-below-fresh-test
  (testing "log damping: a stale habit (60 reads, last read 60d ago, ~0.56)
            fades below a fresh single read (~1.64) instead of squatting on top"
    (with-memory-root
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
                       (memory/scan-memories db enabled-config {:now-ms now-ms})))))))))

(deftest usage-score-log-damping-test
  (let [now 1755000000000
        score (fn [reads last-read-offset-days updated-offset-days at-now-ms]
                (@#'memory/usage-score
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

(deftest scan-memories-usage-ordering-stays-per-group-test
  (testing "a heavily-used global entry must not leapfrog an unused personal one"
    (with-memory-root
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
          (let [entries (memory/scan-memories db enabled-config {:now-ms now-ms})]
            (is (= ["p.md" "g.md"] (mapv #(fs/file-name (:path %)) entries))
                "usage data must not reorder across group boundaries")
            (is (= [:personal :global] (mapv :tier entries)))))))))

(deftest scan-uses-fingerprint-cache-test
  (with-memory-root
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
              (let [scan #(memory/scan-memories db enabled-config)]
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
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/first/repo")
                        (test-helper/file-path "/second/repo")])
            dirs (memory/memory-dirs db enabled-config)
            now-ms 1755000000000
            files (mapv (fn [{:keys [dir]}]
                          [(fs/file dir "old.md") (fs/file dir "new.md")]) dirs)]
        (is (= ["repo" "repo" "global"] (mapv :label dirs)))
        (doseq [[i [old new]] (map-indexed vector files)]
          (write-memory-file-at! old (- now-ms (* (- 10 i) 86400000)))
          (write-memory-file-at! new (- now-ms (* (- 5 i) 86400000))))
        (write-ledger! root
                       (into {} (map (fn [[old _]]
                                       [(str (fs/absolutize old))
                                        {:reads 100 :last-read-at now-ms}]) files)))
        (let [paths #(mapv (comp str fs/absolutize) %)]
          (is (= (paths (mapcat identity files))
                 (mapv :path (memory/scan-memories db enabled-config {:now-ms now-ms})))
              "older highly read files lead each prompt scan group")
          (is (= (paths (mapcat reverse files))
                 (mapv :path (memory/list-memories db enabled-config)))
              "list uses actual mtime and keeps same-label roots separate")
          (with-redefs-fn {#'memory/usage-enrich (fn [_] (throw (ex-info "Unexpected usage lookup" {})))}
            #(is (= (paths (mapcat reverse files))
                    (mapv :path (memory/list-memories db enabled-config))))))))))

(deftest list-memories-shape-test
  (with-memory-root
    (fn [root]
      (let [db (test-db [(test-helper/file-path "/repo/one")])
            global (fs/file root "global")]
        (write-memory-file! (fs/file global "a.md")
                            "---\nname: A\ndescription: D\ntype: fact\ntags: x, y\n---\n\nbody\n")
        (let [item (first (memory/list-memories db enabled-config))]
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
  (with-memory-root
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


