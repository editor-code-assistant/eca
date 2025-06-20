# ECA Development

## Coding 

There are several ways of finding and fixing a bug or implementing a new feature:

- Create a test for your bug/feature, then implement the code following the test (TDD).
- Build `eca` binary using `bb debug-cli` (requires `babashka`) each time you have made changes, and test it manually in your client. This is the slowest option.
  - Using a debug binary you can check eca's stderr buffer and look for a nrepl port, and connect to the REPL, make changes to the running eca process (really handy).
- Run `bb run <path-to-json>` which will start the server and simulate a jsonrpc communication, check `test/flows` folder for built-in json files. (or `(cat <path-to-json>; cat) | clj -M:dev -m eca.main server`)


