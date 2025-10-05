(ns eca.features.hooks)

(defn trigger-if-matches! [type data {:keys [on-before-execute]}]
  (case type
    :prePrompt (delay {})
    :postPrompt (delay {}))
  )
