(ns eca.modules.built-in
  "The built-in modules, compiled into ECA. Each one is a `module` var in the
   namespace of its feature."
  (:require
   [eca.features.core :as f.core]))

(set! *warn-on-reflection* true)

(def modules
  [f.core/module])
