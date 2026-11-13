(ns eca.api.fs
  "Files and folders, for modules. Paths are strings; give absolute ones,
   e.g. under `eca.api/workspaces` or `eca.api/data-dir`: relative ones
   resolve against ECA's working directory."
  (:require
   [babashka.fs :as fs]))

(set! *warn-on-reflection* true)

(defn path
  "The path made of `parts`, e.g. `(path dir \"notes\" \"a.md\")`."
  [part & parts]
  (str (apply fs/path part parts)))

(defn exists?
  "Whether there's a file or folder at `path`."
  [path]
  (fs/exists? path))

(defn directory?
  "Whether there's a folder at `path`."
  [path]
  (fs/directory? path))

(defn file-name
  "The last part of `path`."
  [path]
  (str (fs/file-name path)))

(defn parent
  "The folder `path` is in, or nil."
  [path]
  (some-> (fs/parent path) str))

(defn read-file
  "The text of the file at `path` (UTF-8)."
  [path]
  (slurp (fs/file path)))

(defn write-file!
  "Writes the text `content` to the file at `path` (UTF-8), making its folder
   if needed. With `{:append? true}`, adds it at the end of the file."
  ([path content] (write-file! path content nil))
  ([path content {:keys [append?]}]
   (some-> (fs/parent path) fs/create-dirs)
   (spit (fs/file path) (str content) :append (boolean append?))
   nil))

(defn create-dirs!
  "Makes the folder at `path`, and its parents; returns the path."
  [path]
  (str (fs/create-dirs path)))

(defn delete!
  "Deletes the file at `path`, or the folder and all in it."
  [path]
  (fs/delete-tree path)
  nil)

(defn list-dir
  "The paths in the folder at `path`, sorted."
  [path]
  (vec (sort (map str (fs/list-dir path)))))

(defn glob
  "The paths under `root` matching `pattern`, sorted, e.g. `(glob dir \"**.md\")`."
  [root pattern]
  (vec (sort (map str (fs/glob root pattern)))))

(defn last-modified-ms
  "When the file at `path` last changed, in milliseconds since the epoch."
  [path]
  (.toMillis ^java.nio.file.attribute.FileTime (fs/last-modified-time path)))
