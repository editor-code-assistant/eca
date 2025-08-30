# Troubleshooting

## Server logs

ECA works with clients (editors) sending and receiving messages to server, a process, you can start server with `--log-level debug` or `--verbose` which should log helpful information to `stderr` buffer like what is being sent to LLMs or what ECA is responding to editors, all supported editors have options to set the __server args___ to help with that.

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

## User tool issues

### My user tool isn’t working
- Check that the tool is defined in your config under `userTools`.
- Make sure the `bash` command is correct and executable in your environment.
- Ensure all required arguments are provided.
- If `requireApproval` is true, approve the tool call when prompted.
- Check server logs for errors.

## Ask for help

You can ask for help via chat [here](https://clojurians.slack.com/archives/C093426FPUG)
