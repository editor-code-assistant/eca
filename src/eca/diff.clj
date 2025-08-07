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
   (let [patch (DiffUtils/diff (lines original) (lines revised))
         deltas (.getDeltas patch)
         added (->> deltas
                    (filter #(instance? InsertDelta %))
                    (mapcat (fn [^InsertDelta delta]
                              (.getLines (.getRevised delta))))
                    count)
         changed (->> deltas
                      (filter #(instance? ChangeDelta %))
                      (mapcat (fn [^ChangeDelta delta]
                                (.getLines (.getRevised delta))))
                      count)
         removed (->> deltas
                      (filter #(instance? DeleteDelta %))
                      (mapcat (fn [^DeleteDelta delta]
                                (.getLines (.getOriginal delta))))
                      count)]
     {:added (+ added changed)
      :removed (+ removed changed)
      :diff
      (->> (DiffUtils/generateUnifiedDiff file file (lines original) patch 3)
           (drop 2) ;; removes file header
           unlines)})))
