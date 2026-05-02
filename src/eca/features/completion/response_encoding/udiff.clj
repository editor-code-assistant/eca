(ns eca.features.completion.response-encoding.udiff
  (:require
   [clojure.string :as string]
   [eca.features.completion :as markers]
   [eca.features.completion.response-encoding.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private fence-re
  #"(?s)\A\s*```(?:diff|patch)?\s*\r?\n(.*?)```\s*\z")

(def ^:private file-header-re
  #"(?m)^(?:diff --git .*|index [0-9a-fA-F]+\.\.[0-9a-fA-F]+.*|--- .*|\+\+\+ .*)\r?\n?")

(def ^:private hunk-header-re
  #"(?m)^@@ -(\d+)(?:,\d+)? \+\d+(?:,\d+)? @@.*\r?\n?")

;; --- Hunk parsing ---

(defn ^:private split-hunks
  "Split normalized diff text into hunk maps `{:old-start :body}`.
  With `@@ ... @@` headers present, returns one map per header with the
  parsed old-start line number. With no headers, returns a single hunk
  with `:old-start` nil. Nil for blank input."
  [s]
  (let [s (or s "")]
    (when-not (string/blank? s)
      (let [matcher (re-matcher hunk-header-re s)
            items (loop [acc []]
                    (if (.find matcher)
                      (recur (conj acc {:old-start (Long/parseLong (.group matcher 1))
                                        :header-start (.start matcher)
                                        :body-start (.end matcher)}))
                      acc))]
        (if (seq items)
          (seq (map-indexed (fn [i item]
                              (let [end (if-let [next-item (get items (inc i))]
                                          (:header-start next-item)
                                          (count s))]
                                {:old-start (:old-start item)
                                 :body (subs s (:body-start item) end)}))
                            items))
          [{:old-start nil :body s}])))))

(defn ^:private hunk->edit
  "Build `{:needle :replacement :old-start}` from a hunk map using
  standard unified diff line prefixes:

    ' ' -> context (in both)
    '-' -> needle only
    '+' -> replacement only
    other -> treated as context (tolerance for LLMs that drop the
             leading space on context lines)"
  [{:keys [body old-start]}]
  (let [lines (string/split body #"\r?\n" -1)
        lines (if (and (seq lines) (= "" (peek lines))) (pop lines) lines)
        classified (mapv (fn [^String line]
                           (cond
                             (.startsWith line " ") [:both (subs line 1)]
                             (.startsWith line "-") [:needle (subs line 1)]
                             (.startsWith line "+") [:replacement (subs line 1)]
                             :else [:both line]))
                         lines)
        join-lines (fn [tags]
                     (->> classified
                          (filter (fn [[t]] (contains? tags t)))
                          (map second)
                          (string/join "\n")))]
    {:needle (join-lines #{:both :needle})
     :replacement (join-lines #{:both :replacement})
     :old-start old-start}))

;; --- Model output parsing ---

(defn ^:private normalize-input
  "Strip code fences and file headers so what remains is only the diff body
  (one or more hunks)."
  [s]
  (-> (or s "")
      (markers/strip-leaked-markers)
      (string/replace fence-re "$1")
      (string/replace file-header-re "")))

(defn ^:private parse
  "Split into hunks then convert each hunk body to an edit map."
  [s]
  (->> (split-hunks s) (mapv hunk->edit)))

(defn ^:private normalize-edits
  "Trim blank-line edges on each hunk edit."
  [edits]
  (->> edits
       (map (fn [edit]
              (let [[n r] (shared/trim-pair-edges
                           [(:needle edit) (:replacement edit)])]
                (assoc edit :needle n :replacement r))))
       vec))

(defn ^:private output->edits
  [output-text]
  (-> output-text
      normalize-input
      parse
      normalize-edits))

;; --- Original-buffer matching & application ---

(defn ^:private line-number-at-offset
  "Return the 1-based line number of the character at `offset` in `s`."
  [^String s offset]
  (inc (count (re-seq #"\n" (subs s 0 offset)))))

(defn ^:private find-all-occurrences
  "Return all start offsets of `needle` in `s`."
  [^String s ^String needle]
  (when-not (string/blank? needle)
    (loop [idx 0
           acc []]
      (let [pos (string/index-of s needle idx)]
        (if pos
          (let [p (long pos)]
            (recur (inc p) (conj acc p)))
          (seq acc))))))

(defn ^:private match-edit
  "Match a single edit's needle in the original `doc-text`, returning
  `{:start :end}`. Uses `:old-start` line number to disambiguate when
  there are multiple exact matches. Returns nil on ambiguous or absent
  match."
  [^String doc-text {:keys [needle old-start]}]
  (when-let [positions (find-all-occurrences doc-text needle)]
    (if (= 1 (count positions))
      {:start (first positions)
       :end (+ (first positions) (count needle))}
      (when old-start
        (let [matching (filter (fn [pos]
                                 (= old-start (line-number-at-offset doc-text pos)))
                               positions)]
          (when (= 1 (count matching))
            (let [pos (first matching)]
              {:start pos :end (+ pos (count needle))})))))))

(defn ^:private try-line-aligned-match-edit
  "Fallback: match needle against doc-text line-by-line after collapsing
  whitespace-only lines."
  [^String doc-text {:keys [needle old-start] :as edit}]
  (when-let [match (shared/try-line-aligned-match doc-text needle)]
    (assoc edit :match match)))

(defn ^:private apply-edits-against-original
  "Match all edits against the original `doc-text`. Reject on ambiguous
  match or overlapping ranges. Apply all replacements from end to start
  to produce the single patched string. Returns nil on failure."
  [^String doc-text edits]
  (let [matched (mapv (fn [edit]
                        (or (when-let [m (match-edit doc-text edit)]
                              (assoc edit :match m))
                            (try-line-aligned-match-edit doc-text edit)))
                      edits)]
    (when (every? :match matched)
      (let [sorted (sort-by (comp :start :match) matched)]
        (when (loop [prev nil
                     remaining (seq sorted)]
                (if-let [cur (first remaining)]
                  (if (and prev (> (:end (:match prev)) (:start (:match cur))))
                    false
                    (recur cur (next remaining)))
                  true))
          (reduce (fn [buf {:keys [match replacement]}]
                    (shared/splice buf (:start match) (:end match) (or replacement "")))
                  doc-text
                  (reverse sorted)))))))

(defn build-items
  [{:keys [output-text] :as opts}]
  (shared/edits->items
   (assoc opts
          :encoder-id "udiff"
          :edits (output->edits output-text))))
