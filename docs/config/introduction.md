# Configuration

## Ways to configure

There are multiples ways to configure ECA:

=== "Global config file"

    Convenient for users and multiple projects

    ```javascript title="~/.config/eca/config.json"
    {
      "defaultAgent": "plan"
    }
    ```

=== "Local Config file"

    Convenient for users

    ```javascript title=".eca/config.json"
    {
      "defaultAgent": "plan"
    }
    ```

=== "InitializationOptions"

    Convenient for editors

    Client editors can pass custom settings when sending the `initialize` request via the `initializationOptions` object:

    ```javascript
    "initializationOptions": {
      "defaultAgent": "plan"
    }
    ```

=== "Env var"

    Via env var during server process spawn:

    ```bash
    ECA_CONFIG='{"myConfig": "my_value"}' eca server
    ```

### Dynamic string contents

It's possible to retrieve content of any configs with a string value using the `${key:value}` approach, being `key`:

- `file`: `${file:/path/to/my-file}` or `${file:../rel-path/to/my-file}` to get a file content
- `env`: `${env:MY_ENV}` to get a system env value
- `classpath`: `${classpath:path/to/eca/file}` to get a file content from [ECA's classpath](https://github.com/editor-code-assistant/eca/tree/master/resources)
- `netrc`: Support Unix RC [credential files](./models.md#credential-file-authentication)

### Proxy Configuration

ECA supports proxies with basic cleartext authentication via the de-facto env vars:

```bash
HTTP_PROXY="http://user:pass@host:port"
HTTPS_PROXY="http://user:pass@host:port"
http_proxy="http://user:pass@host:port"
https_proxy="http://user:pass@host:port"
```

Lowercase var wins if both are set. Credentials (if used) must match for HTTP and HTTPS.

## Schema

ECA has a [config.json schema](../config.json) to validate and autocomplete in your editor.

## Default config

By default ECA consider the following as the base configuration: 

```javascript
    {
      "providers": {
          "openai": {"url": "https://api.openai.com"},
          "anthropic": {"url": "https://api.anthropic.com"},
          "github-copilot": {"url": "https://api.githubcopilot.com"},
          "google": {"url": "https://generativelanguage.googleapis.com/v1beta/openai"},
          "ollama": {"url": "http://localhost:11434"}
      },
      "defaultModel": null, // let ECA decides the default model.
      "netrcFile": null, // search ~/.netrc or ~/_netrc when null.
      "hooks": {},
      "rules" : [],
      "commands" : [],
      "enabledSkills": [".*"],
      "disabledTools": [],
      "toolCall": {
        "approval": {
          "byDefault": "ask",
          "allow": {"eca__directory_tree": {},
                    "eca__read_file": {},
                    "eca__grep": {},
                    "eca__preview_file_change": {},
                    "eca__editor_diagnostics": {}},
          "ask": {},
          "deny": {}
        },
        "readFile": {
          "maxLines": 2000
        },
        "shellCommand": {
          "summaryMaxLength": 30,
        },
      },
      "mcpTimeoutSeconds" : 60,
      "lspTimeoutSeconds" : 30,
      "mcpServers" : {},
      "agent" {
        "code": {"prompts": {"chat": "${classpath:prompts/code_agent.md}"},
                 "disabledTools": ["preview_file_change"]},
        "plan": {"prompts": {"chat": "${classpath:prompts/plan_agent.md}"},
                  "disabledTools": ["edit_file", "write_file", "move_file"],
                  "toolCall": {"approval": {"deny": {"eca__shell_command":
                                                     {"argsMatchers": {"command" [".*[12&]?>>?\\s*(?!/dev/null($|\\s))(?!&\\d+($|\\s))\\S+.*",
                                                                                  ".*\\|\\s*(tee|dd|xargs).*",
                                                                                  ".*\\b(sed|awk|perl)\\s+.*-i.*",
                                                                                  ".*\\b(rm|mv|cp|touch|mkdir)\\b.*",
                                                                                  ".*git\\s+(add|commit|push).*",
                                                                                  ".*npm\\s+install.*",
                                                                                  ".*-c\\s+[\"'].*open.*[\"']w[\"'].*",
                                                                                  ".*bash.*-c.*[12&]?>>?\\s*(?!/dev/null($|\\s))(?!&\\d+($|\\s))\\S+.*"]}}}}}}
      }
      "defaultAgent": "code",
      "welcomeMessage" : "Welcome to ECA!\n\nType '/' for commands\n\n",
      "autoCompactPercentage": 85,
      "index" : {
        "ignoreFiles" : [ {
          "type" : "gitignore"
        } ],
        "repoMap": {
          "maxTotalEntries": 800,
          "maxEntriesPerDir": 50
        }
      },
      "prompts": {
        "chat": "${classpath:prompts/code_agent.md}", // default to code agent
        "chatTitle": "${classpath:prompts/title.md}",
        "compact": "${classpath:prompts/compact.md}",
        "init": "${classpath:prompts/init.md}",
        "completion": "${classpath:prompts/inline_completion.md}",
        "rewrite": "${classpath:prompts/rewrite.md}"
      },
      "completion": {
        "model": "openai/gpt-4.1"
      }
    }
```
