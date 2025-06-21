(ns eca.features.rules
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [eca.shared :as shared]))

(defn ^:private file-rules [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (let [rules-dir (fs/file (shared/uri->filename uri) ".eca" "rules")]
                   (when (fs/exists? rules-dir)
                     (fs/list-dir rules-dir)))))
       (map (fn [file]
              {:name (fs/file-name file)
               :path (str (fs/canonicalize file))
               :type :user-file
               :content (slurp (fs/file file))}))))

(defn ^:private config-rules [config roots]
  (->> (get config :rules)
       (map
        (fn [{:keys [path]}]
          (if (fs/absolute? path)
            (when (fs/exists? path)
              {:name (fs/file-name path)
               :path path
               :type :user-config
               :content (slurp path)})
            (keep (fn [{:keys [uri]}]
                    (let [f (fs/file (shared/uri->filename uri) path)]
                      (when (fs/exists? f)
                        {:name (fs/file-name f)
                         :path (str (fs/canonicalize f))
                         :type :user-config
                         :content (slurp f)})))
                  roots))))
       (flatten)
       (remove nil?)))

(defn ^:private system-rules []
  [{:name "ECA System"
    :type :system
    :content (str "You are an expert AI coding tool called ECA (Editor Code Assistant)."
                  "Your behavior is to '<behavior>'."
                  "The chat is markdown mode.")}])

(defn all [config roots variables]
  (mapv (fn [rule]
          (reduce
           (fn [rule [k v]]
             (update rule :content #(string/replace % (str "<" (name k) ">") v)))
           rule
           variables))
        (concat (system-rules)
                (config-rules config roots)
                (file-rules roots))))
