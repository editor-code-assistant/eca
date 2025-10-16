(ns eca.features.hooks)

(defn trigger-if-matches! [type data {:keys [on-before-execute on-after-execute]}]
  (case type
    :prePrompt (delay {})
    :postPrompt (delay {})
    :preToolCall (delay nil)
    :postToolCall (delay nil)))
