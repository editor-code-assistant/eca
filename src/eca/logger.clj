(ns eca.logger
  (:require
   [clojure.string :as string])
  (:import
   [ch.qos.logback.classic Level]
   [org.slf4j Logger LoggerFactory MDC]))

(set! *warn-on-reflection* true)

(def ^:private ^Logger eca-logger
  (LoggerFactory/getLogger "eca"))

(def ^:private kw->logback-level
  {:error Level/ERROR
   :warn  Level/WARN
   :info  Level/INFO
   :debug Level/DEBUG})

(defn set-level!
  "Programmatically set the ECA logger level (called from CLI --log-level)."
  [level-kw]
  (when-let [lvl (kw->logback-level level-kw)]
    (.setLevel ^ch.qos.logback.classic.Logger
               (LoggerFactory/getLogger "eca")
               ^Level lvl)))

(defn capture-context
  "Capture a snapshot of the current MDC context map for cross-thread propagation."
  []
  (MDC/getCopyOfContextMap))

(defmacro with-context
  "Restore a captured MDC context map around body. Used for cross-thread propagation."
  [ctx & body]
  `(let [ctx# ~ctx
         prev# (MDC/getCopyOfContextMap)]
     (try
       (if ctx#
         (MDC/setContextMap ctx#)
         (MDC/clear))
       ~@body
       (finally
         (if prev#
           (MDC/setContextMap prev#)
           (MDC/clear))))))

(defmacro with-chat-context
  "Add chat-id and optional parent-chat-id to MDC around body."
  [chat-id parent-chat-id & body]
  `(let [prev-chat# (MDC/get "chat_id")
         prev-parent# (MDC/get "parent_chat_id")
         cid# (some-> ~chat-id str)
         pid# (some-> ~parent-chat-id str)]
     (try
       (if cid# (MDC/put "chat_id" cid#) (MDC/remove "chat_id"))
       (if pid# (MDC/put "parent_chat_id" pid#) (MDC/remove "parent_chat_id"))
       ~@body
       (finally
         (if prev-chat# (MDC/put "chat_id" prev-chat#) (MDC/remove "chat_id"))
         (if prev-parent# (MDC/put "parent_chat_id" prev-parent#) (MDC/remove "parent_chat_id"))))))

(defn ^:private build-message
  "Build a log message string from varargs, extracting Throwable if present.
   Returns [message throwable-or-nil]."
  [args]
  (let [throwable (first (filter #(instance? Throwable %) args))
        parts (remove #(instance? Throwable %) args)
        message (string/join " " (map #(if (nil? %) "nil" (str %)) parts))
        message (if (and throwable (string/blank? message))
                  (or (ex-message throwable) (str throwable))
                  message)]
    [message throwable]))

(defn error [& args]
  (when (.isErrorEnabled eca-logger)
    (let [[^String msg ^Throwable t] (build-message args)]
      (if t (.error eca-logger msg t) (.error eca-logger msg)))))

(defn warn [& args]
  (when (.isWarnEnabled eca-logger)
    (let [[^String msg ^Throwable t] (build-message args)]
      (if t (.warn eca-logger msg t) (.warn eca-logger msg)))))

(defn info [& args]
  (when (.isInfoEnabled eca-logger)
    (let [[^String msg ^Throwable t] (build-message args)]
      (if t (.info eca-logger msg t) (.info eca-logger msg)))))

(defn debug [& args]
  (when (.isDebugEnabled eca-logger)
    (let [[^String msg ^Throwable t] (build-message args)]
      (if t (.debug eca-logger msg t) (.debug eca-logger msg)))))
