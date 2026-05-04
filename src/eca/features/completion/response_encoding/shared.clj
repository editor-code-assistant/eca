(ns eca.features.completion.response-encoding.shared
  "Single-purpose helpers shared by encoders that produce a list of
  `[needle replacement]` edits."
  (:require
   [clojure.string :as string]
   [eca.features.completion-diff :as completion-diff]
   [eca.features.completion.markers :as markers]
   [eca.logger :as logger]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[COMPLETION]")

(defn strip-leaked-markers
  [s]
  (-> (or s "")
      (string/replace markers/whole-line-window-marker-re "")
      (string/replace markers/inline-marker-re "")))

(defn splice
  "Replace `[start end)` of `s` with `replacement`."
  [^String s ^long start ^long end ^String replacement]
  (str (subs s 0 start) replacement (subs s end)))

(defn blank-out-whitespace-only-lines
  "Collapse lines that are only spaces/tabs to truly empty."
  [s]
  (string/replace (or s "") #"(?m)^[ \t]+$" ""))

(defn trim-edges-string
  "Strip leading and trailing blank lines from `s`."
  [s]
  (-> (or s "")
      (string/replace #"\A(?:[ \t]*\r?\n)+" "")
      (string/replace #"(?:\r?\n[ \t]*)+\z" "")))

(defn trim-pair-edges
  "Strip leading and trailing blank lines from both sides of an edit."
  [[needle replacement]]
  [(trim-edges-string needle)
   (trim-edges-string replacement)])

(defn split-keeping-newlines
  "Split `s` into a vector of lines, each retaining its trailing `\\n` (or
  `\\r\\n`) when present. The final line keeps no terminator if `s` did
  not end with one. An empty input returns `[\"\"]`."
  [^String s]
  (if (zero? (count s))
    [""]
    (vec (re-seq #"[^\n]*\n|[^\n]+\z" s))))

(defn collapse-blank-line
  "If `line`'s body (excluding any trailing `\\n` / `\\r\\n`) is whitespace
  but non-empty, collapse it to just its line ending. Other lines are
  returned unchanged."
  [^String line]
  (let [content-end (cond
                      (.endsWith line "\r\n") (- (count line) 2)
                      (.endsWith line "\n")   (- (count line) 1)
                      :else                   (count line))
        content (subs line 0 content-end)]
    (if (and (pos? (count content)) (string/blank? content))
      (subs line content-end)
      line)))

(defn ^:private normalize-line-for-alignment
  "Collapse whitespace-only lines and strip leading whitespace
  from content lines."
  [^String line]
  (string/replace (collapse-blank-line line) #"^[ \t]+" ""))

(defn try-line-aligned-match
  "Match `needle` against `buf` line-by-line after normalizing each line
  (collapsing whitespace-only lines and stripping leading whitespace).
  Returns `{:start :end}` into the original `buf`, or nil when the match is
  absent or ambiguous.

  Only runs when `needle` is not present as a literal substring; otherwise
  the literal interpretation is authoritative (including its ambiguity)."
  [^String buf ^String needle]
  (when (and (not (string/blank? needle))
             (nil? (string/index-of buf needle)))
    (let [buf-lines (split-keeping-newlines buf)
          needle-lines (split-keeping-newlines needle)
          n (count needle-lines)
          n-buf (mapv normalize-line-for-alignment buf-lines)
          n-needle (mapv normalize-line-for-alignment needle-lines)
          windows (->> (range (inc (- (count n-buf) n)))
                       (filter #(= (subvec n-buf % (+ % n)) n-needle)))]
      (when (= 1 (count windows))
        (let [start-line (first windows)
              end-line (+ start-line n)
              start-char (transduce (map count) + 0 (subvec buf-lines 0 start-line))
              end-char (transduce (map count) + 0 (subvec buf-lines 0 end-line))]
          {:start start-char :end end-char})))))

;; --- Unified needle matching ---

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

(defn match-needle
  "Find `needle` in `buf`. Returns `{:start :end}` when uniquely resolved,
  nil when absent or ambiguous.

  Options map keys:
  - `:old-start-line` — when multiple literal matches exist, keep only the
    one whose 1-based line number equals this value (udiff @@ disambiguation).
    Without this, multiple literal matches → nil (ambiguous).

  Falls back to `try-line-aligned-match` when there are zero literal matches."
  [^String buf ^String needle {:keys [old-start-line]}]
  (when-not (string/blank? needle)
    (if-let [positions (find-all-occurrences buf needle)]
      (if (= 1 (count positions))
        {:start (first positions)
         :end (+ (first positions) (count needle))}
        (when old-start-line
          (let [matching (filter (fn [pos]
                                   (= old-start-line (line-number-at-offset buf pos)))
                                 positions)]
            (when (= 1 (count matching))
              (let [pos (first matching)]
                {:start pos :end (+ pos (count needle))})))))
      (try-line-aligned-match buf needle))))

;; --- Apply edits ---

(defn ^:private splice-resolved
  "Apply pre-resolved `{:start :end :replacement}` edits from end to start.
  `resolved` must contain only matched maps (no nils). Edits must be sorted by
  `:start`. Returns nil on overlap or empty input."
  [^String doc-text resolved]
  (when (and (seq resolved) (every? some? resolved))
    (let [sorted (sort-by :start resolved)]
      (when (loop [prev nil
                   remaining (seq sorted)]
              (if-let [cur (first remaining)]
                (if (and prev (> (:end prev) (:start cur)))
                  false
                  (recur cur (next remaining)))
                true))
        (reduce (fn [buf {:keys [start end replacement]}]
                  (splice buf start end replacement))
                doc-text
                (reverse sorted))))))

(defn apply-edits
  "Match each needle against the original `doc-text`, then apply every
  replacement whose needle matched, from end to start.

  Edits whose needle is absent or ambiguous are skipped; hunks that matched
  but overlap another matched hunk still reject the whole splice (returns nil).

  Each edit is either:
  - `[needle replacement]` — standard pair
  - `[needle replacement opts]` — pair with `match-needle` options (e.g.
    `{:old-start-line N}` for udiff @@ line disambiguation).

  Returns the patched string, or nil when no needle matched or overlaps occur
  among the matched ranges."
  [^String doc-text edits]
  (let [resolved (->> edits
                      (mapv (fn [edit]
                              (let [[n r opts] (if (= 3 (count edit))
                                                 edit
                                                 [(nth edit 0) (nth edit 1) {}])]
                                (when-let [m (match-needle doc-text n opts)]
                                  {:start (:start m)
                                   :end (:end m)
                                   :replacement (or r "")}))))
                      (filterv some?))]
    (splice-resolved doc-text resolved)))

;; --- Completion item emission ---

(defn edits->items
  "Apply parsed edit pairs to `doc-text` and emit completion items."
  [{:keys [encoder-id output-text doc-text doc-version edits]}]
  (logger/debug logger-tag
                (format "encoding=%s\n--- raw model output ---\n%s"
                        encoder-id output-text))
  (cond
    (string/blank? (or output-text "")) markers/no-edits
    (empty? edits) markers/no-edits
    :else
    (let [p (apply-edits doc-text edits)]
      (if p
        (or (when-let [{:keys [range text]}
                       (completion-diff/diff-window doc-text p 1)]
              [{:text text
                :doc-version doc-version
                :range range}])
            markers/no-edits)
        markers/no-edits))))
