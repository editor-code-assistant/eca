(ns eca.features.guide
  "ECA's guide, for the `eca-modules` skill and the create agent: ECA's own
   docs pages (`docs/config/*.md`) and config schema (`docs/config.json`),
   shipped with ECA as `eca/guide/*` resources (see build.clj), and a
   reference of the `eca.api` namespaces built from their code. Running from
   source, the pages are read from the repo's `docs/` folder."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.api]
   [eca.api.debug]
   [eca.api.dev]
   [eca.api.fs]
   [eca.api.http]
   [eca.api.json]
   [eca.api.test]
   [eca.shared :as shared])
  (:import
   [java.net URL]))

(set! *warn-on-reflection* true)

(def doc-topics
  "The topics that are pages of docs/config, in the docs' order."
  ["introduction" "models" "tools" "variants" "skills" "rules" "commands" "agents" "hooks" "completion"
   "rewrite" "chat-inline" "context-management" "plugins" "modules" "metrics" "remote" "network"
   "sandboxing" "examples"])

(def ^:private schema-topic "config-schema")

(def ^:private api-topic "eca-api")

(def topics
  "Every topic of the guide."
  (conj doc-topics schema-topic api-topic))

(def ^:private api-namespaces
  '[eca.api eca.api.json eca.api.http eca.api.fs eca.api.dev eca.api.debug eca.api.test])

(defn ^:private source-docs-dir
  "Running from source (tests, `clojure -M`): the repo's docs folder, next to
   the resources folder. nil in a jar or a native image."
  []
  (when-let [^URL url (io/resource "ECA_VERSION")]
    (when (= "file" (.getProtocol url))
      (let [dir (fs/path (fs/parent (fs/parent (io/file url))) "docs")]
        (when (fs/directory? dir)
          dir)))))

(defn ^:private shipped-text
  "The content of docs file `file-name`: `config.json`, or a page of
   docs/config like `modules.md`."
  [file-name]
  (or (some-> (io/resource (str "eca/guide/" file-name)) slurp)
      (when-let [dir (source-docs-dir)]
        (let [path (if (= "config.json" file-name)
                     (fs/path dir file-name)
                     (fs/path dir "config" file-name))]
          (when (fs/regular-file? path)
            (slurp (str path)))))))

(defn ^:private one-line [s]
  (some-> s (string/replace #"\s*\n\s*" " ") string/trim))

(defn ^:private fn-reference [sym v]
  (let [{:keys [arglists doc]} (meta v)]
    (str "- " (if (seq arglists)
                (string/join ", " (map #(str "`" (pr-str (apply list sym %)) "`") arglists))
                (str "`" sym "`"))
         (when doc (str ": " (one-line doc))))))

(defn api-reference
  "Markdown: every public function of the `eca.api` namespaces, with its
   arguments and docstring, taken from the code."
  []
  (string/join
   "\n\n"
   (cons "# The eca.api namespaces\n\nEvery public function, with its arguments and docstring, from ECA's code."
         (for [ns-sym api-namespaces
               :let [the-ns (the-ns ns-sym)]]
           (str "## " ns-sym "\n\n"
                (some-> (:doc (meta the-ns)) one-line (str "\n\n"))
                (string/join "\n" (for [[sym v] (sort-by key (ns-publics the-ns))]
                                    (fn-reference sym v))))))))

(defn topic
  "Topic `topic-name`: `{:topic :description :text}`; for a docs page, its
   text without the front matter. nil when there's no such topic, or this
   ECA was built without its docs."
  [topic-name]
  (cond
    (= api-topic topic-name)
    {:topic api-topic
     :description "Every function of eca.api and its namespaces, with its arguments and docstring, from ECA's code."
     :text (api-reference)}

    (= schema-topic topic-name)
    (when-let [text (shipped-text "config.json")]
      {:topic schema-topic
       :description "The JSON schema of ECA's config.json: every config key, its type, default and doc."
       :text text})

    (some #{topic-name} doc-topics)
    (when-let [text (shipped-text (str topic-name ".md"))]
      (let [{:keys [description body]} (try
                                         (shared/parse-md text)
                                         (catch Exception _ {:body text}))]
        {:topic topic-name
         :description description
         :text (string/trim (or body text))}))

    :else nil))

(defn index
  "The available topics, in order: `[{:topic :description}]`."
  []
  (into [] (keep #(some-> (topic %) (select-keys [:topic :description]))) topics))
