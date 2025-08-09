(ns eca.features.tools.filesystem
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.features.tools.util :as tools.util]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private allowed-path? [db path]
  (some #(fs/starts-with? path (shared/uri->filename (:uri %)))
        (:workspace-folders db)))

(defn ^:private path-validations [db]
  [["path" fs/exists? "$path is not a valid path"]
   ["path" (partial allowed-path? db) (str "Access denied - path $path outside allowed directories: " (tools.util/workspace-roots-strs db))]])

(defn ^:private directory-tree [arguments {:keys [db]}]
  (let [path (delay (fs/canonicalize (get arguments "path")))]
    (or (tools.util/invalid-arguments arguments (path-validations db))
        (tools.util/single-text-content
         (reduce
          (fn [out path]
            (str out
                 (format "[%s] %s\n"
                         (if (fs/directory? path) "DIR" "FILE")
                         path)))
          ""
          (fs/list-dir @path))))))

(def ^:private read-file-max-lines 2000)

(defn ^:private read-file [arguments {:keys [db]}]
  (or (tools.util/invalid-arguments arguments (concat (path-validations db)
                                                      [["path" fs/readable? "File $path is not readable"]
                                                       ["path" (complement fs/directory?) "$path is a directory, not a file"]]))
      (let [line-offset (get arguments "line_offset")
            limit (or (get arguments "limit") read-file-max-lines)
            content (cond-> (slurp (fs/file (fs/canonicalize (get arguments "path"))))
                      line-offset (->> (string/split-lines)
                                       (drop line-offset)
                                       (string/join "\n"))
                      limit (->> (string/split-lines)
                                 (take limit)
                                 (string/join "\n")))]
        (tools.util/single-text-content content))))

(defn ^:private read-file-summary [args]
  (if-let [path (get args "path")]
    (str "Reading file " (fs/file-name (fs/file path)))
    "Reading file"))

(defn ^:private write-file [arguments {:keys [db]}]
  (or (tools.util/invalid-arguments arguments [["path" (partial allowed-path? db) (str "Access denied - path $path outside allowed directories: " (tools.util/workspace-roots-strs db))]])
      (let [path (get arguments "path")
            content (get arguments "content")]
        (fs/create-dirs (fs/parent (fs/path path)))
        (spit path content)
        (tools.util/single-text-content (format "Successfully wrote to %s" path)))))

(defn ^:private write-file-summary [args]
  (if-let [path (get args "path")]
    (str "Creating file " (fs/file-name (fs/file path)))
    "Creating file"))

(defn ^:private run-ripgrep [path pattern include]
  (let [cmd (cond-> ["rg" "--files-with-matches" "--no-heading"]
              include (concat ["--glob" include])
              :always (concat ["-e" pattern path]))]
    (->> (apply shell/sh cmd)
         :out
         (string/split-lines)
         (filterv #(not (string/blank? %))))))

(defn ^:private run-grep [path pattern ^String include]
  (let [include-patterns (if (and include (.contains include "{"))
                           (let [pattern-match (re-find #"\*\.\{(.+)\}" include)]
                             (when pattern-match
                               (map #(str "*." %) (clojure.string/split (second pattern-match) #","))))
                           [include])
        cmd (cond-> ["grep" "-E" "-l" "-r" "--exclude-dir=.*"]
              (and include (> (count include-patterns) 1)) (concat (mapv #(str "--include=" %) include-patterns))
              include (concat [(str "--include=" include)])
              :always (concat [pattern path]))]
    (->> (apply shell/sh cmd)
         :out
         (string/split-lines)
         (filterv #(not (string/blank? %))))))

(defn ^:private run-java-grep [path pattern include]
  (let [include-pattern (when include
                          (re-pattern (str ".*\\.("
                                           (-> include
                                               (string/replace #"^\*\." "")
                                               (string/replace #"\*\.\{(.+)\}" "$1")
                                               (string/replace #"," "|"))
                                           ")$")))
        pattern-regex (re-pattern pattern)]
    (letfn [(search [dir]
              (keep
               (fn [file]
                 (cond
                   (and (fs/directory? file) (not (fs/hidden? file)))
                   (search file)

                   (and (not (fs/directory? file))
                        (or (nil? include-pattern)
                            (re-matches include-pattern (fs/file-name file))))
                   (try
                     (with-open [rdr (io/reader (fs/file file))]
                       (loop [lines (line-seq rdr)]
                         (when (seq lines)
                           (if (re-find pattern-regex (first lines))
                             (str (fs/canonicalize file))
                             (recur (rest lines))))))
                     (catch Exception _ nil))))
               (fs/list-dir dir)))]
      (when (fs/exists? path)
        (flatten (search path))))))

(defn ^:private grep
  "Searches for files containing patterns using regular expressions.

   This function provides a fast content search across files using three different
   backends depending on what's available:
   1. ripgrep (rg) - fastest, preferred when available
   2. grep - standard Unix tool fallback
   3. Pure Java implementation - slow, but cross-platform fallback

   Returns matching file paths, prioritizing by modification time when possible.
   Validates that the search path is within allowed workspace directories."
  [arguments {:keys [db]}]
  (or (tools.util/invalid-arguments arguments (concat (path-validations db)
                                                      [["path" fs/readable? "File $path is not readable"]
                                                       ["pattern" #(and % (not (string/blank? %))) "Invalid content regex pattern '$pattern'"]
                                                       ["include" #(or (nil? %) (not (string/blank? %))) "Invalid file pattern '$include'"]
                                                       ["max_results" #(or (nil? %) number?) "Invalid number '$max_results'"]]))
      (let [path (get arguments "path")
            pattern (get arguments "pattern")
            include (get arguments "include")
            max-results (or (get arguments "max_results") 1000)
            paths
            (->> (cond
                   (tools.util/command-available? "rg" "--version")
                   (run-ripgrep path pattern include)

                   (tools.util/command-available? "grep" "--version")
                   (run-grep path pattern include)

                   :else
                   (run-java-grep path pattern include))
                 (take max-results))]
        ;; TODO sort by modification time.
        (if (seq paths)
          (tools.util/single-text-content (string/join "\n" paths))
          (tools.util/single-text-content "No files found for given pattern" :error)))))

(defn grep-summary [args]
  (if-let [pattern (get args "pattern")]
    (format "Searching for '%s'" pattern)
    "Searching for files"))

(defn file-change-full-content [path original-content new-content all?]
  (let [original-full-content (slurp path)
        new-full-content (if all?
                           (string/replace original-full-content original-content new-content)
                           (string/replace-first original-full-content original-content new-content))]
    (when (string/includes? original-full-content original-content)
      {:original-full-content original-full-content
       :new-full-content new-full-content})))

(defn ^:private edit-file [arguments {:keys [db]}]
  (or (tools.util/invalid-arguments arguments (concat (path-validations db)
                                                      [["path" fs/readable? "File $path is not readable"]]))
      (let [path (get arguments "path")
            original-content (get arguments "original_content")
            new-content (get arguments "new_content")
            all? (boolean (get arguments "all_occurrences"))]
        (if-let [{:keys [new-full-content]} (file-change-full-content path original-content new-content all?)]
          (do
            (spit path new-full-content)
            (tools.util/single-text-content (format "Successfully replaced content in %s." path)))
          (tools.util/single-text-content (format "Original content not found in %s" path) :error)))))

(defn ^:private move-file [arguments {:keys [db]}]
  (let [workspace-dirs (tools.util/workspace-roots-strs db)]
    (or (tools.util/invalid-arguments arguments [["source" fs/exists? "$source is not a valid path"]
                                                 ["source" (partial allowed-path? db) (str "Access denied - path $source outside allowed directories: " workspace-dirs)]
                                                 ["destination" (partial allowed-path? db) (str "Access denied - path $destination outside allowed directories: " workspace-dirs)]
                                                 ["destination" (complement fs/exists?) "Path $destination already exists"]])
        (let [source (get arguments "source")
              destination (get arguments "destination")]
          (fs/move source destination {:replace-existing false})
          (tools.util/single-text-content (format "Successfully moved %s to %s" source destination))))))

(def definitions
  {"eca_directory_tree"
   {:description (str "Returns a recursive tree view of files and directories starting from the specified path. "
                      "The path parameter must be an absolute path, not a relative path. "
                      "**Only works within the directories: $workspaceRoots.**")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to the directory."}
                              "max_depth" {:type "integer"
                                           :description "Maximum depth to traverse (optional)"}
                              "limit" {:type "integer"
                                       :description "Maxium number of entries to show (default: 100)"}}
                 :required ["path"]}
    :handler #'directory-tree
    :summary-fn (constantly "Listing file tree")}
   "eca_read_file"
   {:description (str "Read the contents of a file from the file system. "
                      "Use this tool when you need to examine "
                      "the contents of a single file. Optionally use the 'line_offset' and/or 'limit' "
                      "parameters to read specific contents of the file when you know the range. "
                      "Prefer call once this tool over multiple calls passing small offsets. "
                      "**Only works within the directories: $workspaceRoots.**")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to the file to read."}
                              "line_offset" {:type "integer"
                                             :description "Line to start reading from (default: 0)"}
                              "limit" {:type "integer"
                                       :description (str "Maximum lines to read (default: " read-file-max-lines ")")}}
                 :required ["path"]}
    :handler #'read-file
    :summary-fn #'read-file-summary}
   "eca_write_file"
   {:description (str "Create a new file or completely overwrite an existing file with new content. "
                      "This tool will automatically create any necessary parent directories if they don't exist. "
                      "Use this tool when you want to create a new file from scratch or completely replace "
                      "the entire content of an existing file. For partial edits or content replacement within "
                      "existing files, use eca_edit_file instead. "
                      "**Only works within the directories: $workspaceRoots.**")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to the file to create or overwrite"}
                              "content" {:type "string"
                                         :description "The complete content to write to the file"}}
                 :required ["path" "content"]}
    :handler #'write-file
    :summary-fn #'write-file-summary}
   "eca_edit_file"
   {:description  (str "Replace a specific string or content block in a file with new content. "
                       "Finds the exact original content and replaces it with new content. "
                       "Be extra careful to format the original-content exactly correctly, "
                       "taking extra care with whitespace and newlines. In addition to replacing strings, "
                       "this can also be used to prepend, append, or delete contents from a file.")
    :parameters  {:type "object"
                  :properties {"path" {:type "string"
                                       :description "The absolute file path to do the replace."}
                               "original_content" {:type "string"
                                                   :description "The exact content to find and replace"}
                               "new_content" {:type "string"
                                              :description "The new content to replace the original content with"}
                               "all_occurrences" {:type "boolean"
                                                  :description "Whether to replace all occurences of the file or just the first one (default)"}}
                  :required ["path" "original_content" "new_content"]}
    :handler #'edit-file
    :summary-fn (constantly "Editting file")}
   "eca_move_file"
   {:description (str "Move or rename files and directories. Can move files between directories "
                      "and rename them in a single operation. If the destination exists, the "
                      "operation will fail. Works across different directories and can be used "
                      "for simple renaming within the same directory. "
                      "Both source and destination must be within the directories: $workspaceRoots.")
    :parameters  {:type "object"
                  :properties {"source" {:type "string"
                                         :description "The absolute origin file path to move."}
                               "destination" {:type "string"
                                              :description "The new absolute file path to move to."}}
                  :required ["source" "destination"]}
    :handler #'move-file
    :summary-fn (constantly "Moving file")}
   "eca_grep"
   {:description (str "Fast content search tool that works with any codebase size. "
                      "Finds the paths to files that have matching contents using regular expressions. "
                      "Supports full regex syntax (eg. \"log.*Error\", \"function\\s+\\w+\", etc.). "
                      "Filter files by pattern with the include parameter (eg. \"*.js\", \"*.{ts,tsx}\"). "
                      "Returns matching file paths sorted by modification time. "
                      "Use this tool when you need to find files containing specific patterns.")
    :parameters  {:type "object"
                  :properties {"path" {:type "string"
                                       :description "The absolute path to search in."}
                               "pattern" {:type "string"
                                          :description "The regular expression pattern to search for in file contents"}
                               "include" {:type "string"
                                          :description "File pattern to include in the search (e.g. \"*.clj\", \"*.{clj,cljs}\")"}
                               "max_results" {:type "integer"
                                              :description "Maximum number of results to return (default: 1000)"}}
                  :required ["path" "pattern"]}
    :handler #'grep
    :summary-fn #'grep-summary}})
