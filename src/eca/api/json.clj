(ns eca.api.json
  "JSON, for modules."
  (:require
   [cheshire.core :as json]))

(set! *warn-on-reflection* true)

(defn parse
  "The data in the JSON text `s`. Object keys become keywords, unless
   `{:keywords? false}`."
  ([s] (parse s nil))
  ([s {:keys [keywords?] :or {keywords? true}}]
   (json/parse-string s (boolean keywords?))))

(defn generate
  "`x` as JSON text; `{:pretty? true}` indents it."
  ([x] (generate x nil))
  ([x {:keys [pretty?]}]
   (json/generate-string x {:pretty (boolean pretty?)})))
