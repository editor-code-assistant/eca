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
  "Build `[needle replacement {:keys [old-start-line]}]` from a hunk map
  using standard unified diff line prefixes:

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
    [(join-lines #{:both :needle})
     (join-lines #{:both :replacement})
     (if old-start {:old-start-line old-start} {})]))

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
  "Split into hunks then convert each hunk body to a
  `[needle replacement opts]` triple."
  [s]
  (->> (split-hunks s) (mapv hunk->edit)))

(defn ^:private normalize-edits
  "Trim blank-line edges on each hunk edit."
  [edits]
  (->> edits
       (map (fn [[n r opts]]
              (let [[n' r'] (shared/trim-pair-edges [n r])]
                [n' r' opts])))
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
          :encoder-id "udiff"
          :edits (output->edits output-text))))
