(ns eca.features.tools.filesystem
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.diff :as diff]
   [eca.features.index :as f.index]
   [eca.features.tools.path-rules :as f.tools.path-rules]
   [eca.features.tools.smart-edit :as smart-edit]
   [eca.features.tools.text-match :as text-match]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.shared :as shared])
  (:import
   [java.util Base64]))

(set! *warn-on-reflection* true)

(defn ^:private path-validations []
  [["path" fs/exists? "$path is not a valid path"]])

(defn ^:private file-validations []
  (concat (path-validations)
          [["path" fs/readable? "File $path is not readable"]
           ["path" (complement fs/directory?) "$path is a directory, not a file"]]))

(def ^:private directory-tree-max-depth 10)

(def ^:private view-image-max-bytes (* 5 1024 1024))

(def ^:private image-signatures
  "Byte offsets and expected bytes that a file of each media type must start with."
  {"image/png"  [[0 [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]]]
   "image/jpeg" [[0 [0xFF 0xD8 0xFF]]]
   "image/gif"  [[0 [0x47 0x49 0x46 0x38]]]
   "image/webp" [[0 [0x52 0x49 0x46 0x46]]
                 [8 [0x57 0x45 0x42 0x50]]]})

(defn ^:private bytes-at? [^bytes data offset expected]
  (and (>= (alength data) (+ offset (count expected)))
       (every? (fn [[i b]] (= b (bit-and (aget data (+ offset i)) 0xFF)))
               (map-indexed vector expected))))

(defn ^:private image-signature-matches? [^bytes data media-type]
  (every? (fn [[offset expected]] (bytes-at? data offset expected))
          (get image-signatures media-type)))

(defn ^:private human-size [bytes]
  (cond
    (>= bytes 1048576) (str (quot bytes 1048576) " MB")
    (>= bytes 1024) (str (quot bytes 1024) " KB")
    :else (str bytes " B")))

(defn ^:private chat-model-image-input?
  "The chat model's image input capability, nil when the model is unknown."
  [db chat-id]
  (when-let [full-model (get-in db [:chats chat-id :model])]
    (get-in db [:models full-model :image-input?])))

(defn ^:private path->root-filename [db path]
  (let [path (shared/normalize-path path)]
    (->> (:workspace-folders db)
         (map :uri)
         (map shared/uri->filename)
         (filter #(shared/path-inside-root? path %))
         (sort-by count >)
         first)))

(defn ^:private visible-paths
  "Path strings under `path` visible in the tree: the files allowed by the
   `:index :ignoreFiles` config of the workspace `root-filename` plus the
   directories leading to them, since the allowed enumeration returns only
   files. Nil when nothing under `path` is allowed (e.g. `path` itself is
   ignored, like `.git` or a gitignored dir) so callers list it unfiltered."
  [path root-filename config]
  (let [root-str (str path)
        prefix (str root-str fs/file-separator)
        files (->> (f.index/allowed-files root-filename config)
                   (map str)
                   (filter #(string/starts-with? % prefix)))]
    (when (seq files)
      (into (set files)
            (mapcat (fn [file]
                      (->> (iterate fs/parent (fs/parent file))
                           (take-while #(and % (not= (str %) root-str)))
                           (map str))))
            files))))

(defn ^:private contains-file?
  "Whether any file exists below `dir`, stopping at the first one found."
  [dir]
  (let [found* (volatile! false)]
    (try
      (fs/walk-file-tree dir {:visit-file (fn [_ _] (vreset! found* true) :terminate)
                              :visit-file-failed (fn [_ _] :continue)})
      (catch Exception _ nil))
    @found*))

(defn ^:private directory-tree [arguments {:keys [db config]}]
  (let [path (delay (fs/canonicalize (get arguments "path")))]
    (or (tools.util/invalid-arguments arguments (path-validations))
        (let [max-depth (or (get arguments "max_depth") directory-tree-max-depth)
              dir-count* (atom 0)
              file-count* (atom 0)
              lines* (atom [(str @path)])
              root-filename (some-> (path->root-filename db @path) shared/normalize-path)
              visible (when root-filename (visible-paths @path root-filename config))
              visible? (if visible
                         (fn [p] (or (contains? visible (str p))
                                     (and (fs/directory? p) (not (contains-file? p)))))
                         (constantly true))
              walk (fn walk [dir depth]
                     (let [names (->> (fs/list-dir dir)
                                      (remove #(string/starts-with? (fs/file-name %) "."))
                                      (filter visible?)
                                      (map fs/file-name)
                                      sort
                                      vec)
                           indent (apply str (repeat depth " "))]
                       (doseq [name names]
                         (let [abs (fs/path dir name)]
                           (if (fs/directory? abs)
                             (do
                               (swap! dir-count* inc)
                               (swap! lines* conj (str indent name))
                               (when (< depth max-depth)
                                 (walk abs (inc depth))))
                             (do
                               (swap! file-count* inc)
                               (swap! lines* conj (str indent name))))))))]
          (walk @path 1)
          (let [body (string/join "\n" @lines*)
                summary (format "%d directories, %d files" @dir-count* @file-count*)]
            (tools.util/single-text-content (str body "\n\n" summary)))))))

(defn ^:private read-file [{:strs [path] :as arguments} {:keys [db chat-id config] :as ctx}]
  (or (tools.util/invalid-arguments arguments (file-validations))
      (f.tools.path-rules/require-fetched-path-scoped-rules-for-read path ctx)
      (when-let [media-type (shared/image-media-type path)]
        (tools.util/single-text-content
         (str path " is an image (" media-type "), not a text file. "
              (if (chat-model-image-input? db chat-id)
                "Use view_image to see it, or shell_command (file, identify, exiftool) to inspect it."
                "Use shell_command (file, identify, exiftool) to inspect it."))
         :error))
      (let [line-offset                   (or (get arguments "line_offset") 0)
            limit                         (->> [(get arguments "limit")
                                                (get-in config [:toolCall :readFile :maxLines])]
                                               (filter number?)
                                               (apply min))
            full-content-lines            (string/split-lines (slurp (fs/file (fs/canonicalize path))))
            maybe-truncated-content-lines (cond->> full-content-lines
                                            line-offset (drop line-offset)
                                            limit       (take limit))
            was-truncated?                (not= (- (count full-content-lines) line-offset)
                                                (count maybe-truncated-content-lines))
            content                       (string/join "\n" maybe-truncated-content-lines)]
        (tools.util/single-text-content (if was-truncated?
                                          (str content "\n\n"
                                               "[CONTENT TRUNCATED] Showing lines " (if line-offset (inc line-offset) 1)
                                               " to " (+ (or line-offset 0) limit)
                                               " of " (count full-content-lines) " total lines. "
                                               "Use line_offset=" (+ (or line-offset 0) limit)
                                               " parameter to read more content.")
                                          content)))))

(defn ^:private read-file-summary [{:keys [args config]}]
  (if-let [path (get args "path")]
    (let [line-offset (get args "line_offset" 0)
          limit (get args "limit" (get-in config [:toolCall :readFile :maxLines]))
          sub-read (or line-offset limit)]
      (format "Reading %s %s"
              (fs/file-name (fs/file path))
              (str
               (when sub-read
                 (format "(%s-%s)"
                         line-offset
                         (+ line-offset limit))))))
    "Reading file"))

(defn ^:private view-image [{:strs [path] :as arguments} {:keys [db chat-id] :as ctx}]
  (or (tools.util/invalid-arguments arguments (file-validations))
      (f.tools.path-rules/require-fetched-path-scoped-rules-for-read path ctx)
      (let [media-type (shared/image-media-type path)
            file (fs/file (fs/canonicalize path))
            size (when media-type (fs/size file))]
        (cond
          (not media-type)
          (tools.util/single-text-content
           (str path " is not a supported image (png, jpg, jpeg, gif, webp). "
                "Use read_file for text files or shell_command to inspect binary files.")
           :error)

          (> size view-image-max-bytes)
          (tools.util/single-text-content
           (format "Image %s is %s, above the %s limit. Downscale or compress it before viewing."
                   path (human-size size) (human-size view-image-max-bytes))
           :error)

          (not (chat-model-image-input? db chat-id))
          (tools.util/single-text-content
           (str "Model " (get-in db [:chats chat-id :model]) " does not support image input, so " path " cannot be viewed. "
                "If the model does support images, set `imageInput: true` in its config. "
                "Otherwise use shell_command (file, identify, exiftool) to inspect it.")
           :error)

          :else
          (let [data (fs/read-all-bytes file)]
            (if (image-signature-matches? data media-type)
              {:error false
               :contents [{:type :text
                           :text (format "Image %s (%s, %s)" path media-type (human-size size))}
                          {:type :image
                           :media-type media-type
                           :base64 (.encodeToString (Base64/getEncoder) data)}]}
              (tools.util/single-text-content
               (str path " has an image extension but its content is not a valid " media-type " file. "
                    "Use shell_command (file, xxd) to inspect it.")
               :error)))))))

(defn ^:private view-image-enabled?
  "Hidden only when the chat model is known to lack image input."
  [{:keys [db chat-id]}]
  (not (false? (chat-model-image-input? db chat-id))))

(defn ^:private view-image-summary [{:keys [args]}]
  (if-let [path (get args "path")]
    (str "Viewing " (fs/file-name (fs/file path)))
    "Viewing image"))

(defn ^:private write-file [arguments ctx]
  (let [path (get arguments "path")
        content (get arguments "content")]
    (or (f.tools.path-rules/require-fetched-path-scoped-rules path ctx)
        (let [old-content (try (slurp path) (catch Exception _ nil))]
          (fs/create-dirs (fs/parent (fs/path path)))
          (spit path content)
          (assoc (tools.util/single-text-content (format "Successfully wrote to %s" path))
                 :rollback-changes [{:path path
                                     :content old-content}])))))

(defn ^:private write-file-summary [{:keys [args]}]
  (if (get args "path")
    "Creating"
    "Creating file"))

(defn ^:private edit-file-summary [{:keys [args]}]
  (if (get args "path")
    "Editing"
    "Editing file"))

(defn ^:private directory-tree-summary [{:keys [args db]}]
  (if-let [path (get args "path")]
    (let [root (path->root-filename db path)
          display-path (if root
                         (let [rel (str (fs/relativize (fs/path root) (fs/path path)))]
                           (if (= rel "")
                             (fs/file-name (fs/file root))
                             rel))
                         path)]
      (str "Listing tree: " display-path))
    "Listing tree"))

(defn ^:private run-ripgrep [path pattern include output-mode]
  (let [mode-flags (case output-mode
                     "content" ["-n" "--no-heading"]
                     "count" ["--count" "--no-heading"]
                     ;; files_with_matches (default)
                     ["--files-with-matches" "--no-heading"])
        cmd (cond-> (into ["rg"] mode-flags)
              include (concat ["--glob" include])
              :always (concat ["-e" pattern path]))]
    (->> (apply shell/sh cmd)
         :out
         (string/split-lines)
         (filterv #(not (string/blank? %))))))

(defn ^:private run-grep [path pattern ^String include output-mode]
  (let [include-patterns (if (and include (.contains include "{"))
                           (let [pattern-match (re-find #"\*\.\{(.+)\}" include)]
                             (when pattern-match
                               (map #(str "*." %) (clojure.string/split (second pattern-match) #","))))
                           [include])
        mode-flag (case output-mode
                    "content" "-n"
                    "count" "-c"
                    ;; files_with_matches (default)
                    "-l")
        cmd (cond-> ["grep" "-E" mode-flag "-r" "--exclude-dir=.*"]
              (and include (> (count include-patterns) 1)) (concat (mapv #(str "--include=" %) include-patterns))
              include (concat [(str "--include=" include)])
              :always (concat [pattern path]))]
    (->> (apply shell/sh cmd)
         :out
         (string/split-lines)
         (filterv #(not (string/blank? %))))))

(defn ^:private run-java-grep [path pattern include output-mode]
  (let [include-pattern (when include
                          (re-pattern (str ".*\\.("
                                           (-> include
                                               (string/replace #"^\*\." "")
                                               (string/replace #"\*\.\{(.+)\}" "$1")
                                               (string/replace #"," "|"))
                                           ")$")))
        pattern-regex (re-pattern pattern)]
    (letfn [(search-file [file]
              (try
                (with-open [rdr (io/reader (fs/file file))]
                  (let [file-path (str (fs/canonicalize file))]
                    (loop [lines (line-seq rdr)
                           line-num 1
                           matches []
                           match-count 0]
                      (if (seq lines)
                        (if (re-find pattern-regex (first lines))
                          (recur (rest lines)
                                 (inc line-num)
                                 (conj matches {:file file-path
                                                :line-num line-num
                                                :content (first lines)})
                                 (inc match-count))
                          (recur (rest lines) (inc line-num) matches match-count))
                        ;; Return based on output-mode
                        (when (pos? match-count)
                          (case output-mode
                            "content" (mapv #(str (:file %) ":" (:line-num %) ":" (:content %)) matches)
                            "count" [(str file-path ":" match-count)]
                            ;; files_with_matches (default)
                            [file-path]))))))
                (catch Exception _ nil)))
            (search [dir]
              (keep
               (fn [file]
                 (cond
                   (and (fs/directory? file) (not (fs/hidden? file)))
                   (search file)

                   (and (not (fs/directory? file))
                        (or (nil? include-pattern)
                            (re-matches include-pattern (fs/file-name file))))
                   (search-file file)))
               (fs/list-dir dir)))]
      (when (fs/exists? path)
        (flatten (search path))))))

(def ^:private valid-output-modes #{"files_with_matches" "content" "count"})

(defn ^:private grep
  "Searches for files containing patterns using regular expressions.

   This function provides a fast content search across files using three different
   backends depending on what's available:
   1. ripgrep (rg) - fastest, preferred when available
   2. grep - standard Unix tool fallback
   3. Pure Java implementation - slow, but cross-platform fallback

   Supports three output modes:
   - files_with_matches (default): Returns matching file paths only
   - content: Returns matching lines with file path and line numbers
   - count: Returns match counts per file

   Validates that the search path is within allowed workspace directories."
  [arguments _]
  (or (tools.util/invalid-arguments arguments (concat (path-validations)
                                                      [["path" fs/readable? "File $path is not readable"]
                                                       ["pattern" #(and % (not (string/blank? %))) "Invalid content regex pattern '$pattern'"]
                                                       ["include" #(or (nil? %) (not (string/blank? %))) "Invalid file pattern '$include'"]
                                                       ["max_results" #(or (nil? %) number?) "Invalid number '$max_results'"]
                                                       ["output_mode" #(or (nil? %) (valid-output-modes %))
                                                        "Invalid output_mode '$output_mode'. Must be one of: files_with_matches, content, count"]]))
      (let [path (get arguments "path")
            pattern (get arguments "pattern")
            include (get arguments "include")
            max-results (or (get arguments "max_results") 1000)
            output-mode (or (get arguments "output_mode") "files_with_matches")
            results
            (->> (cond
                   (tools.util/command-available? "rg" "--version")
                   (run-ripgrep path pattern include output-mode)

                   (tools.util/command-available? "grep" "--version")
                   (run-grep path pattern include output-mode)

                   :else
                   (run-java-grep path pattern include output-mode))
                 (take max-results))]
        ;; TODO sort by modification time.
        (if (seq results)
          (tools.util/single-text-content (string/join "\n" results))
          (tools.util/single-text-content "No files found for given pattern" :error)))))

(defn grep-summary [{:keys [args]}]
  (if-let [pattern (get args "pattern")]
    (if (> (count pattern) 22)
      (format "Searching: %s..." (subs pattern 0 22))
      (format "Searching: %s" pattern))
    "Searching for files"))

(defn ^:private handle-file-change-result
  "Convert file-change-full-content result to appropriate tool response"
  [result path success-message]
  (cond
    (:new-full-content result)
    (tools.util/single-text-content success-message)

    (= (:error result) :not-found)
    (tools.util/single-text-content (format "Original content not found in %s" path) :error)

    (= (:error result) :ambiguous)
    (tools.util/single-text-content
     (format "Ambiguous match - content appears %d times in %s. Provide more specific context to identify the exact location."
             (:match-count result) path) :error)

    (= (:error result) :conflict)
    (tools.util/single-text-content
     (format (str "File changed since it was read: %s. "
                  "Re-read the file and retry the edit so we don't overwrite concurrent changes.")
             path)
     :error)

    :else
    (tools.util/single-text-content (format "Failed to process %s" path) :error)))

(defn ^:private apply-file-edit-strategy
  "Apply the appropriate edit strategy based on whether all occurrences should be replaced.
   - For all_occurrences=true: uses text-match (exact/normalized only) for predictability
   - For all_occurrences=false: uses smart-edit (multi-tier matching) for better handling"
  [file-content original-content new-content all? path]
  (if all?
    (text-match/apply-content-change-to-string file-content original-content new-content all? path)
    (smart-edit/apply-smart-edit file-content original-content new-content path)))

(defn ^:private edit-file [{:strs [path] :as arguments} ctx]
  (or (tools.util/invalid-arguments arguments (concat (path-validations)
                                                      [["path" fs/readable? "File $path is not readable"]]))
      (f.tools.path-rules/require-fetched-path-scoped-rules path ctx)
      (let [original-content (get arguments "original_content")
            new-content      (get arguments "new_content")
            all?             (boolean (get arguments "all_occurrences"))
            initial-content  (slurp path)
            result           (apply-file-edit-strategy initial-content original-content new-content all? path)
            write!           (fn [res]
                               (spit path (:new-full-content res))
                               (-> (handle-file-change-result res path (format "Successfully replaced content in %s." path))
                                   (assoc :rollback-changes [{:path    path
                                                              :content initial-content}])))]
        (if (:new-full-content result)
          (let [current-content (slurp path)]
            (if (= current-content (:original-full-content result))
              (write! result)
              ;; Optimistic retry once against latest content
              (let [retry (apply-file-edit-strategy current-content original-content new-content all? path)]
                (if (:new-full-content retry)
                  (write! retry)
                  (handle-file-change-result {:error :conflict} path nil)))))
          (handle-file-change-result result path nil)))))

(defn ^:private preview-file-change [arguments _]
  (let [path (get arguments "path")
        original-content (get arguments "original_content")
        new-content (get arguments "new_content")
        all? (boolean (get arguments "all_occurrences"))
        file-exists? (fs/exists? path)]
    (cond
      file-exists?
      (let [result (apply-file-edit-strategy (slurp path) original-content new-content all? path)]
        (handle-file-change-result result path
                                   (format "Change simulation completed for %s. Original file unchanged - preview only." path)))

      (and (not file-exists?) (= "" original-content))
      (tools.util/single-text-content (format "New file creation simulation completed for %s. File will be created - preview only." path))

      :else
      (tools.util/single-text-content
       (format "Preview error for %s: For new files, original_content must be empty string (\"\")."
               path)
       :error))))

(defn ^:private move-file [arguments _]
  (or (tools.util/invalid-arguments arguments [["source" fs/exists? "$source is not a valid path"]
                                               ["destination" (complement fs/exists?) "Path $destination already exists"]])
      (let [source (get arguments "source")
            destination (get arguments "destination")
            directory? (fs/directory? source)
            source-content (when-not directory? (slurp source))]
        (fs/move source destination {:replace-existing false})
        (cond-> (tools.util/single-text-content (format "Successfully moved %s to %s" source destination))
          (not directory?) (assoc :rollback-changes [{:path destination
                                                      :content nil}
                                                     {:path source
                                                      :content source-content}])))))

(defn ^:private move-file-summary [{:keys [args]}]
  (let [source (get args "source")
        destination (get args "destination")]
    (if (and source destination)
      (let [source-parent (some-> source fs/path fs/parent str)
            dest-parent (some-> destination fs/path fs/parent str)]
        (if (= source-parent dest-parent)
          (str "Renaming " (fs/file-name (fs/file source)))
          (str "Moving " (fs/file-name (fs/file source)))))
      "Moving file")))

(def definitions
  {"directory_tree"
   {:description (tools.util/read-tool-description "directory_tree")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to the directory."}
                              "max_depth" {:type "integer"
                                           :description (format "Maximum depth to traverse (default: %s)" directory-tree-max-depth)}}
                 :required ["path"]}
    :handler #'directory-tree
    :require-approval-fn (tools.util/require-approval-when-outside-workspace ["path"])
    :summary-fn #'directory-tree-summary}
   "read_file"
   {:description (tools.util/read-tool-description "read_file")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to the file to read."}
                              "line_offset" {:type "integer"
                                             :description "Line to start reading from (default: 0)"}
                              "limit" {:type "integer"
                                       :description "Maximum lines to read (default: {{readFileMaxLines}})"}}
                 :required ["path"]}
    :handler #'read-file
    :require-approval-fn (tools.util/require-approval-when-outside-workspace ["path"])
    :summary-fn #'read-file-summary}
   "view_image"
   {:description (tools.util/read-tool-description "view_image")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to the image file (png, jpg, jpeg, gif, webp)."}}
                 :required ["path"]}
    :handler #'view-image
    :enabled-fn #'view-image-enabled?
    :require-approval-fn (tools.util/require-approval-when-outside-workspace ["path"])
    :summary-fn #'view-image-summary}
   "write_file"
   {:description (tools.util/read-tool-description "write_file")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to the file to create or overwrite"}
                              "content" {:type "string"
                                         :description "The complete content to write to the file"}}
                 :required ["path" "content"]}
    :handler #'write-file
    :require-approval-fn (tools.util/require-approval-when-outside-workspace ["path"])
    :summary-fn #'write-file-summary}
   "edit_file"
   {:description (tools.util/read-tool-description "edit_file")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute file path to do the replace."}
                              "original_content" {:type "string"
                                                  :description "The exact content to find and replace"}
                              "new_content" {:type "string"
                                             :description "The new content to replace the original content with"}
                              "all_occurrences" {:type "boolean"
                                                 :description "Whether to replace all occurrences of the file or just the first one (default)"}}
                 :required ["path" "original_content" "new_content"]}
    :handler #'edit-file
    :require-approval-fn (tools.util/require-approval-when-outside-workspace ["path"])
    :summary-fn #'edit-file-summary}
   "preview_file_change"
   {:description (tools.util/read-tool-description "preview_file_change")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute file path to preview changes for."}
                              "original_content" {:type "string"
                                                  :description "The exact content to find in the file"}
                              "new_content" {:type "string"
                                             :description "The content to show as replacement in the preview"}
                              "all_occurrences" {:type "boolean"
                                                 :description "Whether to preview replacing all occurrences or just the first one (default)"}}
                 :required ["path" "original_content" "new_content"]}
    :handler #'preview-file-change
    :require-approval-fn (tools.util/require-approval-when-outside-workspace ["path"])
    :summary-fn (constantly "Previewing change")}
   "move_file"
   {:description (tools.util/read-tool-description "move_file")
    :parameters {:type "object"
                 :properties {"source" {:type "string"
                                        :description "The absolute origin file path to move."}
                              "destination" {:type "string"
                                             :description "The new absolute file path to move to."}}
                 :required ["source" "destination"]}
    :handler #'move-file
    :require-approval-fn (tools.util/require-approval-when-outside-workspace ["source" "destination"])
    :summary-fn #'move-file-summary}
   "grep"
   {:description (tools.util/read-tool-description "grep")
    :parameters {:type "object"
                 :properties {"path" {:type "string"
                                      :description "The absolute path to search in."}
                              "pattern" {:type "string"
                                         :description "The regular expression pattern to search for in file contents"}
                              "include" {:type "string"
                                         :description "File pattern to include in the search (e.g. \"*.clj\", \"*.{clj,cljs}\")"}
                              "max_results" {:type "integer"
                                             :description "Maximum number of results to return (default: 1000)"}
                              "output_mode" {:type "string"
                                             :enum ["files_with_matches" "content" "count"]
                                             :description "Output format: 'content' shows matching lines with context, 'files_with_matches' shows only file paths (default), 'count' shows match counts per file"}}
                 :required ["path" "pattern"]}
    :handler #'grep
    :require-approval-fn (tools.util/require-approval-when-outside-workspace ["path"])
    :summary-fn #'grep-summary}})

(defmethod tools.util/tool-call-details-before-invocation :edit_file [_name arguments _server _ctx]
  (let [path (get arguments "path")
        original-content (get arguments "original_content")
        new-content (get arguments "new_content")
        all? (get arguments "all_occurrences")
        file-exists? (and path (fs/exists? path))]
    (cond
      (and file-exists? original-content new-content)
      (let [result (apply-file-edit-strategy (slurp path) original-content new-content (boolean all?) path)
            original-full-content (:original-full-content result)]
        (when original-full-content
          (if-let [new-full-content (:new-full-content result)]
            (let [{:keys [added removed diff]} (diff/diff original-full-content new-full-content path)]
              {:type :fileChange
               :path path
               :linesAdded added
               :linesRemoved removed
               :diff diff})
            (logger/warn "tool-call-details-before-invocation - NO DIFF GENERATED because match failed for path:" path))))

      (and (not file-exists?) (= original-content "") new-content path)
      (let [{:keys [added removed diff]} (diff/diff "" new-content path)]
        {:type :fileChange
         :path path
         :linesAdded added
         :linesRemoved removed
         :diff diff})

      :else nil)))

(defmethod tools.util/tool-call-details-before-invocation :preview_file_change [_name arguments server ctx]
  (tools.util/tool-call-details-before-invocation :edit_file arguments server ctx))

(defmethod tools.util/tool-call-details-before-invocation :write_file [_name arguments _server _ctx]
  (let [path (get arguments "path")
        content (get arguments "content")]
    (when (and path content)
      (let [{:keys [added removed diff]} (diff/diff "" content path)]
        {:type :fileChange
         :path path
         :linesAdded added
         :linesRemoved removed
         :diff diff}))))
