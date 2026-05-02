(ns eca.features.completion.response-encoding.shared
  "Single-purpose helpers shared by encoders that produce a list of
  `[needle replacement]` edits."
  (:require
   [clojure.string :as string]
   [eca.features.completion-diff :as completion-diff]
   [eca.features.completion :as markers]
   [eca.logger :as logger]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[COMPLETION]")

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

(defn try-match
  "Return `{:start :end}` for the unique exact occurrence of `needle` in
  `buf`, else nil. A blank/empty `needle` never matches."
  [^String buf ^String needle]
  (when-not (string/blank? needle)
    (let [first-idx (string/index-of buf needle)
          last-idx (string/last-index-of buf needle)]
      (when (and first-idx (= first-idx last-idx))
        {:start first-idx :end (+ first-idx (count needle))}))))

(defn try-line-aligned-match
  "Match `needle` against `buf` line-by-line after collapsing
  whitespace-only lines on both sides. Returns `{:start :end}` into the
  original `buf`, or nil when the match is absent or ambiguous.

  Only runs when `needle` is not present as a literal substring; otherwise
  the literal interpretation is authoritative (including its ambiguity)."
  [^String buf ^String needle]
  (when (and (not (string/blank? needle))
             (nil? (string/index-of buf needle)))
    (let [buf-lines (split-keeping-newlines buf)
          needle-lines (split-keeping-newlines needle)
          n (count needle-lines)
          n-buf (mapv collapse-blank-line buf-lines)
          n-needle (mapv collapse-blank-line needle-lines)
          windows (->> (range (inc (- (count n-buf) n)))
                       (filter #(= (subvec n-buf % (+ % n)) n-needle)))]
      (when (= 1 (count windows))
        (let [start-line (first windows)
              end-line (+ start-line n)
              start-char (transduce (map count) + 0 (subvec buf-lines 0 start-line))
              end-char (transduce (map count) + 0 (subvec buf-lines 0 end-line))]
          {:start start-char :end end-char})))))

(defn apply-edits
  "Match every needle/line-aligned-needle against the original `doc-text`,
  reject on overlapping ranges, then apply all replacements from end to
  start. Returns the patched string, or nil when any needle is absent,
  ambiguous, or overlaps another matched range."
  [^String doc-text edits]
  (let [matched (mapv (fn [[needle replacement]]
                        (when-let [m (or (try-match doc-text needle)
                                         (try-line-aligned-match doc-text needle))]
                          {:match m :replacement (or replacement "")}))
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
                    (splice buf (:start match) (:end match) replacement))
                  doc-text
                  (reverse sorted)))))))

;; --- Completion item emission ---

(defn edits->items
  "Apply parsed edit pairs to `doc-text` and emit completion items.
  When `:patched` is provided in opts, it is used as the already-patched
  document instead of running `apply-edits` (which applies edits sequentially)."
  [{:keys [encoder-id output-text doc-text doc-version edits patched] :as opts}]
  (logger/debug logger-tag
                (format "encoding=%s\n--- raw model output ---\n%s"
                        encoder-id output-text))
  (cond
    (string/blank? (or output-text "")) markers/no-edits
    (empty? edits) markers/no-edits
    :else
    (let [p (if (contains? opts :patched) patched (apply-edits doc-text edits))]
      (if p
        (or (when-let [{:keys [range text]}
                       (completion-diff/diff-window doc-text p 1)]
              [{:text text
                :doc-version doc-version
                :range range}])
            markers/no-edits)
        markers/no-edits))))
