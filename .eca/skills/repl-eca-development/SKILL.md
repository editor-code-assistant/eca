---
name: repl-eca-development
description: "MANDATORY - Load this skill to learn how to use repl in eca project to manual test ECA behavior in a running ECA session. Useful to know how providers behave or do a end to end test."
---

# REPl ECA Development

## Requisites

- Load any other repl skill if not loaded yet to know how to eval Clojure code.

## Context

There is a running JVM ECA process running with a nrepl server available which is tied to the repl you have access, this is powerful to eval anything from eca project and see lively changing in that eca session.
Use this when you need to understand how a provider behaves or part of the ECA code.
When testing stuff, call high level functions which simulates a user interacting with ECA, so for example always use `eca.features.chat/prompt` to simulate chat talks.

## Components 

To access eca components to pass to functions during manual eval, you can:

- db*: `eca.db/db*`
- db: `@eca.db/db*`
- config: `(eca.config/all db)`
- messenger `(-> db :dev :components :messenger)`
- metrics `(-> db :dev :components :metrics)`
- server `(-> db :dev :components :server)`
