(ns eca.diff
  (:require
   [clojure.string :as string]
   [eca.shared :as shared])
  (:import
   [difflib
    ChangeDelta
    DeleteDelta
    DiffUtils
    InsertDelta]))

(set! *warn-on-reflection* true)

(defn ^:private lines
  "Splits S on `\n` or `\r\n`."
  [s]
  (string/split-lines s))

(defn ^:private unlines
  "Joins SS strings coll using the system's line separator."
  [ss]
  (string/join shared/line-separator ss))

(defn diff
  ([original revised file]
   (let [original-lines (lines original)
         revised-lines (lines revised)
         patch (DiffUtils/diff original-lines revised-lines)
         deltas (.getDeltas patch)
         new-file? (= "" original)
         added (if new-file?
                 (count revised-lines)
                 (->> deltas
                      (filter #(instance? InsertDelta %))
                      (mapcat (fn [^InsertDelta delta]
                                (.getLines (.getRevised delta))))
                      count))
         changed (if new-file?
                   0
                   (->> deltas
                        (filter #(instance? ChangeDelta %))
                        (mapcat (fn [^ChangeDelta delta]
                                  (.getLines (.getRevised delta))))
                        count))
         removed (if new-file?
                   0
                   (->> deltas
                        (filter #(instance? DeleteDelta %))
                        (mapcat (fn [^DeleteDelta delta]
                                  (.getLines (.getOriginal delta))))
                        count))]
     {:added (+ added changed)
      :removed (+ removed changed)
      :diff
      (->> (DiffUtils/generateUnifiedDiff file file original-lines patch 3)
           (drop 2) ;; removes file header
           unlines)})))
