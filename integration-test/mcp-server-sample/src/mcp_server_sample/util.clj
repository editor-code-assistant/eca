(ns mcp-server-sample.util)

(defn log [& args]
  (binding [*out* *err*]
    (println args)))
