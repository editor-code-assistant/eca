(ns eca.features.memory.render-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.memory.render :as render]
   [eca.shared :as shared]
   [eca.test-helper :as test-helper]))

(set! *warn-on-reflection* true)

(defn ^:private days-ago ^long [^long now-ms ^long days]
  (- now-ms (* days 86400000)))

(def now 1755000000000) ;; fixed instant for deterministic tests

(defn ^:private entry [& {:as overrides}]
  (merge {:name "Docker IPv6"
          :type "gotcha"
          :label "one"
          :description "CI fails on Alpine with localhost"
          :tags ["docker" "ci"]
          :updated-at-ms now
          :path "/tmp/docker-ipv6.md"}
         overrides))

(defn ^:private dirs []
  [{:dir "/home/u/.config/eca/memory/global" :tier :global :label "global"}
   {:dir "/home/u/.config/eca/memory/projects/-repo" :tier :personal :label "repo"}])

(deftest age-in-days-test
  (testing "whole 24-hour periods, never negative"
    (is (= 0 (render/age-in-days now now)))
    (is (= 0 (render/age-in-days (- now 3600000) now))
        "less than a day rounds to 0")
    (is (= 1 (render/age-in-days (days-ago now 1) now)))
    (is (= 43 (render/age-in-days (days-ago now 43) now)))
    (is (= 0 (render/age-in-days (+ now 5000) now))
        "future dates clamp to 0")
    (is (nil? (render/age-in-days nil now)))))

(deftest index-context-entry-line-format-test
  (let [ctx (render/index-context {:entries [(entry)]
                                   :max-entries 10
                                   :max-tokens 10000}
                                  now)]
    (is (string/includes? (:content ctx)
                          "- Docker IPv6 [gotcha] (updated 0d ago) — CI fails on Alpine with localhost"))
    (testing "entries are grouped under one heading per directory, no repeated source slug per line"
      (is (string/includes? (:content ctx) "\n## one\n"))
      (is (not (string/includes? (:content ctx) "source:"))))
    (testing "tags ride a per-group vocabulary line, not per-entry lines"
      (is (string/includes? (:content ctx) "Tags in use: ci, docker")
          "equal frequencies sort alphabetically"))))

(deftest index-context-tags-summary-test
  (testing "distinct tags sorted by frequency (most-used first, alphabetical tiebreak)"
    (let [ctx (render/index-context {:entries [(entry :tags ["b" "a" "common"])
                                               (entry :name "Other" :path "/tmp/other.md" :tags ["common"])]
                                     :max-entries 10
                                     :max-tokens 10000}
                                    now)]
      (is (string/includes? (:content ctx) "Tags in use: common(2), a, b")
          "reused tags carry their count, singletons stay bare")))
  (testing "long vocabularies are hard-capped"
    (let [many-tags (mapv #(format "frequent-tag-%02d" %) (range 30))
          ctx (render/index-context {:entries [(entry :tags many-tags)]
                                     :max-entries 10
                                     :max-tokens 10000}
                                    now)
          line (some #(when (string/starts-with? % "Tags in use: ") %)
                     (string/split-lines (:content ctx)))]
      (is (some? line))
      (is (<= (count line) 100) "one bounded vocabulary line, no matter how large the tag set")
      (is (string/ends-with? line "…") "the elided tail is signalled")))
  (testing "entries without tags render no vocabulary line"
    (let [ctx (render/index-context {:entries [(entry :tags [])]
                                     :max-entries 10
                                     :max-tokens 10000}
                                    now)]
      (is (not (string/includes? (:content ctx) "Tags in use:"))))))

(deftest index-context-grouping-test
  ;; scan-memories delivers entries already partitioned per directory in
  ;; tier order and usage-sorted within each group; rendering must keep
  ;; that order (re-sorting would discard the usage signal).
  (let [entries [(entry :name "OldRepo" :path "/tmp/old-repo.md" :label "repo"
                        :updated-at-ms (days-ago now 5))
                 (entry :name "NewGlobal" :path "/tmp/new-global.md" :label "global"
                        :updated-at-ms (days-ago now 10))
                 (entry :name "OldGlobal" :path "/tmp/old-global.md" :label "global"
                        :updated-at-ms (days-ago now 1))]
        content (:content (render/index-context {:entries entries
                                                 :max-entries 10
                                                 :max-tokens 10000}
                                                now))]
    (testing "one heading per directory, group order taken from the input"
      (is (string/includes? content "\n## repo\n"))
      (is (string/includes? content "\n## global\n"))
      (is (< (.indexOf ^String content "\n## repo\n")
             (.indexOf ^String content "\n## global\n"))))
    (testing "within-group order is kept as given, NOT re-sorted by mtime"
      (is (< (.indexOf ^String content "- NewGlobal")
             (.indexOf ^String content "- OldGlobal"))
          "NewGlobal is older but comes first in the (usage-ordered) input"))
    (testing "the flat \"Memories:\" preamble is gone"
      (is (not (string/includes? content "Memories:"))))))

(deftest index-context-description-truncation-test
  (testing "descriptions are single-lined and truncated at 120 chars (enforcing the guidance rule)"
    (let [long-desc (str (apply str (repeat 150 "word "))
                         "\nsecond line\n\nthird   line")
          ctx (render/index-context {:entries [(entry :description long-desc)]
                                     :max-entries 10
                                     :max-tokens 10000}
                                    now)
          line (some #(when (string/starts-with? % "- Docker IPv6") %)
                     (string/split-lines (:content ctx)))]
      (is (some? line))
      (is (not (string/includes? line "\n")) "truly one index line")
      (is (string/includes? line "…") "truncated with ellipsis")
      (is (string/ends-with? line "…"))
      (is (<= (count line) 200) "prefix (~60 chars) + capped description (120)")))
  (testing "short descriptions are untouched"
    (let [ctx (render/index-context {:entries [(entry :description "short desc")]
                                     :max-entries 10
                                     :max-tokens 10000}
                                    now)]
      (is (string/includes? (:content ctx) "— short desc"))
      (is (not (string/includes? (:content ctx) "…"))))))

(deftest index-context-age-segments-test
  (testing "old entries show Nd ago"
    (let [ctx (render/index-context {:entries [(entry :tags [] :updated-at-ms (days-ago now 43))]
                                     :max-entries 10
                                     :max-tokens 10000}
                                    now)]
      (is (string/includes? (:content ctx) "updated 43d ago"))))
  (testing "entries without updated-at-ms omit the age segment"
    (let [ctx (render/index-context {:entries [(entry :updated-at-ms nil)]
                                     :max-entries 10
                                     :max-tokens 10000}
                                    now)]
      (is (string/includes? (:content ctx)
                            "- Docker IPv6 [gotcha] — CI fails on Alpine with localhost"))
      (is (not (string/includes? (:content ctx) "updated"))))))

(deftest index-context-nil-without-entries-test
  (testing "no per-turn block when there is nothing to show; write targets live in the static guidance"
    (is (nil? (render/index-context {:entries []
                                     :max-entries 10
                                     :max-tokens 10000}
                                    now)))
    (is (nil? (render/index-context {:entries nil
                                     :max-entries 10
                                     :max-tokens 10000}
                                    now)))))

(deftest dirs-block-test
  (let [text (render/dirs-block (dirs))]
    (is (string/starts-with? text "Memory directories:"))
    (is (string/includes? text
                          "- /home/u/.config/eca/memory/global (global) — Global (personal, all projects)"))
    (is (string/includes? text
                          (str "- /home/u/.config/eca/memory/projects/-repo (repo)"
                               " — Personal per-project (default write target)")))
    (is (string/includes? text "already exist: use your file tools directly")
        "the pre-created note travels with the dir listing"))
  (testing "nil without dirs"
    (is (nil? (render/dirs-block [])))
    (is (nil? (render/dirs-block nil))))
  (testing "the per-turn index does NOT carry the dir listing"
    (let [ctx (render/index-context {:entries [(entry)]
                                     :max-entries 10
                                     :max-tokens 10000}
                                    now)]
      (is (string/starts-with? (:content ctx) "## Memory"))
      (is (not (string/includes? (:content ctx) "Memory directories:")))
      (is (not (string/includes? (:content ctx) ".config/eca/memory"))))))

(deftest index-context-max-entries-caps-test
  (let [entries (mapv (fn [i] (entry :name (str "E" i) :path (str "/tmp/e" i ".md")))
                      (range 5))
        ctx (render/index-context {:entries entries
                                   :max-entries 2
                                   :max-tokens 100000}
                                  now)]
    (is (= 2 (:item-count ctx)))
    (is (= 5 (:total-count ctx)))
    (is (= ["E0" "E1"] (mapv :name (:items ctx))))
    (is (= [{:name "E0" :type "gotcha" :source "one" :path "/tmp/e0.md"}
            {:name "E1" :type "gotcha" :source "one" :path "/tmp/e1.md"}]
           (:items ctx)))
    (is (string/includes? (:content ctx)
                          "Showing 2 of 5 memories. More exist but are not listed here. If a task needs knowledge you expect to be stored but which is not listed above (e.g. no matching topic is visible), Grep the memory directories for that topic keyword; do not open memory files one by one."))
    (is (not (string/includes? (:content ctx) "- E2 [")))))

(deftest index-context-max-tokens-caps-test
  (let [entries (mapv (fn [i] (entry :name (str "E" i)
                                     :description (apply str (repeat 80 "x"))
                                     :path (str "/tmp/e" i ".md")))
                      (range 20))
        ctx (render/index-context {:entries entries
                                   :max-entries 100
                                   :max-tokens 300}
                                  now)]
    (is (<= (:tokens ctx) 300) "whole block stays within the token budget")
    (is (< 0 (:item-count ctx) 20) "entries are truncated, not dropped")
    (is (= 20 (:total-count ctx)))
    (is (string/includes? (:content ctx) "Showing "))
    (is (= (:tokens ctx)
           (:tokens (render/index-context {:entries entries
                                           :max-entries 100
                                           :max-tokens 300}
                                          now)))
        "rendering is deterministic")))

(deftest index-context-small-budgets-test
  (doseq [budget [0 1 2 10]
          max-entries [0 1 10]]
    (is (nil? (render/index-context {:entries [(entry)]
                                     :max-entries max-entries
                                     :max-tokens budget} now))))
  (doseq [entries [nil []]
          budget [0 1 10000]]
    (is (nil? (render/index-context {:entries entries :max-tokens budget} now))))
  (testing "a complete block fits at its exact estimated budget"
    (doseq [max-entries [0 1 2]]
      (let [opts {:entries [(entry) (entry :name "Other")]
                  :max-entries max-entries}
            full (render/index-context opts now)
            budget (shared/estimate-tokens (:content full))]
        (is (= full (render/index-context (assoc opts :max-tokens budget) now)))
        (let [smaller (render/index-context (assoc opts :max-tokens (dec budget)) now)]
          (is (or (nil? smaller) (<= (:tokens smaller) (dec budget))))))))
  (testing "write guidance remains available without an index block"
    (is (string/includes? (render/guidance-text {:write-mode "agent" :dirs (dirs)})
                          "Memory directories:"))))

(deftest index-context-defaults-now-test
  (let [ctx (render/index-context {:entries [(entry :updated-at-ms (System/currentTimeMillis))]
                                   :max-entries 10
                                   :max-tokens 10000})]
    (is (string/includes? (:content ctx) "## Memory"))
    (is (string/includes? (:content ctx) "updated 0d ago"))))

(deftest guidance-text-mode-test
  (doseq [[mode expected] [[nil "AUTO"]
                           ["agent" "AUTO"]
                           ["explicit" "ASK"]
                           ["unknown" "ASK"]]]
    (testing (str "mode: " mode)
      (is (= expected
             (render/guidance-text
              {:write-mode mode
               :template "{% if proactive %}AUTO{% else %}ASK{% endif %}"}))))))

(deftest guidance-text-dirs-test
  (is (= (str "GUIDANCE\n\n" (render/dirs-block (dirs)))
         (render/guidance-text {:template "GUIDANCE" :dirs (dirs)}))))

(deftest guidance-text-builtin-test
  (let [template (slurp (io/resource "prompts/memory_guidance.md"))]
    (doseq [mode ["agent" "explicit"]]
      (let [rendered (render/guidance-text {:write-mode mode :template template})]
        (is (not (string/blank? rendered)))
        (doseq [fallback [nil "" "  "]]
          (is (= rendered
                 (render/guidance-text {:write-mode mode :template fallback}))))))))

(deftest guidance-text-template-error-test
  (is (= "" (render/guidance-text {:template "{% if proactive %}"
                                   :dirs (dirs)}))))

(defn ^:private snapshot-dirs []
  [{:dir "/home/u/.config/eca/memory/projects/-repo" :tier :personal :label "repo"}
   {:dir "/home/u/.config/eca/memory/global" :tier :global :label "global"}])

(defn ^:private snapshot-entry [& {:as overrides}]
  (merge {:name "Docker IPv6"
          :type "gotcha"
          :label "repo"
          :description "CI fails on Alpine with localhost"
          :updated-at-ms (days-ago now 43)
          :path "/home/u/.config/eca/memory/projects/-repo/docker-ipv6.md"
          :reads 3
          :last-read-at (days-ago now 5)}
         overrides))

(deftest consolidate-snapshot-text-test
  (let [text (render/consolidate-snapshot-text
              {:dirs (snapshot-dirs)
               :entries [(snapshot-entry)
                         (snapshot-entry :name "Team notes"
                                         :type "note"
                                         :label "global"
                                         :tier :global
                                         :path "/home/u/.config/eca/memory/global/team.md"
                                         :updated-at-ms (days-ago now 10)
                                         :reads 0
                                         :last-read-at nil)]}
              now)]
    (testing "lists every memory dir with its tier label"
      (is (string/includes? text "Memory directories:"))
      (is (string/includes? text "- /home/u/.config/eca/memory/projects/-repo (repo)"))
      (is (string/includes? text "- /home/u/.config/eca/memory/global (global)")))
    (testing "renders usage lines grouped per dir in scan order"
      (is (string/includes? text "Usage snapshot:"))
      (is (string/includes? text "**/home/u/.config/eca/memory/projects/-repo (repo)**"))
      (is (string/includes? text "- Docker IPv6 [gotcha] (docker-ipv6.md) — age 43d, reads 3, last read 5d ago"))
      (is (string/includes? text "**/home/u/.config/eca/memory/global (global)**"))
      (is (< (.indexOf text "**/home/u/.config/eca/memory/projects/-repo (repo)**")
             (.indexOf text "**/home/u/.config/eca/memory/global (global)**"))
          "personal group precedes the global group, matching scan order"))
    (testing "a never-read entry shows zeros rather than being special-cased"
      (is (string/includes? text "- Team notes [note] (team.md) — age 10d, reads 0, last read never")))
    (testing "tags render in the snapshot so consolidation can cluster by topic, even duplicated ones"
      (is (string/includes? (render/consolidate-snapshot-text
                             {:dirs (snapshot-dirs)
                              :entries [(snapshot-entry :tags ["docker" "alpine"])]
                              :skipped []}
                             now)
                            "; tags: docker, alpine")))
    (testing "closing note explains the usage numbers"
      (is (string/includes? text "_Note: reads and last-read ages count local `read_file` consultations only._")))))

(deftest consolidate-snapshot-text-never-read-test
  (let [text (render/consolidate-snapshot-text
              {:dirs (snapshot-dirs)
               :entries [(snapshot-entry :name "Unread note"
                                         :path "/home/u/.config/eca/memory/projects/-repo/unread.md"
                                         :reads 0
                                         :last-read-at nil
                                         :updated-at-ms now)]}
              now)]
    (is (string/includes? text "- Unread note [gotcha] (unread.md) — age 0d, reads 0, last read never"))))

(deftest consolidate-snapshot-text-relative-path-test
  (testing "nested paths retain their directories with either separator"
    (doseq [[dir path] [["C:\\memory\\repo" "C:\\memory\\repo\\docker\\setup.md"]
                       ["/memory/repo" "/memory/repo/docker/setup.md"]]]
      (is (= "docker/setup.md" (#'render/entry-relative-path dir path)))))
  (testing "missing paths degrade gracefully"
    (is (= "?" (#'render/entry-relative-path "/memory/repo" nil))))
  (testing "two nested files with the same basename remain distinct"
    (let [dir (test-helper/file-path "/memory/repo")
          text (render/consolidate-snapshot-text
                {:dirs [{:dir dir :tier :personal :label "repo"}]
                 :entries [(snapshot-entry :name "Docker"
                                           :path (test-helper/file-path "/memory/repo/docker/setup.md"))
                           (snapshot-entry :name "Java"
                                           :path (test-helper/file-path "/memory/repo/java/setup.md"))]}
                now)]
      (is (string/includes? text "(docker/setup.md)"))
      (is (string/includes? text "(java/setup.md)")))))

(deftest consolidate-snapshot-text-empty-states-test
  (testing "no entries renders an empty note but still lists the dirs"
    (let [text (render/consolidate-snapshot-text {:dirs (snapshot-dirs) :entries []} now)]
      (is (string/includes? text "- /home/u/.config/eca/memory/global (global)"))
      (is (string/includes? text "(no memory files)"))))
  (testing "no dirs degrades to a one-line note"
    (is (= "No memory directories configured."
           (render/consolidate-snapshot-text {:dirs [] :entries []} now)))))

(deftest consolidate-snapshot-text-deterministic-test
  (let [opts {:dirs (snapshot-dirs)
              :entries [(snapshot-entry) (snapshot-entry :reads 7 :last-read-at nil)]}]
    (is (= (render/consolidate-snapshot-text opts now)
           (render/consolidate-snapshot-text opts now))
        "rendering is deterministic for a fixed now-ms")))

(deftest consolidate-snapshot-text-skipped-test
  (let [text (render/consolidate-snapshot-text
              {:dirs (snapshot-dirs)
               :entries [(snapshot-entry)]
               :skipped [{:path "/home/u/.config/eca/memory/global/broken.md"
                          :reason "YAML frontmatter does not parse (boom)"}
                         {:path "/home/u/.config/eca/memory/projects/-repo/bad.md"
                          :reason "missing `description`"}]}
              now)]
    (is (string/includes? text "Skipped files (not in the index")
        "the section appears when skipped files exist")
    (is (string/includes? text "- /home/u/.config/eca/memory/global/broken.md — YAML frontmatter does not parse (boom)"))
    (is (string/includes? text "- /home/u/.config/eca/memory/projects/-repo/bad.md — missing `description`"))
    (is (string/includes? text "local `read_file` consultations only")
        "the usage note still closes the snapshot after the skipped section"))
  (let [text (render/consolidate-snapshot-text
              {:dirs (snapshot-dirs) :entries [(snapshot-entry)] :skipped []} now)]
    (is (not (string/includes? text "Skipped files"))
        "an empty skipped list renders no section"))
  (let [text (render/consolidate-snapshot-text
              {:dirs (snapshot-dirs) :entries [(snapshot-entry)]} now)]
    (is (not (string/includes? text "Skipped files"))
        "absent `skipped` key renders no section")))

(deftest consolidate-snapshot-text-default-now-test
  (let [text (render/consolidate-snapshot-text
              {:dirs (snapshot-dirs)
               :entries [(snapshot-entry :updated-at-ms (System/currentTimeMillis))]})]
    (is (string/includes? text "Memory directories:"))
    (is (string/includes? text "— age 0d, reads 3"))))