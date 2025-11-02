(ns eca.features.tools.filesystem-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.features.tools.util :as tools.util]
   [eca.shared :refer [multi-str]]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]])
  (:import
   [java.io ByteArrayInputStream]))

(deftest directory-tree-test
  (testing "Invalid path"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text (str (h/file-path "/foo/qux") " is not a valid path")}]}
         (with-redefs [fs/canonicalize (constantly (h/file-path "/foo/qux"))
                       fs/exists? (constantly false)]
           ((get-in f.tools.filesystem/definitions ["eca_directory_tree" :handler])
            {"path" (h/file-path "/foo/qux")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "Approval required outside workspace"
    (let [require-approval-fn (get-in f.tools.filesystem/definitions ["eca_directory_tree" :require-approval-fn])
          args {"path" (h/file-path "/foo/qux")}
          db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}]
      (is (true? (require-approval-fn args {:db db})))))
  (testing "allowed dir"
    (is (match?
         {:error false
          :contents [{:type :text
                      :text (multi-str (h/file-path "/foo/bar/baz")
                                       " qux"
                                       ""
                                       "1 directories, 0 files")}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/starts-with? (constantly true)
                       fs/list-dir (fn [path]
                                     (let [p (str path)]
                                       (cond
                                         (= p (h/file-path "/foo/bar/baz"))
                                         [(fs/path (h/file-path "/foo/bar/baz/some.clj"))
                                          (fs/path (h/file-path "/foo/bar/baz/qux"))]
                                         (= p (h/file-path "/foo/bar/baz/qux"))
                                         []))) ; make "qux" an empty directory
                       fs/directory? (fn [path] (not (string/ends-with? (str path) ".clj")))
                       fs/canonicalize (constantly (h/file-path "/foo/bar/baz"))]
           ((get-in f.tools.filesystem/definitions ["eca_directory_tree" :handler])
            {"path" (h/file-path "/foo/bar/baz")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}}))))))

(deftest read-file-test
  (testing "Not readable path"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text (format "File %s is not readable" (h/file-path "/foo/qux"))}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly false)]
           ((get-in f.tools.filesystem/definitions ["eca_read_file" :handler])
            {"path" (h/file-path "/foo/qux")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "Path is a directory"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text (format "%s is a directory, not a file" (h/file-path "/foo/dir"))}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       fs/directory? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["eca_read_file" :handler])
            {"path" (h/file-path "/foo/dir")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "Readable path"
    (is (match?
         {:error false
          :contents [{:type :text
                      :text "fooo"}]}
         (with-redefs [slurp (constantly "fooo")
                       fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       fs/directory? (constantly false)]
           ((get-in f.tools.filesystem/definitions ["eca_read_file" :handler])
            {"path" (h/file-path "/foo/qux")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "with line_offset"
    (is (match?
         {:error false
          :contents [{:type :text
                      :text "line3\nline4\nline5"}]}
         (with-redefs [slurp (constantly "line1\nline2\nline3\nline4\nline5")
                       fs/exists? (constantly true)
                       fs/readable? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["eca_read_file" :handler])
            {"path" (h/file-path "/foo/qux") "line_offset" 2}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "with limit"
    (is (match?
         {:error false
          :contents [{:type :text
                      :text "line1\nline2\n\n[CONTENT TRUNCATED] Showing lines 1 to 2 of 5 total lines. Use line_offset=2 parameter to read more content."}]}
         (with-redefs [slurp (constantly "line1\nline2\nline3\nline4\nline5")
                       fs/exists? (constantly true)
                       fs/readable? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["eca_read_file" :handler])
            {"path" (h/file-path "/foo/qux") "limit" 2}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "with line_offset and limit"
    (is (match?
         {:error false
          :contents [{:type :text
                      :text "line3\nline4\n\n[CONTENT TRUNCATED] Showing lines 3 to 4 of 5 total lines. Use line_offset=4 parameter to read more content."}]}
         (with-redefs [slurp (constantly "line1\nline2\nline3\nline4\nline5")
                       fs/exists? (constantly true)
                       fs/readable? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["eca_read_file" :handler])
            {"path" (h/file-path "/foo/qux") "line_offset" 2 "limit" 2}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}}))))))

(deftest write-file-test
  (testing "Approval required outside workspace"
    (let [require-approval-fn (get-in f.tools.filesystem/definitions ["eca_write_file" :require-approval-fn])
          args {"path" (h/file-path "/foo/qux/new_file.clj")}
          db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar") :name "bar"}]}]
      (is (true? (require-approval-fn args {:db db}))))))

(deftest grep-test
  (testing "invalid pattern"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text "Invalid content regex pattern ' '"}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["eca_grep" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" " "}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "invalid include"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text "Invalid file pattern ' '"}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["eca_grep" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" ".*"
             "include" " "}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "no files found"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text "No files found for given pattern"}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       tools.util/command-available? (fn [command & _args] (= "rg" command))
                       shell/sh (constantly {:out ""})]
           ((get-in f.tools.filesystem/definitions ["eca_grep" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" ".*"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "ripgrep search"
    (is (match?
         {:error false
          :contents [{:type :text
                      :text "/project/foo/bla.txt\n/project/foo/qux.txt"}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       tools.util/command-available? (fn [command & _args] (= "rg" command))
                       shell/sh (constantly {:out "/project/foo/bla.txt\n/project/foo/qux.txt"})]
           ((get-in f.tools.filesystem/definitions ["eca_grep" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" "some-cool-content"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "grep search"
    (is (match?
         {:error false
          :contents [{:type :text
                      :text "/project/foo/bla.txt\n/project/foo/qux.txt"}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       tools.util/command-available? (fn [command & _args] (= "grep" command))
                       shell/sh (constantly {:out "/project/foo/bla.txt\n/project/foo/qux.txt"})]
           ((get-in f.tools.filesystem/definitions ["eca_grep" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" "some-cool-content"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}})))))
  (testing "java grep search"
    (is (match?
         {:error false
          :contents [{:type :text
                      :text (h/file-path "/project/foo/bla.txt")}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       tools.util/command-available? (constantly false)
                       shell/sh (constantly {:out "/project/foo/bla.txt\n/project/foo/qux.txt"})
                       fs/list-dir (constantly [(fs/path (h/file-path "/project/foo/bla.txt"))])
                       fs/canonicalize identity
                       fs/directory? (constantly false)
                       fs/hidden? (constantly false)
                       fs/file (constantly (ByteArrayInputStream. (.getBytes "some-cool-content")))]
           ((get-in f.tools.filesystem/definitions ["eca_grep" :handler])
            {"path" (h/file-path "/project/foo")
             "pattern" "some-cool-content"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}}))))))

(deftest edit-file-test
  (testing "Not readable path"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text (format "File %s is not readable" (h/file-path "/foo/qux"))}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly false)]
           ((get-in f.tools.filesystem/definitions ["eca_edit_file" :handler])
            {"path" (h/file-path "/foo/qux")
             "original_content" "foo"
             "new_content" "bar"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))

  (testing "Original content not found"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text (format "Original content not found in %s" (h/file-path "/foo/bar/my-file.txt"))}]}
         (with-redefs [fs/exists? (constantly true)
                       fs/readable? (constantly true)
                       slurp (constantly "line1\nline2\nline3")]
           ((get-in f.tools.filesystem/definitions ["eca_edit_file" :handler])
            {"path" (h/file-path "/foo/bar/my-file.txt")
             "original_content" "notfound"
             "new_content" "new"}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar") :name "foo"}]}})))))

  (testing "Replace first occurrence only"
    (let [file-content* (atom {})]
      (is (match?
           {:error false
            :contents [{:type :text
                        :text (format "Successfully replaced content in %s." (h/file-path "/project/foo/my-file.txt"))}]}
           (with-redefs [fs/exists? (constantly true)
                         fs/readable? (constantly true)
                         slurp (constantly "a b a c")
                         spit (fn [f content] (swap! file-content* assoc f content))]
             ((get-in f.tools.filesystem/definitions ["eca_edit_file" :handler])
              {"path" (h/file-path "/project/foo/my-file.txt")
               "original_content" "a"
               "new_content" "X"}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}}))))
      (is (match?
           {(h/file-path "/project/foo/my-file.txt") "X b a c"}
           @file-content*))))

  (testing "Replace all occurrences"
    (let [file-content* (atom {})]
      (is (match?
           {:error false
            :contents [{:type :text
                        :text (format "Successfully replaced content in %s." (h/file-path "/project/foo/my-file.txt"))}]}
           (with-redefs [fs/exists? (constantly true)
                         fs/readable? (constantly true)
                         slurp (constantly "foo bar foo baz foo")
                         spit (fn [f content] (swap! file-content* assoc f content))]
             ((get-in f.tools.filesystem/definitions ["eca_edit_file" :handler])
              {"path" (h/file-path "/project/foo/my-file.txt")
               "original_content" "foo"
               "new_content" "Z"
               "all_occurrences" true}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}}))))
      (is (match?
           {(h/file-path "/project/foo/my-file.txt") "Z bar Z baz Z"}
           @file-content*))))

  (testing "Match with CRLF line endings (normalization)"
    (let [file-content* (atom {})
          file-content "line1\nline2\nline3"
          search-content "line1\r\nline2\r\nline3"]
      (is (match?
           {:error false
            :contents [{:type :text
                        :text (format "Successfully replaced content in %s." (h/file-path "/project/foo/my-file.txt"))}]}
           (with-redefs [fs/exists? (constantly true)
                         fs/readable? (constantly true)
                         slurp (constantly file-content)
                         spit (fn [f content] (swap! file-content* assoc f content))]
             ((get-in f.tools.filesystem/definitions ["eca_edit_file" :handler])
              {"path" (h/file-path "/project/foo/my-file.txt")
               "original_content" search-content
               "new_content" "REPLACED"}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}}))))
      (is (match?
           {(h/file-path "/project/foo/my-file.txt") "REPLACED"}
           @file-content*))))

  (testing "Match with trailing whitespace (normalization)"
    (let [file-content* (atom {})
          file-content "line1\nline2\nline3"
          search-content "line1  \nline2   \nline3"]
      (is (match?
           {:error false
            :contents [{:type :text
                        :text (format "Successfully replaced content in %s." (h/file-path "/project/foo/my-file.txt"))}]}
           (with-redefs [fs/exists? (constantly true)
                         fs/readable? (constantly true)
                         slurp (constantly file-content)
                         spit (fn [f content] (swap! file-content* assoc f content))]
             ((get-in f.tools.filesystem/definitions ["eca_edit_file" :handler])
              {"path" (h/file-path "/project/foo/my-file.txt")
               "original_content" search-content
               "new_content" "REPLACED"}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}}))))
      (is (match?
           {(h/file-path "/project/foo/my-file.txt") "REPLACED"}
           @file-content*))))

  (testing "Optimistic retry succeeds when file changed between read and write"
    (let [writes* (atom {})
          slurp-calls* (atom 0)
          initial "a b a c"
          changed "a z a c"
          path (h/file-path "/project/foo/my-file.txt")]
      (is (match?
           {:error false
            :contents [{:type :text
                        :text (format "Successfully replaced content in %s." path)}]}
           (with-redefs [fs/exists? (constantly true)
                         fs/readable? (constantly true)
                         slurp (fn [_]
                                 (let [n (swap! slurp-calls* inc)]
                                   (if (= n 1) initial changed)))
                         spit (fn [f content] (swap! writes* assoc f content))]
             ((get-in f.tools.filesystem/definitions ["eca_edit_file" :handler])
              {"path" path
               "original_content" "a"
               "new_content" "X"}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}}))))
      ;; Should have applied retry on changed content
      (is (match?
           {path "X z a c"}
           @writes*))))

  (testing "Optimistic retry returns conflict when retry cannot match uniquely"
    (let [writes* (atom {})
          slurp-calls* (atom 0)
          initial "A\nB\nC\n"
          changed "A\nQ\nC\n"
          path (h/file-path "/project/foo/my-file.txt")]
      (is (match?
           {:error true
            :contents [{:type :text
                        :text (format (str "File changed since it was read: %s. "
                                           "Re-read the file and retry the edit so we don't overwrite concurrent changes.")
                                      path)}]}
           (with-redefs [fs/exists? (constantly true)
                         fs/readable? (constantly true)
                         slurp (fn [_]
                                 (let [n (swap! slurp-calls* inc)]
                                   (if (= n 1) initial changed)))
                         spit (fn [f content] (swap! writes* assoc f content))]
             ((get-in f.tools.filesystem/definitions ["eca_edit_file" :handler])
              {"path" path
               "original_content" "B"
               "new_content" "X"}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///project/foo") :name "foo"}]}}))))
      ;; No write should have occurred
      (is (empty? @writes*)))))

(deftest move-file-test
  (testing "Not readable source path"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text (format "%s is not a valid path" (h/file-path "/foo/qux"))}]}
         (with-redefs [fs/exists? (constantly false)]
           ((get-in f.tools.filesystem/definitions ["eca_move_file" :handler])
            {"source" (h/file-path "/foo/qux")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar/baz") :name "foo"}]}})))))
  (testing "Destination already exists"
    (is (match?
         {:error true
          :contents [{:type :text
                      :text (format "Path %s already exists" (h/file-path "/foo/bar/other_file.clj"))}]}
         (with-redefs [fs/exists? (constantly true)]
           ((get-in f.tools.filesystem/definitions ["eca_move_file" :handler])
            {"source" (h/file-path "/foo/bar/some_file.clj")
             "destination" (h/file-path "/foo/bar/other_file.clj")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar") :name "foo"}]}})))))
  (testing "Move successfully"
    (is (match?
         {:error false
          :contents [{:type :text
                      :text (format "Successfully moved %s to %s"
                                    (h/file-path "/foo/bar/some_file.clj")
                                    (h/file-path "/foo/bar/other_file.clj"))}]}
         (with-redefs [fs/exists? (fn [path] (not (string/includes? path "other_file.clj")))
                       fs/move (constantly true)]
           ((get-in f.tools.filesystem/definitions ["eca_move_file" :handler])
            {"source" (h/file-path "/foo/bar/some_file.clj")
             "destination" (h/file-path "/foo/bar/other_file.clj")}
            {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar") :name "foo"}]}}))))))

(deftest preview-file-change-test
  (testing "Preview does not modify files"
    (let [spit-called* (atom false)
          original-file-content "line1\nline2\nline3"]
      (is (match?
           {:error false
            :contents [{:type :text
                        :text (format "Change simulation completed for %s. Original file unchanged - preview only." (h/file-path "/foo/bar/my-file.txt"))}]}
           (with-redefs [fs/exists? (constantly true)
                         fs/readable? (constantly true)
                         slurp (constantly original-file-content)
                         spit (fn [& _] (reset! spit-called* true))]
             ((get-in f.tools.filesystem/definitions ["eca_preview_file_change" :handler])
              {"path" (h/file-path "/foo/bar/my-file.txt")
               "original_content" "line2"
               "new_content" "modified line2"}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar") :name "foo"}]}}))))
      ;; Verify that spit was never called (no file modification)
      (is (false? @spit-called*))))

  (testing "Preview handles content not found"
    (let [spit-called* (atom false)]
      (is (match?
           {:error true
            :contents [{:type :text
                        :text (format "Original content not found in %s" (h/file-path "/foo/bar/my-file.txt"))}]}
           (with-redefs [fs/exists? (constantly true)
                         fs/readable? (constantly true)
                         slurp (constantly "line1\nline2\nline3")
                         spit (fn [& _] (reset! spit-called* true))]
             ((get-in f.tools.filesystem/definitions ["eca_preview_file_change" :handler])
              {"path" (h/file-path "/foo/bar/my-file.txt")
               "original_content" "notfound"
               "new_content" "new"}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar") :name "foo"}]}}))))
      ;; Verify that spit was never called even for error case
      (is (false? @spit-called*))))

  (testing "Preview succeeds for new file creation (empty original content)"
    (let [spit-called* (atom false)]
      (is (match?
           {:error false
            :contents [{:type :text
                        :text (format "New file creation simulation completed for %s. File will be created - preview only." (h/file-path "/foo/bar/new-file.txt"))}]}
           (with-redefs [fs/exists? (constantly false)  ; File doesn't exist
                         spit (fn [& _] (reset! spit-called* true))]
             ((get-in f.tools.filesystem/definitions ["eca_preview_file_change" :handler])
              {"path" (h/file-path "/foo/bar/new-file.txt")
               "original_content" ""  ; Empty for new file
               "new_content" "New file content"}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar") :name "foo"}]}}))))
      ;; Verify that spit was never called (no file modification)
      (is (false? @spit-called*))))

  (testing "Preview fails when trying to find content in non-existent file"
    (let [spit-called* (atom false)]
      (is (match?
           {:error true
            :contents [{:type :text
                        :text (format "Preview error for %s: For new files, original_content must be empty string (\"\")." (h/file-path "/foo/bar/missing-file.txt"))}]}
           (with-redefs [fs/exists? (constantly false)  ; File doesn't exist
                         spit (fn [& _] (reset! spit-called* true))]
             ((get-in f.tools.filesystem/definitions ["eca_preview_file_change" :handler])
              {"path" (h/file-path "/foo/bar/missing-file.txt")
               "original_content" "some content"  ; Non-empty for non-existent file
               "new_content" "replacement content"}
              {:db {:workspace-folders [{:uri (h/file-uri "file:///foo/bar") :name "foo"}]}}))))
      ;; Verify that spit was never called
      (is (false? @spit-called*)))))
