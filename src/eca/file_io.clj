(ns eca.file-io
  "Shared file locking and replacement mechanics. Callers own serialization
   and error handling. Locks are advisory: all writers must use the same lock."
  (:require
   [clojure.java.io :as io]
   [eca.logger :as logger])
  (:import
   [java.io File RandomAccessFile]
   [java.nio.channels FileChannel FileLock]
   [java.nio.file AtomicMoveNotSupportedException CopyOption Files StandardCopyOption]
   [java.nio.file.attribute FileAttribute]
   [java.util.concurrent ConcurrentHashMap]))

(set! *warn-on-reflection* true)

(defonce ^:private ^ConcurrentHashMap file-locks (ConcurrentHashMap.))

(defn file-lock
  "Return a JVM monitor shared by callers using the same absolute file path."
  ^Object [^File f]
  (let [k (.getAbsolutePath f)]
    (or (.get file-locks k)
        (let [o (Object.)]
          (or (.putIfAbsent file-locks k o) o)))))

(defn with-os-file-lock-fn
  "Run f with both a JVM monitor and an exclusive OS lock on lock-file.
   Use a stable sidecar file, not a file that will be replaced. Blocks until
   acquired. The callback must not acquire this OS lock again."
  [^File lock-file f]
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  (locking (file-lock lock-file)
    (io/make-parents lock-file)
    (let [^RandomAccessFile raf (RandomAccessFile. lock-file "rw")
          ^FileChannel channel (.getChannel raf)
          lock-ref (volatile! nil)]
      (try
        (vreset! lock-ref ^FileLock (.lock channel))
        (f)
        (finally
          (when-let [^FileLock lock @lock-ref]
            (try (.release lock)
                 (catch Throwable e
                   (logger/warn "[FILE-IO]" "Could not release file lock" e))))
          (try (.close channel) (catch Throwable _))
          (try (.close raf) (catch Throwable _)))))))

(defn atomic-move!
  "Replace dest with src atomically, with a non-atomic replace fallback when
   the filesystem does not support ATOMIC_MOVE."
  [^File src ^File dest]
  (try
    (Files/move (.toPath src) (.toPath dest)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                        StandardCopyOption/REPLACE_EXISTING]))
    (catch AtomicMoveNotSupportedException _
      (Files/move (.toPath src) (.toPath dest)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))))

(defn replace-file!
  "Call write! with a unique sibling temp file, then replace dest. Removes
   the temp on success or failure. Caller must close streams before returning
   and hold any locks needed for a read-modify-write operation."
  [^File dest write!]
  (io/make-parents dest)
  (let [parent (.getParentFile (.getAbsoluteFile dest))
        tmp (.toFile (Files/createTempFile (.toPath parent)
                                           (str (.getName dest) ".")
                                           ".tmp"
                                           (make-array FileAttribute 0)))]
    (try
      (write! tmp)
      (atomic-move! tmp dest)
      (finally
        (when (.exists tmp)
          (.delete tmp))))))
