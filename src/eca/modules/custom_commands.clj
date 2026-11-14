(ns eca.modules.custom-commands
  "The `:eca/custom-commands` module: commands from Markdown files and the
   `commands` config, each a prompt template with optional arguments."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.interpolation :as interpolation]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private normalize-command-name [f]
  (string/lower-case (fs/strip-ext (fs/file-name f))))

(defn ^:private markdown-file? [file]
  (and (not (fs/directory? file))
       (string/ends-with? (string/lower-case (str file)) ".md")))

(defn ^:private configured-command-files [path]
  (cond
    (not (fs/exists? path)) []
    (fs/directory? path) (filter markdown-file? (fs/glob path "**" {:follow-links true}))
    :else [path]))

(defn ^:private normalize-frontmatter-arg [a]
  (when (map? a)
    (let [g (fn [k] (if (contains? a k) (get a k) (get a (keyword k))))]
      (cond-> {}
        (some? (g "name")) (assoc :name (str (g "name")))
        (some? (g "description")) (assoc :description (str (g "description")))
        (some? (g "required")) (assoc :required (boolean (g "required")))))))

(defn ^:private merge-command-args
  "Enriches detected args (the source of truth for order/names) with metadata
   from the frontmatter `arguments` list. Named args match by name; positional
   args (arg1, arg2, ...) match by index, letting frontmatter rename them."
  [detected frontmatter-args named?]
  (let [fm (mapv normalize-frontmatter-arg frontmatter-args)]
    (vec (map-indexed
          (fn [idx arg]
            (let [fm-match (if named?
                             (first (filter #(= (:name %) (:name arg)) fm))
                             (nth fm idx nil))]
              (merge arg fm-match)))
          detected))))

(defn ^:private command-file->command [type file opts]
  (try
    (let [base (normalize-command-name file)
          raw (interpolation/replace-dynamic-strings (slurp (str file)) (str (fs/parent file)) nil)
          {:keys [description body] fm-args :arguments} (shared/parse-md raw)
          body (or body raw)
          named? (boolean (seq (shared/named-arg-names body)))
          detected (shared/extract-args-from-content body)]
      (cond-> {:name (if-let [plugin (:plugin opts)]
                       (shared/prefixed-name plugin base)
                       base)
               :path (str (fs/canonicalize file))
               :type type
               :content body
               :arguments (merge-command-args detected fm-args named?)}
        description (assoc :description description)
        (:plugin opts) (assoc :plugin (:plugin opts))))
    (catch Exception e
      (logger/warn "[commands]" (format "Error parsing command file '%s': %s" (str file) (ex-message e)))
      nil)))

(defn ^:private global-file-commands []
  (let [xdg-config-home (or (config/get-env "XDG_CONFIG_HOME")
                            (io/file (config/user-home) ".config"))
        commands-dir (io/file xdg-config-home "eca" "commands")]
    (keep #(command-file->command :user-global-file % {})
          (configured-command-files commands-dir))))

(defn ^:private local-file-commands [roots]
  (->> roots
       (mapcat (fn [{:keys [uri]}]
                 (configured-command-files (fs/file (shared/uri->filename uri) ".eca" "commands"))))
       (keep #(command-file->command :user-local-file % {}))))

(defn ^:private config-commands [config roots]
  (->> (get config :commands)
       (mapcat
        (fn [{:keys [path plugin]}]
          (let [path (str (fs/expand-home path))
                opts (cond-> {}
                       plugin (assoc :plugin plugin))]
            (->> (if (fs/absolute? path)
                   (configured-command-files path)
                   (mapcat (fn [{:keys [uri]}]
                             (configured-command-files (fs/file (shared/uri->filename uri) path)))
                           roots))
                 (keep #(command-file->command :user-config % opts))))))))

(defn all
  "The custom commands: from the `commands` config, from
   ~/.config/eca/commands (unless `pureConfig`) and from each workspace's
   .eca/commands, in that order."
  [config roots]
  (concat (config-commands config roots)
          (when-not (:pureConfig config)
            (global-file-commands))
          (local-file-commands roots)))

(def module
  {:id :eca/custom-commands
   :description "Commands from Markdown files and the commands config."
   ;; read on every listing (~/.config/eca/commands, .eca/commands and config paths), so new
   ;; files show up at once; they are listed and run after the modules' commands
   :config-spec {:commands {:type :array :default []}}})
