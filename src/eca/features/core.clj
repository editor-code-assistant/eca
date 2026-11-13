(ns eca.features.core
  "The `:eca/core` module: what stays in core (protocol, persistence, config,
   the chat loop and the module runtime), as a module so its config keys,
   events and commands are listed like any other module's. It can't be
   disabled."
  (:require
   [eca.modules.events :as events]))

(set! *warn-on-reflection* true)

(def module
  {:id :eca/core
   :description "Chats, config, models and the module runtime."
   :config-spec {:modules {:type :map
                           :doc "Per module id: enabled, source and priority."}
                 :disabledTools {:type :array :default []}
                 :toolCall {:outputTruncation {:type :map :default {:lines 2000 :sizeKb 50}}}}
   :defines {:events events/core-events}})
