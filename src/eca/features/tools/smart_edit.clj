(ns eca.features.tools.smart-edit
  "Smart file editing with advanced matching strategies.

   This namespace implements multi-tier matching for file edits:
   - Flexible matching (whitespace-agnostic with indentation preservation)
   - Regex matching (tokenized matching with flexible whitespace)

   Note: This only supports SINGLE replacement (no multiple occurrences).
   For multiple replacements, use eca.features.tools.text-match instead."
  (:require [clojure.string :as string]
            [eca.features.tools.text-match :as text-match]
            [eca.logger :as logger])
  (:import
   [java.util.regex Pattern]))

(set! *warn-on-reflection* true)

;;; Flexible Matching (Whitespace-Agnostic)

(defn- try-flexible-match
  "Match content ignoring whitespace differences, preserving original indentation.
   Ambiguity prevention: if more than one region matches, return {:error :ambiguous}.
   Returns:
   - {:new-full-content ... :strategy :flexible} on exactly one match
   - {:error :ambiguous, :match-count n, :original-full-content content} if n>1
   - nil if no matches"
  [file-content original-content new-content path]
  (let [file-lines (vec (string/split-lines file-content))
        search-lines (string/split-lines original-content)
        search-lines-trimmed (mapv string/trim search-lines)
        new-lines (string/split-lines new-content)
        search-len (count search-lines)]
    (when (pos? search-len)
      (let [match-indexes (loop [idx 0 acc []]
                            (if (<= (+ idx search-len) (count file-lines))
                              (let [window (subvec file-lines idx (+ idx search-len))
                                    window-trimmed (mapv string/trim window)]
                                (recur (inc idx)
                                       (if (= window-trimmed search-lines-trimmed)
                                         (conj acc idx)
                                         acc)))
                              acc))
            cnt (count match-indexes)]
        (case cnt
          0 nil
          1 (let [idx (first match-indexes)
                  window (subvec file-lines idx (+ idx search-len))
                  ;; Use indentation from the first non-blank line; fallback to first line
                  indentation (->> window
                                   (drop-while string/blank?)
                                   first
                                   (or (first window))
                                   (text-match/detect-indentation))
                  indented-new (text-match/apply-indentation (string/join "\n" new-lines) indentation)
                  indented-new-lines (string/split-lines indented-new)
                  result-lines (concat (take idx file-lines)
                                       indented-new-lines
                                       (drop (+ idx search-len) file-lines))]
              (logger/debug "Content matched using flexible matching for" path)
              {:original-full-content file-content
               :new-full-content (string/join "\n" result-lines)
               :strategy :flexible})
          (do (logger/debug "Flexible match ambiguous for" path "- matches:" cnt)
              {:error :ambiguous
               :match-count cnt
               :original-full-content file-content}))))))

;;; Regex Matching (Tokenized)

(defn- tokenize-by-delimiters
  "Tokenize a search string for flexible regex matching.
   Strategy (similar to Gemini CLI):
   - Insert spaces around common code delimiters so they become separate tokens
   - Split by any whitespace to get minimal tokens
   - Remove empty tokens
   Returns a vector of tokens."
  [s]
  (let [delims ["(" ")" ":" "[" "]" "{" "}" ">" "<" "=" "," ";"]
        spaced (when s (reduce (fn [acc d]
                                 (string/replace acc d (str " " d " ")))
                               s delims))]
    (->> (or (some-> spaced (string/split #"\s+")) [])
         (remove string/blank?)
         vec)))

(defn- try-regex-match
  "Tokenized regex matching with ambiguity prevention.
   - Build a multiline pattern anchored at start-of-line with flexible \\s* between tokens
   - Count all matches across the file
   - If exactly one match, replace it (first only)
   - If >1, return {:error :ambiguous}
   - If 0, return nil"
  [file-content original-content new-content path]
  (let [tokens (tokenize-by-delimiters original-content)]
    (when (seq tokens)
      (let [escaped-tokens (map #(Pattern/quote %) tokens)
            pattern-str (str "(?m)^([ \\t]*)" (string/join "\\s*" escaped-tokens))
            pattern (re-pattern pattern-str)
            matches (re-seq pattern file-content)
            cnt (count matches)]
        (cond
          (= cnt 0) nil
          (> cnt 1) (do (logger/debug "Regex match ambiguous for" path "- matches:" cnt)
                        {:error :ambiguous
                         :match-count cnt
                         :original-full-content file-content})
          :else (let [indentation (some-> matches first second)
                      indented-new (text-match/apply-indentation new-content (or indentation ""))
                      quoted (java.util.regex.Matcher/quoteReplacement indented-new)
                      new-content-str (string/replace-first file-content pattern quoted)]
                  (logger/debug "Content matched using regex matching for" path)
                  {:original-full-content file-content
                   :new-full-content new-content-str
                   :strategy :regex}))))))

(defn apply-smart-edit
  "Apply smart edit with multi-tier matching.
   SINGLE REPLACEMENT ONLY - does not support multiple occurrences.

   Matching order:
   1. Exact match (via text-match)
   2. Normalized match (via text-match)
   3. Flexible match (whitespace-agnostic)
   4. Regex match (tokenized)

   Line ending style (CRLF vs LF) is automatically preserved."
  [file-content original-content new-content path]
  ;; Detect original line ending
  (let [line-ending (text-match/detect-line-ending file-content)
        ;; Normalize to LF for processing
        norm-file (text-match/normalize-to-lf file-content)
        norm-orig (text-match/normalize-to-lf original-content)
        norm-new (text-match/normalize-to-lf new-content)

        ;; Try text-match strategies first (exact + normalized)
        text-match-result (text-match/apply-content-change-to-string file-content original-content new-content false path)

        ;; Try advanced matching if text-match failed
        result (cond
                 (:new-full-content text-match-result)
                 text-match-result

                 (= :ambiguous (:error text-match-result))
                 text-match-result

                 :else
                 (or
                  (try-flexible-match norm-file norm-orig norm-new path)
                  (try-regex-match norm-file norm-orig norm-new path)
                  text-match-result))]

    ;; Restore original line endings and trailing newline if successful,
    ;; and ensure original-full-content reflects the exact pre-edit content
    (if (:new-full-content result)
      (-> result
          (assoc :original-full-content file-content)
          (update :new-full-content #(text-match/restore-trailing-newline file-content %))
          (update :new-full-content text-match/restore-line-ending line-ending))
      result)))
