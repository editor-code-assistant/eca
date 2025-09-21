# Troubleshooting

## Logs (stderr)

All supported editors have options to set the __server args__ to help with that and options to check the server logs.

To access the server logs:

=== "Emacs"

    `M-x` `eca-show-stderr`
    
=== "VsCode"

    Check the output channel `ECA stderr`.
   
=== "IntelliJ"

    Via action 'ECA: Show server logs'.
    
=== "Nvim"

    `EcaShowLogs` 

### Server logs

This controls what's logged by server on its actions, you can control to log more things via `--log-level debug` server arg.
This should help log LLM outputs, and other useful stuff.

### Client<->Server logs

ECA works with clients (editors) sending and receiving messages to server, a process, you can start server `--verbose` which should log all jsonrpc communication between client and server to `stderr` buffer like what is being sent to LLMs or what ECA is responding to editors. 

## Doctor command

`/doctor` command should log useful information to debug model used, server version, env vars and more.

## Missing env vars

When launching editors from a GUI application (Dock, Applications folder, or desktop environment), high chance that it won't inherit environment variables from your shell configuration files (`.zshrc`, `.bashrc`, etc.). Since the ECA server is started as a subprocess from editor, it inherits the editor environment, which may be missing your API keys and other configuration.

You can check if the env vars are available via `/doctor`.

One way to workaround that is to start the editor from your terminal.

### Alternatives

- Start the editor from your terminal.

- Set variables in your editor if supported, example in Emacs: `(setenv "MY_ENV" "my-value")`

- On macOS, you can set environment variables system-wide using `launchctl`:

   ```bash
   launchctl setenv ANTHROPIC_API_KEY "your-key-here"
   ```

## Ask for help

You can ask for help via chat [here](https://clojurians.slack.com/archives/C093426FPUG)
