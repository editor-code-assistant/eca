(ns eca.file-io
  "Shared file locking and replacement mechanics. Callers own serialization
   and error handling. Locks are advisory: all writers must use the same lock."
  (:require
   [clojure.java.io :as io]
   [eca.logger :as logger])
  (:import
   [java.io File RandomAccessFile]
   [java.nio.channels FileChannel FileLock OverlappingFileLockException]
   [java.nio.file AtomicMoveNotSupportedException CopyOption Files Path StandardCopyOption]
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

(defn ^:private acquire-os-lock
  "The OS lock on `channel`. Without `timeout-ms`, waits as long as needed.
   With it, retries until the deadline and then throws: a process that froze
   while holding the lock must not stop every other process. A lock held by
   another channel in this JVM counts as busy too."
  ^FileLock [^FileChannel channel timeout-ms]
  (if-not timeout-ms
    (.lock channel)
    (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
      (loop []
        (or (try (.tryLock channel)
                 (catch OverlappingFileLockException _ nil))
            (if (< (System/currentTimeMillis) deadline)
              (do (Thread/sleep 20) (recur))
              (throw (ex-info "Timed out waiting for a file lock" {:timeout-ms timeout-ms}))))))))

(defn with-os-file-lock-fn
  "Run f with both a JVM monitor and an exclusive OS lock on lock-file.
   Use a stable sidecar file, not a file that will be replaced. Blocks until
   acquired, or, with `:timeout-ms`, throws when another process holds the
   OS lock longer. The callback must not acquire this OS lock again."
  ([lock-file f] (with-os-file-lock-fn lock-file {} f))
  ([^File lock-file {:keys [timeout-ms]} f]
   #_{:clj-kondo/ignore [:locking-suspicious-lock]}
   (locking (file-lock lock-file)
     (io/make-parents lock-file)
     (let [raf (RandomAccessFile. lock-file "rw")
           channel (.getChannel raf)]
       (try
         (let [lock (acquire-os-lock channel timeout-ms)]
           (try
             (f)
             (finally
               (try (.release lock)
                    (catch Throwable e
                      (logger/warn "[FILE-IO]" "Could not release file lock" e))))))
         (finally
           (try (.close channel) (catch Throwable _))
           (try (.close raf) (catch Throwable _))))))))

(defn ^:private move-file!
  [^Path src ^Path dest options]
  (Files/move src dest ^"[Ljava.nio.file.CopyOption;" (into-array CopyOption options)))

(defn atomic-move!
  "Replace dest with src atomically, with a non-atomic replace fallback when
   the filesystem does not support ATOMIC_MOVE."
  [^File src ^File dest]
  (let [src-path (.toPath src)
        dest-path (.toPath dest)]
    (try
      (move-file! src-path dest-path [StandardCopyOption/ATOMIC_MOVE
                                      StandardCopyOption/REPLACE_EXISTING])
      (catch AtomicMoveNotSupportedException _
        (move-file! src-path dest-path [StandardCopyOption/REPLACE_EXISTING])))))

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
