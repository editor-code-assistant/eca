(ns eca.features.memory.render
  "Renders the memory context blocks that ride user messages (a full index or
   a change list), the memory guidance text placed in the static system
   instructions, and the /memory-consolidate usage snapshot."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.shared :as shared])
  (:import
   [java.time Instant LocalDate ZoneId]))

(set! *warn-on-reflection* true)

(def ^:private ms-per-day 86400000)

(defn ^:private age-in-days
  "Whole 24-hour periods elapsed between `at-ms` and `now-ms`, or nil without a
   timestamp. Never negative."
  [at-ms now-ms]
  (when (some? at-ms)
    (max 0 (quot (- now-ms at-ms) ms-per-day))))

(defn ^:private date-str
  "Absolute local date `YYYY-MM-DD` of a timestamp. Absolute dates cost about
   the same as a relative age suffix and do not go stale in a stored block."
  ^String [at-ms]
  (str (LocalDate/ofInstant (Instant/ofEpochMilli (long at-ms)) (ZoneId/systemDefault))))

(def ^:private max-description-chars
  "render.clj truncates descriptions beyond this many chars in index lines.
   Matches the <=120 chars rule in the write guidance: this is the actual
   enforcement, the guidance line alone does not stop overshoot. Keeps a
   single bloated entry from eating the budget meant for many."
  120)

(defn ^:private inline-text
  "`s` as safe text for one line of a memory block: each run of whitespace
   (line breaks too) becomes one space, and the `<` of a memory block tag
   (`<memory-index`, `</memory-changes`...) becomes `&lt;`. Note text comes
   from files that a model may write, so it must not close the block it sits
   in and continue as text outside it. Other `<` stay as they are (`Vec<T>`)."
  ^String [s]
  (-> (str s)
      (string/replace #"\s+" " ")
      (string/replace #"(?i)<(\s*/?\s*memory-(?:index|changes))" "&lt;$1")))

(defn ^:private single-line-truncated
  "`inline-text` truncated to `max-description-chars` with an ellipsis.
   Descriptions come from free-form frontmatter and may contain newlines."
  [s]
  (let [s (inline-text s)]
    (if (> (count s) max-description-chars)
      (str (subs s 0 (dec max-description-chars)) "…")
      s)))

(defn ^:private entry-line
  "One compact line: `- <name> [<type>] (<YYYY-MM-DD>) — <description>`.
   No source slug: entries are grouped under one heading per directory
   instead. Tags are deliberately NOT shown per-entry either: in real
   entries they mostly duplicate words already in name/description, and the
   line's job is WHEN-recognition. Tags ride the per-group `Tags in use:`
   vocabulary line (see `tags-summary-line`), plus they stay in the files
   (grep anchors) and in the /memory-consolidate snapshot, where topic
   grouping actually consumes them."
  [{:keys [name type description updated-at-ms]}]
  (format "- %s [%s]%s — %s"
          (single-line-truncated name) (single-line-truncated type)
          (if (some? updated-at-ms)
            (str " (" (date-str updated-at-ms) ")")
            "")
          (single-line-truncated description)))

(def ^:private tags-lines-share
  "Share of the index token budget (`max-tokens`) that the `Tags in use:` lines
   of all groups may use together. The line is the only place where the model
   sees the tags (ticket ids too) of notes that a small store would show and a
   large store hides, so a fixed short cap cut most of it away. A share keeps
   the line complete at the default budget and still leaves most of a small
   budget to the entries; the full index is sent once per window and then read
   from the prompt cache, so the extra tokens are paid once. Tags with hidden
   entries come first and then frequency order, so the tags that matter for
   searching survive a cut."
  0.2)

(def ^:private chars-per-token
  "The ratio that `shared/estimate-tokens` uses."
  4)

(def ^:private tags-prefix "Tags in use: ")

(defn ^:private tag-frequencies
  "Distinct tags of `entries` as `[tag n]`, most-used first, alphabetical tiebreak."
  [entries]
  (->> entries
       (mapcat :tags)
       (frequencies)
       (sort-by (fn [[tag n]] [(- n) tag]))))

(defn ^:private tag-pieces
  "The pieces of the `Tags in use: t1(3)*, t2, …` line for a group of entries
   (entries marked with `::shown?`): distinct tags with a `(n)` count on any
   tag used more than once. Computed from ALL entries of the group, shown or
   hidden, so it also covers memories that did not fit the index. A `*` after
   a tag means at least one memory with that tag is not listed; the footer
   explains the mark once. Tags with hidden entries come first, then frequency
   order (alphabetical tiebreak).

   Two jobs: writers reuse an existing tag instead of inventing plurals or
   variants, and readers learn which topics have memories beyond the visible
   lines. The `(n)` count shows which tags actually cluster anything; singletons
   stay bare, which keeps the line cheap. \"in use\" is deliberate: the line
   describes what exists, it is not a menu to choose from, and a genuinely new
   topic may add a new tag."
  [entries]
  (let [hidden-tags (set (mapcat :tags (remove ::shown? entries)))]
    (->> (tag-frequencies entries)
         (sort-by (fn [[tag n]] [(if (hidden-tags tag) 0 1) (- n) tag]))
         (mapv (fn [[tag n]]
                 (str (inline-text tag)
                      (when (> n 1) (str "(" n ")"))
                      (when (hidden-tags tag) "*")))))))

(defn ^:private full-tags-line
  "The uncut `Tags in use:` line of `pieces`, or nil without tags."
  [pieces]
  (when (seq pieces)
    (str tags-prefix (string/join ", " pieces))))

(defn ^:private tags-summary-line
  "The `Tags in use:` line of `pieces`, cut to at most `max-chars` only between
   whole tags, with `…` at the end of a cut line. nil without tags. Tags that
   fall off are not lost: they stay in the memory files and in the
   /memory-consolidate snapshot, which lists all tags per entry."
  [pieces max-chars]
  (when-let [full (full-tags-line pieces)]
    (if (<= (count full) max-chars)
      full
      ;; Cut between whole tags, leaving room for the ellipsis.
      (loop [ps pieces line tags-prefix]
        (let [candidate (str line (when-not (= line tags-prefix) ", ") (first ps))]
          (if (and (seq ps) (< (count candidate) max-chars))
            (recur (rest ps) candidate)
            (str line "…")))))))

(defn ^:private fair-shares
  "Splits `budget` between `needs` (one number per group, in group order):
   a group that needs less than an equal share gets what it needs, and the
   rest is split equally between the other groups (max-min fairness). Returns
   one cap per group, in the same order."
  [needs budget]
  (loop [[i & more] (sort-by #(nth needs %) (range (count needs)))
         budget (double budget)
         caps {}]
    (if (nil? i)
      (mapv caps (range (count needs)))
      (let [cap (min (double (nth needs i)) (/ budget (inc (count more))))]
        (recur more (- budget cap) (assoc caps i cap))))))

(defn ^:private not-listed-notice
  "Footer for an index that does not list every memory: how many are shown, what
   the `*` mark and untagged memories mean, and how to find the rest (grep, not
   opening files one by one). `hidden` is the seq of entries that are not
   listed."
  [shown-count total-count hidden]
  (let [starred? (boolean (some (comp seq :tags) hidden))
        untagged? (boolean (some (comp empty? :tags) hidden))]
    (str "Showing " shown-count " of " total-count " memories. "
         (cond
           (and starred? untagged?) "Tags marked * have memories not listed above, and some memories without tags are not listed either."
           starred? "Tags marked * have memories not listed above."
           :else "Some memories without tags are not listed.")
         " Before a task on a related topic, Grep the memory directories for the tag or its keywords; do not open memory files one by one.")))

(def ^:private dirs-note-write
  "Personal and Global directories already exist: use your file tools directly without mkdir or ls first, and Grep/Read them with your normal file tools.")

(def ^:private dirs-note-read
  "Personal and Global directories already exist: Grep/Read them with your normal file tools; do not run ls first.")

(defn ^:private dir-line
  "One memory-dir line: `- <dir> (<label>) — <tier annotation>`. The annotation
   is why the paths do not need to be repeated in the write guidance: tier and
   path sit on the same line, and the write policy can reference tiers (Global,
   Personal per-project) by name."
  [{:keys [dir label tier]}]
  (str "- " dir " (" label ")"
       (case tier
         :global " — Global (personal, all projects)"
         :personal " — Personal per-project (default write target)"
         "")))

(defn dirs-block
  "The memory-directory listing as prompt text: one annotated line per dir
   plus the pre-created note. The note talks about mkdir only when the chat has
   write tools (`:write?`), so a read-only agent is not told to create dirs.
   Session-stable (workspace roots do not change during a chat), so it rides the
   static guidance. Returns nil without dirs."
  [dirs {:keys [write?]}]
  (when (seq dirs)
    (string/join "\n"
                 (concat ["Memory directories:"]
                         (map dir-line dirs)
                         [(if write? dirs-note-write dirs-note-read)]))))

(defn ^:private index-content
  "Full-index block text: entries grouped under one heading per memory
   directory (no repeated source slug per line), a `Tags in use:` line per
   group (tags with hidden entries carry a `*`), the shown entry lines
   (entries marked `::shown?`), and a short notice when not all entries are
   shown. Group order and within-group order are taken from the input
   as-is: `usage/ranked-memories` already partitions by directory in tier order and
   sorts by the decay-weighted usage score, so re-sorting here would
   discard the usage signal. The memory dir paths are NOT here; they are
   session-stable and live in the static guidance (see `dirs-block`).
   The `Tags in use:` lines of all groups share `tags-lines-share` of
   `max-tokens` (see `fair-shares`)."
  [entries total-count max-tokens]
  (let [groups (partition-by :label entries)
        shown-count (count (filter ::shown? entries))
        pieces (mapv tag-pieces groups)
        caps (fair-shares (mapv #(count (or (full-tags-line %) "")) pieces)
                          (* tags-lines-share chars-per-token max-tokens))]
    (string/join "\n"
                 (concat
                  (mapcat (fn [g group-pieces cap]
                            (let [tags-line (tags-summary-line group-pieces cap)]
                              (concat [(str "## " (inline-text (:label (first g))))]
                                      (when tags-line [tags-line])
                                      (map entry-line (filter ::shown? g)))))
                          groups pieces caps)
                  (when (> total-count shown-count)
                    [(not-listed-notice shown-count total-count (remove ::shown? entries))])))))

(defn ^:private wrap-block
  "Wraps block `content` in a tag whose `description` attribute explains the
   block to the model. The same pattern as `<editor-state>`: the block
   introduces itself, so no system-prompt sentence is needed, also when the
   user enables memory while a chat is open (the system prompt is pinned)."
  [tag description content]
  (str "<" tag " description=\"" description "\">\n" content "\n</" tag ">"))

(def ^:private index-description
  "Saved notes from earlier chats (name, type, date of last change, description). ECA adds this block, not the user. It replaces any earlier memory block in this chat.")

(def ^:private changes-description
  "Notes that changed since ECA last showed memory in this chat. ECA adds this block, not the user. When a note appears more than once in this chat, the newest entry is correct.")

(def ^:private global-share
  "Share of the index budget reserved for the global tier. The project tier
   gets the rest. A side that needs less than its share leaves the unused part
   to the other side."
  0.3)

(defn ^:private select-by-share
  "Set of indexes kept from `indexed` (a vector of `[index entry]`) when the
   summed `cost-fn` of the kept entries may not exceed `budget`. Project and
   global entries are each sorted by `:score` (the usage score of
   `usage/ranked-memories`), highest first, and each side keeps a prefix of that
   order. So the best entries of each side win, also when the project side
   has one group per workspace root. The sort is stable: entries without a
   score keep their input order.
   Global gets `global-share` of the budget and project the rest; when one
   side needs less than its part, the other side may use the difference.
   Entry-count quotas are rounded to whole entries; token quotas stay fractional."
  [indexed cost-fn budget whole-entries?]
  (let [global? #(= :global (:tier (second %)))
        by-score (fn [xs] (vec (sort-by #(double (or (:score (second %)) 0.0)) > xs)))
        project (by-score (remove global? indexed))
        global (by-score (filter global? indexed))
        need (fn [xs] (reduce + 0 (map (comp cost-fn second) xs)))
        project-need (need project)
        global-need (need global)
        global-quota (if whole-entries?
                       (Math/round (* (double budget) global-share))
                       (* budget global-share))
        ;; Global keeps its quota, and gets more when project needs less than
        ;; its part. Project gets what global leaves.
        global-cap (min global-need (max global-quota (- budget project-need)))
        project-cap (- budget global-cap)
        prefix (fn [xs cap]
                 (loop [xs xs used 0.0 kept #{}]
                   (if-let [[i entry] (first xs)]
                     (let [used (+ used (double (cost-fn entry)))]
                       (if (> used cap)
                         kept
                         (recur (rest xs) used (conj kept i))))
                     kept)))]
    (into (prefix project project-cap) (prefix global global-cap))))

(defn index-context
  "Build the full memory index block.

   `entries` has the output shape of `eca.features.memory.usage/ranked-memories`
   (already grouped, and ordered by usage score inside each group). At most
   `max-entries` entries and `max-tokens` total tokens are emitted. The limits
   are shared between the project and global tiers (see `select-by-share`), so
   a large project cannot push all global entries out. Inside a tier the
   selection compares `:score` across groups, so the first workspace root
   cannot use the whole project budget. Display order stays the input order. Entries that do not fit are flagged with a `*` on their tags.
   Returns nil when there is nothing to show: with zero memories a block buys
   nothing, because the write targets live in the static guidance."
  [{:keys [entries max-entries max-tokens]}]
  (let [entries (vec entries)]
    (when (seq entries)
      (let [max-entries (or max-entries Integer/MAX_VALUE)
            max-tokens (or max-tokens Integer/MAX_VALUE)
            total-count (count entries)
            indexed (vec (map-indexed vector entries))
            counted (let [kept (select-by-share indexed (constantly 1) max-entries true)]
                      (filterv #(contains? kept (first %)) indexed))
            line-cost (fn [entry] (inc (shared/estimate-tokens (entry-line entry))))
            render (fn [token-budget]
                     (let [kept (select-by-share counted line-cost token-budget false)
                           marked (mapv (fn [[i entry]] (assoc entry ::shown? (contains? kept i)))
                                        indexed)
                           content (wrap-block "memory-index" index-description
                                               (index-content marked total-count max-tokens))]
                       {:content content
                        :tokens (shared/estimate-tokens content)
                        :shown (filterv ::shown? marked)}))
            ;; Block overhead (headings, tag lines, notices) is not part of the
            ;; line costs, so cut the overshoot from what the shown lines cost
            ;; until the whole block fits. A cut from the old budget would
            ;; change nothing while that budget is above the cost of the lines.
            ;; The cut leaves one line of slack: line costs are estimates, and
            ;; a full cut would often drop one line more than needed.
            result (loop [token-budget max-tokens]
                     (let [r (render token-budget)
                           overshoot (- (:tokens r) max-tokens)]
                       (cond
                         (not (pos? overshoot)) r
                         (empty? (:shown r)) nil
                         :else (let [costs (map line-cost (:shown r))
                                     cut (max 1 (- overshoot (reduce max costs)))]
                                 (recur (- (min token-budget (reduce + costs)) cut))))))]
        (when result
          {:content (:content result)
           :tokens (:tokens result)
           :item-count (count (:shown result))
           :items (mapv (fn [entry]
                          {:name (:name entry)
                           :type (:type entry)
                           :source (:label entry)
                           :path (:path entry)})
                        (:shown result))})))))

(defn ^:private change-line
  "One line of a change list: the entry line for a new or changed note,
   `- <name> (deleted)` for a note that no longer exists, or
   `- <name> (invalid file, not indexed)` for a file the scanner skipped, or
   `- <name> (no longer indexed)` for an existing file outside the current scan."
  [{:keys [entry name invalid? unindexed?]}]
  (if entry
    (entry-line entry)
    (str "- " (single-line-truncated name)
         (cond
           invalid? " (invalid file, not indexed)"
           unindexed? " (no longer indexed)"
           :else " (deleted)"))))

(defn changes-context
  "Build the memory change-list block for the notes that changed from outside
   this chat. `items` is a seq of `{:label <dir label> :entry <current scan
   entry or nil> :name <note name>}`; a nil `:entry` marks a deleted note.
   Items are grouped per directory label, in order of first appearance.
   Returns nil without items."
  [items]
  (when (seq items)
    (let [order (distinct (map :label items))
          groups (group-by :label items)]
      (wrap-block "memory-changes" changes-description
                  (string/join "\n"
                               (mapcat (fn [label]
                                         (concat [(str "## " (inline-text label))]
                                                 (map change-line (get groups label))))
                                       order))))))

(defn ^:private load-guidance-template*
  "The memory write-guidance template: a classpath resource like every other
   built-in prompt (`resources/prompts/`)."
  []
  (slurp (io/resource "prompts/memory_guidance.md")))

(def ^:private load-guidance-template (memoize load-guidance-template*))

(defn guidance-text
  "Memory guidance for the static system instructions, rendered from
   `prompts/memory_guidance.md`. The read part goes to every primary agent
   (directory listing plus reuse rules); the write part only to agents with
   write tools (`:write?`). Session-stable, so it joins the cached system
   prefix; the memory blocks that change as notes change ride the user
   messages instead. Uses safe template rendering to protect prompt building.
   `:subagents?` adds the note that subagents have no memory."
  [{:keys [write-mode write? subagents? dirs]}]
  (string/trim
   (shared/safe-selmer-render (load-guidance-template)
                              {:proactive (= "agent" (or write-mode "agent"))
                               :write (boolean write?)
                               :subagents (boolean subagents?)
                               :dirs-block (dirs-block dirs {:write? write?})}
                              "memory-guidance"
                              "")))

(defn ^:private entry-relative-path
  "Path relative to the containing group dir, with portable separators."
  [dir path]
  (if (some? path)
    (let [normalize #(string/replace (str %) "\\" "/")
          prefix (str (string/replace (normalize dir) #"/+$" "") "/")
          path (normalize path)]
      (if (string/starts-with? path prefix)
        (subs path (count prefix))
        path))
    "?"))

(defn ^:private snapshot-entry-line
  "One usage snapshot line for a scan entry at `now-ms`:
   `- <name> [<type>] (<filename>) — age Nd, reads N, last read Nd ago, body N chars; tags: a, b — <description>`.
   Unread entries render \"last read never\"; the body size, tags and
   description segments are omitted when missing. The body size lets the
   consolidating agent spot oversized files without opening them, and the
   description lets it judge candidates from the snapshot alone. Unlike the
   memory index the snapshot shows ALL tags, even duplicated ones:
   /memory-consolidate clusters and merges by topic, so it needs the full topic
   vocabulary.

   `age` reports `:updated-at-ms`, when the KNOWLEDGE last changed, which
   matters most here: consolidation judges staleness from this column and
   rewrites files as it works, so reporting raw mtime would let one run
   corrupt the input of the next."
  [{:keys [name type path reads last-read-at tags updated-at-ms body description]} dir now-ms]
  (let [age (age-in-days updated-at-ms now-ms)
        age-str (if (some? age) (str "age " age "d") "age n/a")
        usage-str (str "reads " (long (or reads 0))
                       ", last read " (if last-read-at
                                        (str (age-in-days last-read-at now-ms) "d ago")
                                        "never")
                       (when (some? body)
                         (str ", body " (count (string/trim (str body))) " chars"))
                       (if (seq tags)
                         (str "; tags: " (string/join ", " tags))
                         "")
                       (when-not (string/blank? description)
                         (str " — " (single-line-truncated description))))]
    (format "- %s [%s] (%s) — %s, %s"
            (single-line-truncated name) (single-line-truncated type)
            (entry-relative-path dir path) age-str usage-str)))

(def ^:private max-snapshot-body-chars
  "Body text shown per note in the /memory-consolidate snapshot. Most entries
   can be judged from their body alone, so the consolidating agent does not
   open every file; a longer body is cut with a marker that tells it to open
   the file instead."
  1500)

(defn ^:private snapshot-body-lines
  "Body of one note under its snapshot line, each non-blank line indented two
   spaces so it reads as part of the entry. Cut at `max-snapshot-body-chars`
   with an explicit marker. Empty when the note has no body."
  [body]
  (let [body (string/trim (str body))]
    (when (seq body)
      (let [cut? (> (count body) max-snapshot-body-chars)
            body (if cut? (subs body 0 max-snapshot-body-chars) body)]
        (concat ["  body:"]
                (map #(if (string/blank? %) "" (str "  " %))
                     (string/split body #"\n"))
                (when cut?
                  [(str "  [... body cut at " max-snapshot-body-chars
                        " chars; open the file to read the rest ...]")]))))))

(defn ^:private snapshot-groups
  "Entries partitioned per memory dir, in `dirs` order; within each group the
   scan order (usage-score descending) is preserved. Dir groups without
   entries are dropped; the dir list at the top of the snapshot still names
   them."
  [dirs entries]
  (keep (fn [{:keys [dir] :as d}]
          (let [group (filterv #(shared/path-inside-root? (:path %) dir) entries)]
            (when (seq group)
              [d group])))
        dirs))

(defn consolidate-snapshot-text
  "Markdown block for the /memory-consolidate prompt: the ordered memory
   directory list (paths + tier labels) followed by a usage snapshot with one
   line per memory file, grouped per dir in scan order. Each line shows name,
   type, filename, age in days, read count and last-read age in days (or
   \"never\"), and each note's body follows its line (see `snapshot-body-lines`),
   so the consolidating agent can judge most entries without opening files. The
   closing note reminds the model that reads/last-read count local `read_file`
   consultations only. `now-ms` defaults to the current time; pass it
   explicitly for deterministic output. When `opts` contains a non-empty
   `:skipped` (see `eca.features.memory/skipped-files`), appends a section
   naming the broken files and their skip reasons so the consolidating agent
   can repair or delete them instead of never seeing them."
  ([opts] (consolidate-snapshot-text opts (System/currentTimeMillis)))
  ([{:keys [dirs entries skipped]} now-ms]
   (if (seq dirs)
     (string/join "\n"
                  (concat
                   ["Memory directories:"]
                   (map (fn [{:keys [dir label]}] (str "- " dir " (" label ")")) dirs)
                   ["" "Usage snapshot:"]
                   (if-let [groups (seq (snapshot-groups dirs entries))]
                     (mapcat (fn [[{:keys [dir label]} group]]
                               (cons (str "**" dir " (" label ")**")
                                     (mapcat (fn [entry]
                                               (cons (snapshot-entry-line entry dir now-ms)
                                                     (snapshot-body-lines (:body entry))))
                                             group)))
                             groups)
                     ["(no memory files)"])
                   (when (seq skipped)
                     (concat
                      ["" "Skipped files (not in the index; fix them so they can be indexed, or delete them):"]
                      (map (fn [{:keys [path reason]}]
                             (str "- " path " — " reason))
                           skipped)))
                   ["" "_Note: reads and last-read ages count local `read_file` consultations only._"]))
     "No memory directories configured.")))
