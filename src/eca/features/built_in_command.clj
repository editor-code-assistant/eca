(ns eca.features.built-in-command
  "Handlers for the `:eca/core` commands, whose code is in
   `eca.features.commands`. They resolve it when called, so declaring the
   commands doesn't load that namespace. The other built-in modules have
   their commands' handlers in their own namespaces.")

(set! *warn-on-reflection* true)

(defn handler
  "A command `:handler` running the built-in command `command-name`."
  [command-name]
  (fn [ctx args]
    ((requiring-resolve 'eca.features.commands/run-built-in!) command-name ctx args)))

(defn commands
  "A module's `:commands` for built-in commands, from `[name description
   arguments]` entries."
  [entries]
  (into {}
        (map (fn [[command-name description arguments]]
               [command-name {:description description
                              :arguments arguments
                              :handler (handler command-name)}]))
        entries))
