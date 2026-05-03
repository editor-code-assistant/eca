(ns eca.features.completion.markers)

(set! *warn-on-reflection* true)

(def completion-tag "<ECA_TAG>")
(def cursor-marker "<ECA_CURSOR>")
(def window-start-marker "<ECA_WINDOW_START>")
(def window-end-marker "<ECA_WINDOW_END>")

(def no-edits ::no-edits)

(def whole-line-window-marker-re
  ;; Whole-line window markers (allow leading whitespace and an optional line
  ;; terminator). A line whose entire content is one of these tokens is
  ;; essentially-never user code.
  (re-pattern
   (str "(?m)^[ \\t]*(?:"
        (java.util.regex.Pattern/quote window-start-marker)
        "|"
        (java.util.regex.Pattern/quote window-end-marker)
        ")[ \\t]*\\r?\\n?")))

(def inline-marker-re
  ;; Optional `<` + uppercase/underscore token starting with `ECA_` +
  ;; optional `>`. Permissive on purpose: covers `<ECA_CURSOR>`,
  ;; `<ECA_CURSOR`, `ECA_CURSOR>`, `ECA_CURSOR`, `<ECA_CURSO>`,
  ;; `<ECA_WINDOW_STAR>`, `ECA_WINDOW_EN`, etc.
  #"<?ECA_[A-Z_]{2,}>?")
