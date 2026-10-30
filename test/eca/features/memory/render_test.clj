(ns eca.features.memory.render-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.memory.render :as render]
   [eca.shared :as shared]
   [eca.test-helper :as test-helper]))

(set! *warn-on-reflection* true)

(defn ^:private days-ago ^long [^long now-ms ^long days]
  (- now-ms (* days 86400000)))

(def now 1755000000000) ;; fixed instant for deterministic tests

(defn ^:private date-of
  "The absolute YYYY-MM-DD date the renderer shows for a timestamp, in the
   local time zone."
  ^String [ms]
  (str (java.time.LocalDate/ofInstant (java.time.Instant/ofEpochMilli (long ms))
                                      (java.time.ZoneId/systemDefault))))

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
    (is (= 0 (#'render/age-in-days now now)))
    (is (= 0 (#'render/age-in-days (- now 3600000) now))
        "less than a day rounds to 0")
    (is (= 1 (#'render/age-in-days (days-ago now 1) now)))
    (is (= 43 (#'render/age-in-days (days-ago now 43) now)))
    (is (= 0 (#'render/age-in-days (+ now 5000) now))
        "future dates clamp to 0")
    (is (nil? (#'render/age-in-days nil now)))))

(deftest index-context-entry-line-format-test
  (let [ctx (render/index-context {:entries [(entry)]
                                   :max-entries 10
                                   :max-tokens 10000})]
    (is (string/includes? (:content ctx)
                          (str "- Docker IPv6 [gotcha] (" (date-of now) ") — CI fails on Alpine with localhost")))
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
                                     :max-tokens 10000})]
      (is (string/includes? (:content ctx) "Tags in use: common(2), a, b")
          "reused tags carry their count, singletons stay bare")))
  (testing "long vocabularies are hard-capped"
    (let [many-tags (mapv #(format "frequent-tag-%02d" %) (range 30))
          ctx (render/index-context {:entries [(entry :tags many-tags)]
                                     :max-entries 10
                                     :max-tokens 10000})
          line (some #(when (string/starts-with? % "Tags in use: ") %)
                     (string/split-lines (:content ctx)))]
      (is (some? line))
      (is (<= (count line) 140) "one bounded vocabulary line, no matter how large the tag set")
      (is (string/ends-with? line "…") "the elided tail is signalled")))
  (testing "entries without tags render no vocabulary line"
    (let [ctx (render/index-context {:entries [(entry :tags [])]
                                     :max-entries 10
                                     :max-tokens 10000})]
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
                                                 :max-tokens 10000}))]
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
                                     :max-tokens 10000})
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
                                     :max-tokens 10000})]
      (is (string/includes? (:content ctx) "— short desc"))
      (is (not (string/includes? (:content ctx) "…"))))))

(deftest index-context-age-segments-test
  (testing "the date of the last change is absolute, so a stored block cannot go stale"
    (let [ctx (render/index-context {:entries [(entry :tags [] :updated-at-ms (days-ago now 43))]
                                     :max-entries 10
                                     :max-tokens 10000})]
      (is (string/includes? (:content ctx)
                            (str "(" (date-of (days-ago now 43)) ")")))
      (is (not (string/includes? (:content ctx) "ago")))))
  (testing "entries without updated-at-ms omit the age segment"
    (let [ctx (render/index-context {:entries [(entry :updated-at-ms nil)]
                                     :max-entries 10
                                     :max-tokens 10000})]
      (is (string/includes? (:content ctx)
                            "- Docker IPv6 [gotcha] — CI fails on Alpine with localhost"))
      (is (not (string/includes? (:content ctx) "gotcha] ("))))))

(deftest index-context-nil-without-entries-test
  (testing "no block when there is nothing to show; write targets live in the static guidance"
    (is (nil? (render/index-context {:entries []
                                     :max-entries 10
                                     :max-tokens 10000})))
    (is (nil? (render/index-context {:entries nil
                                     :max-entries 10
                                     :max-tokens 10000})))))

(deftest dirs-block-test
  (let [text (render/dirs-block (dirs) {:write? true})]
    (is (string/starts-with? text "Memory directories:"))
    (is (string/includes? text
                          "- /home/u/.config/eca/memory/global (global) — Global (personal, all projects)"))
    (is (string/includes? text
                          (str "- /home/u/.config/eca/memory/projects/-repo (repo)"
                               " — Personal per-project (default write target)")))
    (is (string/includes? text (str "already exist: use your file tools directly without mkdir or ls first, "
                                    "and Grep/Read them with your normal file tools"))
        "the note talks about mkdir when the chat can write"))
  (testing "a read-only agent is not told to mkdir"
    (let [text (render/dirs-block (dirs) {:write? false})]
      (is (string/includes? text "already exist: Grep/Read them with your normal file tools; do not run ls first"))
      (is (string/includes? text "- /home/u/.config/eca/memory/global (global)"))))
  (testing "nil without dirs"
    (is (nil? (render/dirs-block [] {:write? true})))
    (is (nil? (render/dirs-block nil {:write? true}))))
  (testing "the index block does NOT carry the dir listing"
    (let [ctx (render/index-context {:entries [(entry)]
                                     :max-entries 10
                                     :max-tokens 10000})]
      (is (string/starts-with? (:content ctx) "<memory-index description="))
      (is (not (string/includes? (:content ctx) "Memory directories:")))
      (is (not (string/includes? (:content ctx) ".config/eca/memory"))))))

(deftest index-context-max-entries-caps-test
  (let [entries (mapv (fn [i] (entry :name (str "E" i) :path (str "/tmp/e" i ".md")))
                      (range 5))
        ctx (render/index-context {:entries entries
                                   :max-entries 2
                                   :max-tokens 100000})]
    (is (= 2 (:item-count ctx)))
    (is (= 5 (:total-count ctx)))
    (is (= ["E0" "E1"] (mapv :name (:items ctx))))
    (is (= [{:name "E0" :type "gotcha" :source "one" :path "/tmp/e0.md"}
            {:name "E1" :type "gotcha" :source "one" :path "/tmp/e1.md"}]
           (:items ctx)))
    (is (string/includes? (:content ctx) "Showing 2 of 5 memories. Tags marked * have memories not listed above. Before a task on a related topic, Grep"))
    (is (not (string/includes? (:content ctx) "- E2 [")))))

(deftest index-context-hidden-tag-marker-test
  (testing "tags of hidden entries carry a *, hidden-first, counts cover shown and hidden entries"
    (let [entries [(entry :name "Seen" :path "/tmp/seen.md" :tags ["seen"])
                   (entry :name "H1" :path "/tmp/h1.md" :tags ["raylib" "babashka"])
                   (entry :name "H2" :path "/tmp/h2.md" :tags ["raylib"])
                   (entry :name "H3" :path "/tmp/h3.md" :tags [])]
          content (:content (render/index-context {:entries entries
                                                   :max-entries 1
                                                   :max-tokens 10000}))]
      (is (string/includes? content "Tags in use: raylib(2)*, babashka*, seen\n"))
      (is (not (string/includes? content "Not shown")) "one line only, no second summary")
      (is (string/includes? content "Showing 1 of 4 memories. Tags marked * have memories not listed above, and some memories without tags are not listed either. Before a task on a related topic, Grep"))))
  (testing "a tag whose entries are all shown has no marker"
    (let [entries [(entry :name "A" :path "/tmp/a.md" :tags ["shown-tag"])
                   (entry :name "B" :path "/tmp/b.md" :tags ["hidden-tag"])]
          content (:content (render/index-context {:entries entries
                                                   :max-entries 1
                                                   :max-tokens 10000}))]
      (is (string/includes? content "Tags in use: hidden-tag*, shown-tag\n"))))
  (testing "a group with every entry hidden still shows its tag line"
    (let [entries [(entry :name "P" :path "/tmp/p.md" :label "repo" :tier :personal)
                   (entry :name "G" :path "/tmp/g.md" :label "global" :tier :global :tags ["jank"])]
          content (:content (render/index-context {:entries entries
                                                   :max-entries 1
                                                   :max-tokens 10000}))]
      (is (string/includes? content "## global\nTags in use: jank*\n"))))
  (testing "only untagged entries hidden: the notice says so and does not mention *"
    (let [entries [(entry :name "A" :path "/tmp/a.md" :tags ["seen"])
                   (entry :name "B" :path "/tmp/b.md" :tags [])]
          content (:content (render/index-context {:entries entries
                                                   :max-entries 1
                                                   :max-tokens 10000}))]
      (is (string/includes? content "Tags in use: seen\n"))
      (is (string/includes? content "Showing 1 of 2 memories. Some memories without tags are not listed. Before"))
      (is (not (string/includes? content "Tags marked *")))))
  (testing "the length cap never cuts a tag that has hidden entries"
    (let [many-tags (mapv #(format "frequent-tag-%02d" %) (range 30))
          entries [(entry :name "Seen" :path "/tmp/seen.md" :tags many-tags)
                   (entry :name "Hidden" :path "/tmp/hidden.md" :tags ["zzz-rare"])]
          content (:content (render/index-context {:entries entries
                                                   :max-entries 1
                                                   :max-tokens 10000}))
          line (some #(when (string/starts-with? % "Tags in use: ") %)
                     (string/split-lines content))]
      (is (string/starts-with? line "Tags in use: zzz-rare*, frequent-tag-00"))
      (is (string/ends-with? line "…"))))
  (testing "nothing hidden means no marker and no notice"
    (let [content (:content (render/index-context {:entries [(entry)]
                                                   :max-entries 10
                                                   :max-tokens 10000}))]
      (is (not (string/includes? content "*")))
      (is (not (string/includes? content "Showing "))))))

(deftest index-context-search-only-test
  (testing "maxEntries 0 lists no entries but keeps the topic lines and the search instruction"
    (let [entries [(entry :name "P" :path "/tmp/p.md" :label "repo" :tier :personal :tags ["eca"])
                   (entry :name "G" :path "/tmp/g.md" :label "global" :tier :global :tags ["jank"])]
          ctx (render/index-context {:entries entries :max-entries 0 :max-tokens 2000})]
      (is (= 0 (:item-count ctx)))
      (is (= 2 (:total-count ctx)))
      (is (string/includes? (:content ctx) "Tags in use: eca*"))
      (is (string/includes? (:content ctx) "Tags in use: jank*"))
      (is (string/includes? (:content ctx) "Showing 0 of 2 memories. Tags marked * have memories not listed above. Before a task on a related topic, Grep"))
      (is (not (string/includes? (:content ctx) "- P [")))))
  (testing "maxTokens 0 still omits the whole index"
    (is (nil? (render/index-context {:entries [(entry)] :max-entries 100 :max-tokens 0})))))

(deftest index-context-tier-share-test
  (let [project (mapv #(entry :name (str "P" %) :path (str "/tmp/p" % ".md")
                              :label "repo" :tier :personal :tags [])
                      (range 10))
        global (mapv #(entry :name (str "G" %) :path (str "/tmp/g" % ".md")
                             :label "global" :tier :global :tags [])
                     (range 10))
        shown-names (fn [opts]
                      (set (map :name (:items (render/index-context
                                               (merge {:max-tokens 100000} opts))))))]
    (testing "a large project cannot take the whole count budget"
      (let [names (shown-names {:entries (concat project global) :max-entries 10})]
        (is (= 10 (count names)))
        (is (= #{"G0" "G1" "G2"} (set (filter #(string/starts-with? % "G") names)))
            "global keeps 30 percent, best entries first")
        (is (= (set (map #(str "P" %) (range 7)))
               (set (filter #(string/starts-with? % "P") names))))))
    (testing "unused global share flows back to the project"
      (let [names (shown-names {:entries (concat project (take 1 global)) :max-entries 10})]
        (is (= 9 (count (filter #(string/starts-with? % "P") names))))
        (is (contains? names "G0"))))
    (testing "unused project share flows to global"
      (let [names (shown-names {:entries (concat (take 2 project) global) :max-entries 10})]
        (is (= 8 (count (filter #(string/starts-with? % "G") names))))))
    (testing "display order stays project group first, then global"
      (let [items (:items (render/index-context {:entries (concat project global)
                                                 :max-entries 10
                                                 :max-tokens 100000}))]
        (is (= ["P0" "P1" "P2" "P3" "P4" "P5" "P6" "G0" "G1" "G2"] (mapv :name items)))))
    (testing "the token budget is shared the same way and never exceeded"
      (let [ctx (render/index-context {:entries (concat project global)
                                       :max-entries 100
                                       :max-tokens 200})
            names (map :name (:items ctx))]
        (is (<= (:tokens ctx) 200))
        (is (some #(string/starts-with? % "G") names) "global is not squeezed out")
        (is (some #(string/starts-with? % "P") names))))))

(deftest index-context-max-tokens-caps-test
  (let [entries (mapv (fn [i] (entry :name (str "E" i)
                                     :description (apply str (repeat 80 "x"))
                                     :path (str "/tmp/e" i ".md")))
                      (range 20))
        ctx (render/index-context {:entries entries
                                   :max-entries 100
                                   :max-tokens 300})]
    (is (<= (:tokens ctx) 300) "whole block stays within the token budget")
    (is (< 0 (:item-count ctx) 20) "entries are truncated, not dropped")
    (is (= 20 (:total-count ctx)))
    (is (string/includes? (:content ctx) "Showing "))
    (is (= (:tokens ctx)
           (:tokens (render/index-context {:entries entries
                                           :max-entries 100
                                           :max-tokens 300})))
        "rendering is deterministic")))

(deftest index-context-small-budgets-test
  (doseq [budget [0 1 2 10]
          max-entries [0 1 10]]
    (is (nil? (render/index-context {:entries [(entry)]
                                     :max-entries max-entries
                                     :max-tokens budget}))))
  (doseq [entries [nil []]
          budget [0 1 10000]]
    (is (nil? (render/index-context {:entries entries :max-tokens budget}))))
  (testing "a complete block fits at its exact estimated budget"
    (doseq [max-entries [0 1 2]]
      (let [opts {:entries [(entry) (entry :name "Other")]
                  :max-entries max-entries}
            full (render/index-context opts)
            budget (shared/estimate-tokens (:content full))]
        (is (= full (render/index-context (assoc opts :max-tokens budget))))
        (let [smaller (render/index-context (assoc opts :max-tokens (dec budget)))]
          (is (or (nil? smaller) (<= (:tokens smaller) (dec budget))))))))
  (testing "write guidance remains available without an index block"
    (is (string/includes? (render/guidance-text {:write-mode "agent" :dirs (dirs)})
                          "Memory directories:"))))

(deftest index-context-block-wrapper-test
  (let [ctx (render/index-context {:entries [(entry :updated-at-ms (System/currentTimeMillis))]
                                   :max-entries 10
                                   :max-tokens 10000})
        content (:content ctx)]
    (testing "the block introduces itself through its description attribute"
      (is (string/starts-with? content
                               (str "<memory-index description=\"Saved notes from earlier chats (name, type, date of last change, description). "
                                    "ECA adds this block, not the user. "
                                    "It replaces any earlier memory block in this chat.\">")))
      (is (string/ends-with? content "</memory-index>"))
      (is (not (string/includes? content "## Memory\n")) "no generic title line"))
    (testing "the date is today"
      (is (string/includes? content (str "(" (date-of (System/currentTimeMillis)) ")"))))))

(deftest guidance-text-mode-test
  (doseq [[mode expected] [[nil "Save useful technical findings without being asked."]
                           ["agent" "Save useful technical findings without being asked."]
                           ["explicit" "Create, update, or delete memories only when the user asks."]
                           ["unknown" "Create, update, or delete memories only when the user asks."]]]
    (testing (str "mode: " mode)
      (is (string/includes? (render/guidance-text {:write-mode mode :write? true :dirs (dirs)})
                            expected)))))

(deftest guidance-text-read-part-test
  (let [read-only (render/guidance-text {:write-mode "agent" :write? false :dirs (dirs)})]
    (testing "the read part lists the dirs and the reuse rules"
      (is (string/starts-with? read-only "## Memory"))
      (is (string/includes? read-only "Memory directories:"))
      (is (string/includes? read-only "- /home/u/.config/eca/memory/global (global)"))
      (is (string/includes? read-only "Check facts and referenced files, symbols, or flags before reuse."))
      (is (string/includes? read-only "When a memory records a stale condition, run its check before reuse."))
      (is (string/includes? read-only "Current code overrides old facts, not user preferences."))
      (is (string/includes? read-only "When a note's description matches your task, open it once in this chat.")))
    (testing "the write part is absent"
      (is (not (string/includes? read-only "## Memory write guidance")))
      (is (not (string/includes? read-only "### Permission")))
      (is (not (string/includes? read-only "mkdir"))))))

(deftest guidance-text-keeps-dir-paths-verbatim-test
  (testing "directory paths are not HTML-escaped: the model must see the real path"
    (let [dirs [{:dir "/home/o'brien/R&D <x>/memory/global" :tier :global :label "global"}]
          text (render/guidance-text {:write-mode "agent" :write? true :dirs dirs})]
      (is (string/includes? text "- /home/o'brien/R&D <x>/memory/global (global)"))
      (is (not (string/includes? text "&#39;")))
      (is (not (string/includes? text "&amp;"))))))

(deftest guidance-text-write-part-test
  (let [both (render/guidance-text {:write-mode "agent" :write? true :dirs (dirs)})]
    (testing "a chat with write tools gets both parts"
      (is (string/includes? both "## Memory\n"))
      (is (string/includes? both "## Memory write guidance"))
      (is (string/includes? both "### Permission"))
      (is (string/includes? both "### What to keep"))
      (is (string/includes? both "### File format"))
      (is (string/includes? both "Refresh or delete the memory when the condition fired."))
      (is (string/includes? both "On each write, update the existing topic"))
      (is (string/includes? both "Merge duplicates or remove stale entries."))
      (is (string/includes? both "If unsure whether topics match, keep them separate."))
      (is (< (.indexOf ^String both "## Memory\n")
             (.indexOf ^String both "## Memory write guidance"))
          "the read part comes first"))))

(def ^:private pre-split-guidance
  "The guidance before the read/write split. Every sentence of it must survive
   in one of the two parts. Keep this copy even though the resource changed: it
   is the reference for the split."
  "## Memory write guidance

### Permission

{% if proactive %}Save useful technical findings without being asked.{% else %}Create, update, or delete memories only when the user asks.{% endif %}
User instructions apply to this chat unless the user clearly asks you to remember them for future chats.
This applies to every memory type. If permission for future use is unclear, skip saving rather than ask.

### What to keep

Save a finding only if a future chat, without this one, is likely to need it again and would pay a real
cost to find it again. The effort you spent now is not the test. Likely recurrence is the test.

Good candidates, most valuable first:
- What the user asked for, in their words (preferences, standing decisions).
- Recipes for this machine or workflow that took long to find and will be needed again.
- The state of ongoing work that a context reset would lose (decisions, where things are).
- Traps that will repeat in this project or setup, with their conditions and evidence.

Skip: a bug in a library or tool at one version, unless you keep using that version; copied code or
logs; chat summaries; pending tasks; team rules, which belong to AGENTS.md or `.eca/rules`.
Knowledge that already has a home (repo, git, skills, docs) needs no copy: save nothing, or save
the one-line answer with that home as its check. A bare pointer to the home is forbidden.
Never store secrets.

Record the conditions and evidence, not guesses. If you did not establish the cause, write what you
observed and under which conditions, and leave out the cause.

A factual memory is a claim stored with the condition of its death: record what would falsify it
and one command that checks it. Claims that cannot be checked are opinions. A preference is a
vote, not a law: keep the user's exact words, their scope, and how future use was accepted. When
the moment fits only this situation, save the situation and what worked, with the condition
attached; when it fits nothing future, save nothing. A new decision replaces the old one on its
topic.

Use project memory by default. Use global memory only for subjects outside this codebase.
If a code change here could make a fact false, keep it in project memory.
Team rules belong in AGENTS.md or `.eca/rules`. Never commit memory files to git.

### File format

Use one topic per Markdown file, named after the topic, not the date. Start with YAML fields:
- `name`: the shortest phrase that distinguishes the topic from the other memories in its group
  (the project group or the global group), usually 3-6 words, at most ~60 characters.
- `description`: one line, at most 120 characters, saying when the memory applies. If it contains `: `
  or a backslash, put it in single quotes (write an apostrophe inside as `''`). Double quotes turn
  `\\n`, `\\t`, and `\\r` into control characters.
- `type`: solution|decision|gotcha|procedure|fact|preference|metric.
- `tags`: optional, at most 3 lowercase words you would filter memories by. Reuse `Tags in use:`
  spellings.

The index shows only name and description; write them so a reader who never opens the file applies
the memory correctly from those two lines alone.

Keep the body within 20 lines and about 1500 characters. Keep exact quotes, errors, and paths; use
references instead of copies. Use full dates for one-time events; keep recurring times relative.
Preserve unknown fields and types in existing files, but do not add new fields.

```markdown
---
name: Docker host DNS on Linux
description: A container cannot reach services on the host machine without an extra flag
type: procedure
tags: docker, networking
---
When: a container cannot reach a service running on the host machine.
Steps: start it with `--add-host=host.docker.internal:host-gateway` and target
`host.docker.internal` (Docker Desktop adds the name automatically).
Check: `getent hosts host.docker.internal` inside the container prints an IP.
```

### Reuse and upkeep

Check facts and referenced files, symbols, or flags before reuse. When a memory records a stale
condition, run its check before reuse; refresh or delete the memory when the condition fired.
Current code overrides old facts,
not user preferences. On each write, update the existing topic: replace statements that are
no longer true, and do not append a session report. Merge duplicates or remove stale entries.
If unsure whether topics match, keep them separate.
")

(defn ^:private comparable-text
  "Lowercased, whitespace-normalized text with heading lines, code fences and
   template tags removed, so plain sentences can be compared between the
   reference and the render. Lowercasing lets a sentence that moved from after a
   semicolon to a fresh sentence still match."
  [text]
  (-> (str text)
      (string/replace #"(?m)^#+.*$" "")
      (string/replace #"(?m)^```.*$" "")
      (string/replace #"\{%[^%]*%\}" " ")
      (string/replace #"\{%\}" " ")
      (string/replace #"\s+" " ")
      string/lower-case))

(defn ^:private reference-clauses
  "Sentences and clauses of `text`, as searchable substrings without trailing
   punctuation."
  [text]
  (->> (comparable-text (string/replace text #"\{%\s*else\s*%\}.*?\{%\s*endif\s*%\}" ""))
       (re-seq #"[^.;]+")
       (map (comp string/trim #(string/replace % #"^-\s+" "") string/trim))
       (remove string/blank?)))

(deftest guidance-keeps-every-sentence-test
  (let [both (comparable-text (render/guidance-text {:write-mode "agent" :write? true :dirs (dirs)}))
        clauses (reference-clauses pre-split-guidance)]
    (is (> (count clauses) 30) "the reference text was parsed into sentences")
    (doseq [clause clauses]
      (is (string/includes? both clause) clause))))

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
    (testing "body size and description let the agent judge candidates without opening files"
      (is (string/includes?
           (render/consolidate-snapshot-text
            {:dirs (snapshot-dirs)
             :entries [(snapshot-entry :body "  twelve chars\n" :tags ["docker"])]}
            now)
           "last read 5d ago, body 12 chars; tags: docker — CI fails on Alpine with localhost"))
      (testing "both are left out when missing"
        (let [text (render/consolidate-snapshot-text
                    {:dirs (snapshot-dirs)
                     :entries [(snapshot-entry :description "  ")]}
                    now)]
          (is (not (string/includes? text "body ")))
          (is (string/includes? text "last read 5d ago\n")))))
    (testing "closing note explains the usage numbers"
      (is (string/includes? text "_Note: reads and last-read ages count local `read_file` consultations only._")))))

(deftest consolidate-snapshot-text-body-test
  (testing "each body follows its entry line, indented"
    (let [text (render/consolidate-snapshot-text
                {:dirs (snapshot-dirs)
                 :entries [(snapshot-entry :body "Rule 1.\n\nMore detail.")]}
                now)]
      (is (string/includes? text "- Docker IPv6 [gotcha] (docker-ipv6.md)"))
      (is (string/includes? text "  body:\n  Rule 1.\n\n  More detail.")
          "the body reads as part of the entry")
      (is (not (string/includes? text "cut at"))
          "a short body gets no cut marker")))
  (testing "a long body is cut with a marker that says to open the file"
    (let [body (apply str (repeat 2000 "x"))
          text (render/consolidate-snapshot-text
                {:dirs (snapshot-dirs)
                 :entries [(snapshot-entry :body body)]}
                now)]
      (is (string/includes? text (str "\n  " (subs body 0 1500)))
          "the visible part is max-snapshot-body-chars long")
      (is (string/includes? text "[... body cut at 1500 chars; open the file to read the rest ...]"))
      (is (not (string/includes? text (subs body 0 1501)))
          "the rest of the body is not rendered")))
  (testing "an entry without a body renders only its line"
    (let [text (render/consolidate-snapshot-text
                {:dirs (snapshot-dirs)
                 :entries [(snapshot-entry)]}
                now)]
      (is (not (string/includes? text "  body:"))))))

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
    (let [text (render/consolidate-snapshot-text {:dirs (snapshot-dirs) :entries []})]
      (is (string/includes? text "- /home/u/.config/eca/memory/global (global)"))
      (is (string/includes? text "(no memory files)"))))
  (testing "no dirs degrades to a one-line note"
    (is (= "No memory directories configured."
           (render/consolidate-snapshot-text {:dirs [] :entries []})))))

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
              {:dirs (snapshot-dirs) :entries [(snapshot-entry)] :skipped []})]
    (is (not (string/includes? text "Skipped files"))
        "an empty skipped list renders no section"))
  (let [text (render/consolidate-snapshot-text
              {:dirs (snapshot-dirs) :entries [(snapshot-entry)]})]
    (is (not (string/includes? text "Skipped files"))
        "absent `skipped` key renders no section")))

(deftest consolidate-snapshot-text-default-now-test
  (let [text (render/consolidate-snapshot-text
              {:dirs (snapshot-dirs)
               :entries [(snapshot-entry :updated-at-ms (System/currentTimeMillis))]})]
    (is (string/includes? text "Memory directories:"))
    (is (string/includes? text "— age 0d, reads 3"))))
(deftest changes-context-test
  (let [content (render/changes-context
                 [{:label "repo" :name "New" :entry (entry :name "New" :path "/tmp/new.md")}
                  {:label "global" :name "Gone" :entry nil}
                  {:label "repo" :name "Edited"
                   :entry (entry :name "Edited" :path "/tmp/edited.md" :description "changed")}])]
    (testing "the block introduces itself through its description attribute"
      (is (string/starts-with?
           content
           (str "<memory-changes description=\"Notes that changed since ECA last showed memory in this chat. "
                "ECA adds this block, not the user. "
                "When a note appears more than once in this chat, the newest entry is correct.\">")))
      (is (string/ends-with? content "</memory-changes>")))
    (testing "items are grouped per directory label, in order of first appearance"
      (is (string/includes? content "\n## repo\n"))
      (is (string/includes? content "\n## global\n"))
      (is (< (.indexOf ^String content "## repo") (.indexOf ^String content "## global")))
      (is (string/includes? content "- New [gotcha] ("))
      (is (string/includes? content "- Edited [gotcha] (")))
    (testing "a deleted note keeps its name and carries the marker"
      (is (string/includes? content "- Gone (deleted)"))))
  (testing "nil without items"
    (is (nil? (render/changes-context [])))
    (is (nil? (render/changes-context nil)))))
