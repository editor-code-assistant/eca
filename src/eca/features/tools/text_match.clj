(ns eca.features.tools.text-match
  (:require [clojure.string :as string]
            [eca.logger :as logger])
  (:import
   [java.util.regex Pattern]))

(set! *warn-on-reflection* true)

;;; Line Ending Handling

(defn detect-line-ending
  "Detect if content uses CRLF or LF line endings. Returns :crlf or :lf."
  [content]
  (if (string/includes? content "\r\n") :crlf :lf))

(defn normalize-to-lf
  "Normalize all line endings to LF for processing."
  [content]
  (string/replace content #"\r\n|\r" "\n"))

(defn restore-line-ending
  "Restore original line ending style after processing.
   Assumes content might have mixed line endings, so normalizes to LF first."
  [content line-ending-type]
  (case line-ending-type
    :crlf (-> content
              (normalize-to-lf)  ; Ensure clean LF first
              (string/replace "\n" "\r\n"))
    :lf (normalize-to-lf content)))

;;; Trailing Newline Handling

(defn restore-trailing-newline
  "Restore trailing newline if original had one.
   Ensures file ending consistency is preserved."
  [original-content modified-content]
  (let [had-trailing-newline? (string/ends-with? original-content "\n")]
    (cond
      ;; Original had newline, modified doesn't → add it
      (and had-trailing-newline? (not (string/ends-with? modified-content "\n")))
      (str modified-content "\n")

      ;; Original didn't have newline, modified does → remove it
      (and (not had-trailing-newline?) (string/ends-with? modified-content "\n"))
      (subs modified-content 0 (dec (count modified-content)))

      ;; Otherwise keep as-is
      :else
      modified-content)))

;;; Indentation Handling

(defn detect-indentation
  "Extract leading whitespace from a line."
  [line]
  (or (second (re-find #"^(\s*)" line)) ""))

(defn apply-indentation
  "Apply indentation to all lines in content."
  [content indentation]
  (->> (string/split-lines content)
       (map #(str indentation %))
       (string/join "\n")))

;;; Legacy normalization function (kept for backward compatibility)

(defn normalize-for-matching
  "Normalize content for matching: fix line endings and trim trailing whitespace.
   This handles the most common differences between LLM-generated content and files."
  [s]
  (-> s
      (string/replace #"\r\n|\r" "\n")       ; CRLF/CR -> LF
      (string/replace #"(?m)[ \t]+$" "")))

(defn- apply-replacement
  "Apply string replacement, handling all? flag for replacing all occurrences vs first only."
  [content search replacement all?]
  (if all?
    (string/replace content search replacement)
    (string/replace-first content search replacement)))

(defn- try-exact-match
  "Attempt exact string matching without normalization.
   Returns result map if successful, nil if no match found."
  [file-content original-content new-content all?]
  (when (string/includes? file-content original-content)
    {:original-full-content file-content
     :new-full-content (apply-replacement file-content original-content new-content all?)
     :strategy :exact}))

(defn- count-normalized-matches
  "Count how many times the normalized search content appears in normalized file content."
  [normalized-file-content normalized-search-content]
  (count (re-seq (re-pattern (Pattern/quote normalized-search-content)) normalized-file-content)))

(defn- try-normalized-match
  "Attempt normalized matching when exact matching fails.
   Returns result map with success/error status."
  [file-content original-content new-content all? path]
  (let [normalized-file-content (normalize-for-matching file-content)
        normalized-search-content (normalize-for-matching original-content)
        normalized-new-content (normalize-for-matching new-content)
        match-count (count-normalized-matches normalized-file-content normalized-search-content)
        mk-result (fn [new-content' strategy]
                    {:original-full-content file-content
                     :new-full-content new-content'
                     :strategy strategy})]
    (cond
      (= match-count 0)
      (do
        (logger/debug "Content not found in" path)
        {:error :not-found
         :original-full-content file-content})

      (and (> match-count 1) (not all?))
      {:error :ambiguous
       :match-count match-count
       :original-full-content file-content}

      :else
      (do
        (logger/debug "Content matched using normalization for" path
                      "- match count:" match-count
                      "- replacing all:" all?)
        ;; Try original replacement first, fall back to normalized if no change
        (let [original-attempt (apply-replacement file-content original-content new-content all?)]
          (if (= original-attempt file-content)
            ;; Original replacement failed, use normalized
            (mk-result (apply-replacement normalized-file-content
                                          normalized-search-content
                                          normalized-new-content
                                          all?)
                       :normalized)
            ;; Original replacement succeeded
            (mk-result original-attempt :exact)))))))


(defn apply-content-change-to-string
  "Apply content change to a string without file I/O.

   Takes file content as string and attempts to replace original-content with new-content.
   Uses matching strategy with line ending preservation:
   1. Exact match (fastest)
   2. Normalized match (line endings + trailing whitespace)

   Original line ending style (CRLF vs LF) is automatically preserved."
  [file-content original-content new-content all? path]
  ;; Detect original line ending
  (let [line-ending (detect-line-ending file-content)

        ;; Try matching strategies
        result (or
                ;; 1. Try exact match on original content (before normalization)
                (try-exact-match file-content original-content new-content all?)

                ;; 2. Try normalized match - pass ORIGINAL content, it normalizes inside
                (try-normalized-match file-content original-content new-content all? path))]

    ;; Restore original line endings and trailing newline in new-full-content if successful
    (if (:new-full-content result)
      (-> result
          (assoc :original-full-content file-content)
          (update :new-full-content #(restore-trailing-newline file-content %))
          (update :new-full-content restore-line-ending line-ending))
      ;; No match - just fix original-full-content
      (assoc result :original-full-content file-content))))

(defn apply-content-change-to-file
  "Apply content change to a file by path.
   Reads file content and delegates to apply-content-change-to-string."
  [path original-content new-content all?]
  (let [file-content (slurp path)]
    (apply-content-change-to-string file-content original-content new-content all? path)))
