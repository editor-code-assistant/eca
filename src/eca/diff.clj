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
         changed-added (if new-file?
                         0
                         (->> deltas
                              (filter #(instance? ChangeDelta %))
                              (mapcat (fn [^ChangeDelta delta]
                                        (.getLines (.getRevised delta))))
                              count))
         changed-removed (if new-file?
                           0
                           (->> deltas
                                (filter #(instance? ChangeDelta %))
                                (mapcat (fn [^ChangeDelta delta]
                                          (.getLines (.getOriginal delta))))
                                count))
         removed (if new-file?
                   0
                   (->> deltas
                        (filter #(instance? DeleteDelta %))
                        (mapcat (fn [^DeleteDelta delta]
                                  (.getLines (.getOriginal delta))))
                        count))]
     {:added (+ added changed-added)
      :removed (+ removed changed-removed)
      :diff
      (->> (DiffUtils/generateUnifiedDiff file file original-lines patch 3)
           (drop 2) ;; removes file header
           unlines)})))

(defn unified-diff-counts
  "Given a unified diff string, return a map with counts of added and removed lines.

  It ignores diff headers (---, +++), hunk markers (@@), and metadata lines starting with \\."
  [diff-text]
  (let [lines (string/split-lines diff-text)]
    (reduce (fn [acc line]
              (cond
                (or (string/starts-with? line "---")
                    (string/starts-with? line "+++")
                    (string/starts-with? line "@@")
                    (string/starts-with? line "\\")
                    (string/blank? line))
                acc

                (string/starts-with? line "+")
                (update acc :added inc)

                (string/starts-with? line "-")
                (update acc :removed inc)

                :else acc))
            {:added 0 :removed 0}
            lines)))
