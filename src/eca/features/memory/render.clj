(ns eca.features.memory.render
  "Renders the always-on memory index context block, the write-policy
   guidance text injected into the chat prompt, and the /memory-consolidate
   usage snapshot."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn age-in-days
  "Whole 24-hour periods elapsed between `at-ms` and `now-ms`, or nil without a
   timestamp. Never negative."
  [at-ms now-ms]
  (when (some? at-ms)
    (max 0 (quot (- now-ms at-ms) 86400000))))

(defn ^:private updated-suffix
  "Short freshness label like \"updated 43d ago\", or nil without a timestamp."
  [at-ms now-ms]
  (when (some? at-ms)
    (str "updated " (age-in-days at-ms now-ms) "d ago")))

(def ^:private max-description-chars
  "render.clj truncates descriptions beyond this many chars in index lines.
   Matches the <=120 chars rule in the write guidance: this is the actual
   enforcement, the guidance line alone does not stop overshoot. Keeps a
   single bloated entry from eating the budget meant for many."
  120)

(defn ^:private single-line-truncated
  "Collapses whitespace to a single line and truncates to `max-description-chars`
   with an ellipsis. Descriptions come from free-form frontmatter and may
   contain newlines."
  [s]
  (let [s (string/replace (str s) #"\s+" " ")]
    (if (> (count s) max-description-chars)
      (str (subs s 0 (dec max-description-chars)) "…")
      s)))

(defn ^:private entry-line
  "One compact index line: `- <name> [<type>] (updated Nd ago) — <description>`.
   No source slug: entries are grouped under one heading per directory
   instead. Tags are deliberately NOT shown per-entry either: in real
   entries they mostly duplicate words already in name/description, and the
   per-turn index's job is WHEN-recognition. Tags ride the per-group
   `Tags in use:` vocabulary line (see `tags-summary-line`), plus they stay in the
   files (grep anchors) and in the /memory-consolidate snapshot, where topic
   grouping actually consumes them."
  [{:keys [name type description updated-at-ms]} now-ms]
  (let [segments (cond-> []
                   (some? updated-at-ms) (conj (updated-suffix updated-at-ms now-ms)))]
    (format "- %s [%s]%s — %s"
            name type
            (if (seq segments)
              (str " (" (string/join "; " segments) ")")
              "")
            (single-line-truncated description))))

(def ^:private max-tags-summary-chars
  "Upper bound on the per-group `Tags in use:` line in the per-turn index.
   ~140 chars of comma-separated tag words costs about one entry's worth of
   tokens; the tail is elided rather than letting a large or drifting
   vocabulary crowd out actual entries. Tags with hidden entries come first
   and then frequency order, so the tags that matter for searching survive
   the cut."
  140)

(defn ^:private tag-frequencies
  "Distinct tags of `entries` as `[tag n]`, most-used first, alphabetical tiebreak."
  [entries]
  (->> entries
       (mapcat :tags)
       (frequencies)
       (sort-by (fn [[tag n]] [(- n) tag]))))

(defn ^:private tags-summary-line
  "The `Tags in use: t1(3)*, t2, …` line for a group of entries (entries marked
   with `::shown?`): distinct tags with a `(n)` count on any tag used more than
   once. Computed from ALL entries of the group, shown or hidden, so it also
   covers memories that did not fit the index. A `*` after a tag means at least
   one memory with that tag is not listed; the footer explains the mark once.
   Tags with hidden entries come first, then frequency order (alphabetical
   tiebreak), hard-capped at `max-tags-summary-chars` and cut only between
   whole tags.

   Two jobs: writers reuse an existing tag instead of inventing plurals or
   variants, and readers learn which topics have memories beyond the visible
   lines. The `(n)` count shows which tags actually cluster anything; singletons
   stay bare, which keeps the line cheap. \"in use\" is deliberate: the line
   describes what exists, it is not a menu to choose from, and a genuinely new
   topic may add a new tag. Rare tags that fall off the cap are not lost: they
   stay in the memory files and in the /memory-consolidate snapshot, which lists
   all tags per entry."
  [entries]
  (when-let [by-freq (seq (tag-frequencies entries))]
    (let [hidden-tags (set (mapcat :tags (remove ::shown? entries)))
          prefix "Tags in use: "
          pieces (->> by-freq
                      (sort-by (fn [[tag n]] [(if (hidden-tags tag) 0 1) (- n) tag]))
                      (map (fn [[tag n]]
                             (str tag
                                  (when (> n 1) (str "(" n ")"))
                                  (when (hidden-tags tag) "*")))))
          full (str prefix (string/join ", " pieces))]
      (if (<= (count full) max-tags-summary-chars)
        full
        ;; Cut between whole tags, leaving room for the ellipsis.
        (loop [ps pieces line prefix]
          (let [candidate (str line (when-not (= line prefix) ", ") (first ps))]
            (if (and (seq ps) (< (count candidate) max-tags-summary-chars))
              (recur (rest ps) candidate)
              (str line "…"))))))))

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

(def ^:private dirs-note
  "Personal and Global directories already exist: use your file tools directly without mkdir or ls first, and Grep/Read them with your normal file tools.")

(defn ^:private dir-line
  "One memory-dir line: `- <dir> (<label>) — <tier annotation>`. The annotation
   is why the paths do not need to be repeated in the write guidance: tier and
   path sit on the same line, and the write policy can reference tiers (Global,
   Personal per-project, Shared per-project) by name."
  [{:keys [dir label tier]}]
  (str "- " dir " (" label ")"
       (case tier
         :global " — Global (personal, all projects)"
         :personal " — Personal per-project (default write target)"
         "")))

(defn dirs-block
  "The memory-directory listing as prompt text: one annotated line per dir
   plus the pre-created note. Session-stable (workspace roots do not change
   during a chat), so it rides the static write guidance rather than the
   per-turn index. Returns nil without dirs."
  [dirs]
  (when (seq dirs)
    (string/join "\n"
                 (concat ["Memory directories:"]
                         (map dir-line dirs)
                         [dirs-note]))))

(defn ^:private index-content
  "Per-turn index block text: entries grouped under one heading per memory
   directory (no repeated source slug per line), a `Tags in use:` line per
   group (tags with hidden entries carry a `*`), the shown entry lines
   (entries marked `::shown?`), and a short notice when not all entries are
   shown. Group order and within-group order are taken from the input
   as-is: `scan-memories` already partitions by directory in tier order and
   sorts by the decay-weighted usage score, so re-sorting here would
   discard the usage signal. The memory dir paths are NOT here; they are
   session-stable and live in the static guidance (see `dirs-block`)."
  [entries total-count now-ms]
  (let [groups (partition-by :label entries)
        shown-count (count (filter ::shown? entries))]
    (string/join "\n"
                 (concat
                  ["## Memory"]
                  (mapcat (fn [g]
                            (let [tags-line (tags-summary-line g)]
                              (concat [(str "## " (:label (first g)))]
                                      (when tags-line [tags-line])
                                      (map #(entry-line % now-ms) (filter ::shown? g)))))
                          groups)
                  (when (> total-count shown-count)
                    [(not-listed-notice shown-count total-count (remove ::shown? entries))])))))

(def ^:private global-share
  "Share of the index budget reserved for the global tier. The project tier
   gets the rest. A side that needs less than its share leaves the unused part
   to the other side."
  0.3)

(defn ^:private select-by-share
  "Set of indexes kept from `indexed` (a vector of `[index entry]`) when the
   summed `cost-fn` of the kept entries may not exceed `budget`. Project and
   global entries each keep a prefix of their own order (entries arrive sorted
   by usage score within a group), so the best entries of each side win.
   Global gets at most `global-share` of the budget and project the rest; when
   one side needs less than its part, the other side may use the difference."
  [indexed cost-fn budget]
  (let [global? #(= :global (:tier (second %)))
        project (vec (remove global? indexed))
        global (vec (filter global? indexed))
        need (fn [xs] (reduce + 0 (map (comp cost-fn second) xs)))
        project-need (need project)
        global-need (need global)
        global-quota (* budget global-share)
        project-quota (- budget global-quota)
        [project-cap global-cap] (cond
                                   (<= project-need project-quota) [project-need (- budget project-need)]
                                   (<= global-need global-quota) [(- budget global-need) global-need]
                                   :else [project-quota global-quota])
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
  "Build the per-turn memory index context block.

   `entries` has the output shape of `eca.features.memory/scan-memories`
   (already grouped, and ordered by usage score inside each group). At most
   `max-entries` entries and `max-tokens` total tokens are emitted. The limits
   are shared between the project and global tiers (see `select-by-share`), so
   a large project cannot push all global entries out. Display order stays the
   input order. Entries that do not fit are flagged with a `*` on their tags.
   Returns nil when there is nothing to show: with zero memories the per-turn
   injection buys nothing, because the write targets live in the static
   guidance."
  ([opts]
   (index-context opts (System/currentTimeMillis)))
  ([{:keys [entries max-entries max-tokens]} now-ms]
   (let [entries (vec entries)]
     (when (seq entries)
       (let [max-entries (or max-entries Integer/MAX_VALUE)
             max-tokens (or max-tokens Integer/MAX_VALUE)
             total-count (count entries)
             indexed (vec (map-indexed vector entries))
             counted (let [kept (select-by-share indexed (constantly 1) max-entries)]
                       (filterv #(contains? kept (first %)) indexed))
             line-cost (fn [entry] (inc (shared/estimate-tokens (entry-line entry now-ms))))
             render (fn [token-budget]
                      (let [kept (select-by-share counted line-cost token-budget)
                            marked (mapv (fn [[i entry]] (assoc entry ::shown? (contains? kept i)))
                                         indexed)
                            content (index-content marked total-count now-ms)]
                        {:content content
                         :tokens (shared/estimate-tokens content)
                         :shown (filterv ::shown? marked)}))
             ;; Block overhead (headings, tag lines, notices) is not part of the
             ;; line costs, so shrink the entry budget by the overshoot until the
             ;; whole block fits.
             result (loop [token-budget max-tokens]
                      (let [r (render token-budget)]
                        (cond
                          (<= (:tokens r) max-tokens) r
                          (empty? (:shown r)) nil
                          :else (recur (- token-budget (max 1 (- (:tokens r) max-tokens)))))))]
         (when result
           {:content (:content result)
            :tokens (:tokens result)
            :item-count (count (:shown result))
            :total-count total-count
            :items (mapv (fn [entry]
                           {:name (:name entry)
                            :type (:type entry)
                            :source (:label entry)
                            :path (:path entry)})
                         (:shown result))}))))))

(defn ^:private load-guidance-template*
  "The memory write-guidance template: a classpath resource like every other
   built-in prompt (`resources/prompts/`)."
  []
  (slurp (io/resource "prompts/memory_guidance.md")))

(def ^:private load-guidance-template (memoize load-guidance-template*))

(defn guidance-text
  "Write-policy guidance for the memory subsystem, rendered from
   `prompts/memory_guidance.md`. The internal `template` input supports tests.
   Placed in
   the static system instructions (it never changes during a chat); the
   per-turn memory index injected after the conversation is what it refers
   to. `dirs` (memory-dirs shape) is appended as the annotated directory
   listing; the paths are session-stable, so they belong here rather than in
   the per-turn index. Uses safe template rendering to protect prompt building."
  [{:keys [write-mode template dirs]}]
  (let [write-mode (or write-mode "agent")
        template (if (string/blank? template) (load-guidance-template) template)
        rendered (shared/safe-selmer-render template
                                            {:proactive (= "agent" write-mode)}
                                            "memory-guidance"
                                            "")]
    (if (seq rendered)
      (if-let [dirs-text (dirs-block dirs)]
        (str rendered "\n\n" dirs-text)
        rendered)
      rendered)))

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
   per-turn index the snapshot shows ALL tags, even duplicated ones:
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
            name type (entry-relative-path dir path) age-str usage-str)))

(defn ^:private snapshot-groups
  "Entries partitioned per memory dir, in `dirs` order; within each group the
   scan order (usage-score descending) is preserved. Dir groups without
   entries are dropped — the dir list at the top of the snapshot still names
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
   \"never\"). The closing note reminds the model that reads/last-read count
   local `read_file` consultations only. `now-ms` defaults to the current
   time; inject it explicitly for deterministic output. When `opts` contains a
   non-empty `:skipped` (see `eca.features.memory/skipped-files`), appends a
   section naming the broken files and their skip reasons so the
   consolidating agent can repair or delete them instead of never seeing them."
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
                                     (map #(snapshot-entry-line % dir now-ms) group)))
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
