(ns eca.features.built-in-command
  "Handlers for built-in modules' commands whose code is in
   `eca.features.commands`. They resolve it when called: that namespace
   requires feature namespaces that declare these commands, e.g. the agents
   one, which the config namespace requires.")

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
