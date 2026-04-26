(ns eca.features.completion-diff
  "Pure helpers for computing a single replacement edit (range + text) from a
  rewritten window of source code, used by `completion/inline` to produce
  region-replace suggestions."
  (:require
   [clojure.string :as string]))

(set! *warn-on-reflection* true)

(defn ^:private common-prefix-length
  "Number of characters at the start of `a` and `b` that match."
  [^String a ^String b]
  (let [n (min (.length a) (.length b))]
    (loop [i 0]
      (if (and (< i n)
               (= (.charAt a i) (.charAt b i)))
        (recur (inc i))
        i))))

(defn ^:private common-suffix-length
  "Number of characters at the end of `a` and `b` that match, up to `max-len`.
  `max-len` MUST be capped so the suffix cannot overlap with an already-counted
  prefix on either string."
  [^String a ^String b max-len]
  (let [a-len (.length a)
        b-len (.length b)]
    (loop [j 0]
      (if (and (< j max-len)
               (= (.charAt a (- a-len 1 j))
                  (.charAt b (- b-len 1 j))))
        (recur (inc j))
        j))))

(defn ^:private offset->position
  "Convert a 0-based char offset within `text` to a 1-based protocol position
  `{:line :character}`. The text is assumed to begin at line `start-line`
  (1-based) and column 1."
  [^String text offset start-line]
  (let [prefix (subs text 0 offset)
        line-offset (count (re-seq #"\n" prefix))
        last-nl (string/last-index-of prefix "\n")
        col-offset (if last-nl
                     (- offset (inc (long last-nl)))
                     offset)]
    {:line (+ start-line line-offset)
     :character (inc col-offset)}))

(defn diff-window
  "Compare an original window of code with its rewritten version and return a
  single replacement edit `{:range {:start :end} :text}` suitable for a
  `completion/inline` response.

  - `orig` and `new` are full strings. The window MUST begin at protocol line
    `start-line` (1-based) and column 1. Newlines are preserved verbatim, so
    pass the slices in the buffer's own line endings (assumed `\\n`).
  - Returns `nil` when both windows are identical.
  - The algorithm trims the longest common prefix and longest common suffix
    (character-wise) so that `range` covers the smallest changed character
    span, and `text` is the matching replacement. A pure suffix append yields
    a zero-width range at the insertion point."
  [^String orig ^String new start-line]
  (when-not (= orig new)
    (let [orig-len (.length orig)
          new-len (.length new)
          prefix-len (common-prefix-length orig new)
          max-suffix (min (- orig-len prefix-len) (- new-len prefix-len))
          suffix-len (common-suffix-length orig new max-suffix)
          start-offset prefix-len
          end-offset (- orig-len suffix-len)
          replacement-end (- new-len suffix-len)
          replacement (subs new prefix-len replacement-end)]
      {:range {:start (offset->position orig start-offset start-line)
               :end (offset->position orig end-offset start-line)}
       :text replacement})))

(defn extract-window
  "Extract a window of `doc-text` centered on `cursor-line` (1-based) with
  `radius` lines on either side, plus the line itself.

  Returns `{:window :start-line :end-line}` where `:start-line` is the
  1-based protocol line of the first line of `:window`, `:end-line` the
  1-based line of the last line, and `:window` is the joined window text
  with `\\n` line separators (no trailing newline). Out-of-range bounds are
  clamped to the document."
  [doc-text cursor-line radius]
  (let [lines (if (seq doc-text)
                (string/split doc-text #"\n" -1)
                [""])
        n (count lines)
        start-line (max 1 (- cursor-line radius))
        end-line (min n (+ cursor-line radius))
        slice (subvec (vec lines) (dec start-line) end-line)]
    {:window (string/join "\n" slice)
     :start-line start-line
     :end-line end-line}))

(defn ^:private clamp-character
  "Clamp `character` to `[1, line-length+1]`."
  [character ^String line]
  (max 1 (min character (inc (.length line)))))

(defn cursor-position-in-window
  "Map an absolute cursor `{:line :character}` to its 0-based offset inside
  `window` whose first line is `start-line`. Returns `nil` if the cursor is
  outside the window."
  [window {:keys [line character]} start-line]
  (when (and window line character)
    (let [lines (string/split window #"\n" -1)
          end-line (+ start-line (dec (count lines)))]
      (when (and (>= line start-line) (<= line end-line))
        (let [row (- line start-line)
              before (subvec lines 0 row)
              line-str (nth lines row)
              col (dec (clamp-character character line-str))
              ;; Sum line lengths + 1 char each for the joining \n.
              base (reduce + 0 (map #(inc (count ^String %)) before))]
          (+ base col))))))
