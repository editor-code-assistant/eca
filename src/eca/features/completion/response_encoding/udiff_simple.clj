(ns eca.features.completion.response-encoding.udiff-simple
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
  #"(?m)^@@.*?@@.*\r?\n?")

(defn ^:private split-hunks
  "Split normalized diff text into hunk bodies. With `@@ ... @@` headers
  present, returns one body per header (preamble and blanks dropped); with
  no headers, returns the whole input as a single body. Nil for blank."
  [s]
  (let [s (or s "")]
    (cond
      (string/blank? s) nil

      (re-find hunk-header-re s)
      (->> (string/split s hunk-header-re)
           rest
           (remove string/blank?)
           seq)

      :else [s])))

(defn ^:private hunk->edit
  "Build `[needle replacement]` from a hunk body using standard unified
  diff line prefixes:

    ' ' -> context (in both)
    '-' -> needle only
    '+' -> replacement only
    other -> treated as context (tolerance for LLMs that drop the
             leading space on context lines)"
  [body]
  (let [lines (string/split body #"\r?\n" -1)
        ;; Trim a trailing empty line introduced by a final newline so we
        ;; don't append a stray "\n" to either side.
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
    [(join-lines #{:both :needle})
     (join-lines #{:both :replacement})]))

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
  "Split into hunks then convert each hunk body to `[needle replacement]`."
  [s]
  (->> (split-hunks s) (mapv hunk->edit)))

(defn ^:private normalize-edits
  "Trim blank-line edges on each hunk edit. Whitespace-only lines inside
  the body are left untouched; the matcher's line-aligned fallback
  handles whitespace drift between model and buffer."
  [edits]
  (->> edits
       (map shared/trim-pair-edges)
       vec))

(defn ^:private output->edits
  [output-text]
  (-> output-text
      normalize-input
      parse
      normalize-edits))

(defn build-items
  [{:keys [output-text] :as opts}]
  (shared/edits->items
   (assoc opts
          :encoder-id "udiff-simple"
          :edits (output->edits output-text))))
