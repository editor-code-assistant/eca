(ns eca.features.rules
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [eca.config :as config]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private global-file-rules []
  (let [xdg-config-home (or (config/get-env "XDG_CONFIG_HOME")
                            (io/file (config/get-property "user.home") ".config"))
        rules-dir (io/file xdg-config-home "eca" "rules")]
    (when (fs/exists? rules-dir)
      (keep (fn [file]
              (when-not (fs/directory? file)
                {:name (fs/file-name file)
                 :path (str (fs/canonicalize file))
                 :type :user-global-file
                 :content (slurp (fs/file file))}))
            (fs/glob rules-dir "**" {:follow-links true})))))

(defn ^:private local-file-rules [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (let [rules-dir (fs/file (shared/uri->filename uri) ".eca" "rules")]
                   (when (fs/exists? rules-dir)
                     (fs/glob rules-dir "**" {:follow-links true})))))
       (keep (fn [file]
               (when-not (fs/directory? file)
                 {:name (fs/file-name file)
                  :path (str (fs/canonicalize file))
                  :type :user-local-file
                  :content (slurp (fs/file file))})))))

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

(defn all [config roots]
  (concat (config-rules config roots)
          (global-file-rules)
          (local-file-rules roots)))
