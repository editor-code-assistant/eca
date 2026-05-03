(ns eca.features.completion.response-encoding.search-replace
  (:require
   [clojure.string :as string]
   [eca.features.completion.response-encoding.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private search-replace-block-re
  "Matches a SEARCH/REPLACE block, tolerating 5-9 marker chars"
  #"(?ms)^<{5,9} SEARCH>?[ \t]*\r?\n(.+?)\r?\n^={5,9}[ \t]*\r?\n(.*?)(?:\r?\n)?^>{5,9} REPLACE[ \t]*$")

;; --- Model output parsing ---

(defn ^:private parse
  "Extract every SEARCH/REPLACE block as a `[needle replacement]` pair."
  [s]
  (->> (re-seq search-replace-block-re (or s ""))
       (mapv (fn [[_ n r]] [n r]))))

(defn ^:private normalize-edits
  [edits]
  (->> edits
       (mapv shared/trim-pair-edges)
       (remove (fn [[n _]] (string/blank? n)))
       vec))

(defn ^:private output->edits
  [output-text]
  (-> output-text
      shared/strip-leaked-markers
      parse
      normalize-edits))

(defn build-items
  [{:keys [output-text] :as opts}]
  (shared/edits->items
   (assoc opts
          :encoder-id "search-replace"
          :edits (output->edits output-text))))
