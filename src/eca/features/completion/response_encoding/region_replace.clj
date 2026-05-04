(ns eca.features.completion.response-encoding.region-replace
  "Whole-window rewrite encoder. The model emits the rewritten editable
  window, which is normalized into one `[window rewritten-window]` edit."
  (:require
   [eca.features.completion.response-encoding.shared :as shared]
   [eca.shared :as eca-shared]))

(set! *warn-on-reflection* true)

(defn ^:private normalize-input
  "Strip wrappers and leaked prompt markers from a whole-window rewrite."
  [s]
  (-> s
      shared/strip-leaked-markers
      eca-shared/normalize-code-result
      shared/blank-out-whitespace-only-lines
      shared/trim-edges-string))

(defn build-items
  [{:keys [output-text region-input] :as opts}]
  (let [window (:window region-input)
        normalized-text (normalize-input output-text)]
    (shared/edits->items
     (assoc opts
            :encoder-id "region-replace"
            :edits [[window normalized-text]]))))
