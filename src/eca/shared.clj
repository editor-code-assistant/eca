(ns eca.shared
  (:require
   [clojure.string :as string])
  (:import
   [java.net URI]
   [java.nio.file Paths]))

(set! *warn-on-reflection* true)

(def line-separator
  "The system's line separator."
  (System/lineSeparator))

(defn uri->filename [uri]
  (let [uri (URI. uri)]
    (-> uri Paths/get .toString
        ;; WINDOWS drive letters
        (string/replace #"^[a-z]:\\" string/upper-case))))

(defn update-last [coll f]
  (if (seq coll)
    (update coll (dec (count coll)) f)
    coll))

(defn deep-merge [v & vs]
  (letfn [(rec-merge [v1 v2]
            (if (and (map? v1) (map? v2))
              (merge-with deep-merge v1 v2)
              v2))]
    (when (some identity vs)
      (reduce #(rec-merge %1 %2) v vs))))

(defn assoc-some
  "Assoc[iate] if the value is not nil. "
  ([m k v]
   (if (nil? v) m (assoc m k v)))
  ([m k v & kvs]
   (let [ret (assoc-some m k v)]
     (if kvs
       (if (next kvs)
         (recur ret (first kvs) (second kvs) (nnext kvs))
         (throw (IllegalArgumentException.
                 "assoc-some expects even number of arguments after map/vector, found odd number")))
       ret))))

(defn multi-str [& strings] (string/join "\n" (remove nil? strings)))

(defn redact-api-key
  "Given a string, redacts everything after first 4 characters,"
  [s]
  (string/join "" (concat (take 4 s) (repeat (max 0 (- (count s) 4)) \*))))

(defn tokens->cost [input-tokens input-cache-creation-tokens input-cache-read-tokens output-tokens model db]
  (let [normalized-model (if (string/includes? model "/")
                           (last (string/split model #"/"))
                           model)
        {:keys [input-token-cost output-token-cost
                input-cache-creation-token-cost input-cache-read-token-cost]} (get-in db [:models normalized-model])
        input-cost (* input-tokens input-token-cost)
        input-cost (if input-cache-creation-tokens
                     (+ input-cost (* input-cache-creation-tokens input-cache-creation-token-cost))
                     input-cost)
        input-cost (if input-cache-read-tokens
                     (+ input-cost (* input-cache-read-tokens input-cache-read-token-cost))
                     input-cost)]
    (when (and input-token-cost output-token-cost)
      (format "%.2f" (+ input-cost
                        (* output-tokens output-token-cost))))))
