(ns eca.logger)

(set! *warn-on-reflection* true)

(def ^:dynamic *level* :info)

(def ^:private level->value
  {:error 1
   :warn 2
   :info 3
   :debug 4})

(defn ^:private stderr-print [level & args]
  (when (<= (level->value level) (level->value *level*))
    (binding [*out* *err*]
      (apply println args))))

(defn error [& args]
  (apply stderr-print :error args))

(defn warn [& args]
  (apply stderr-print :warn args))

(defn info [& args]
  (apply stderr-print :info args))

(defn debug [& args]
  (apply stderr-print :debug args))
