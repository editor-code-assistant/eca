---
description: "Get started with ECA: installation, initial setup, and configuration guide for connecting any LLM to your editor."
---

# Configuration

## Installation

Eca is written in Clojure and compiled into a native binary via graalvm.

!!! info "Auto install"

    ECA is already automatically downloaded and updated in all editor plugins, so you don't need to handle it manually, even so, if you want that, check the other methods.

=== "Editor (recommended)"

    ECA is already downloaded automatically by your ECA editor plugin, so you just need to install the plugin for your editor:
    
    - [Emacs](https://github.com/editor-code-assistant/eca-emacs)
    - [VsCode](https://github.com/editor-code-assistant/eca-vscode)
    - [Vim](https://github.com/editor-code-assistant/eca-nvim)
    - [Intellij](https://github.com/editor-code-assistant/eca-intellij)
  
=== "Script (recommended if manual installing)"

    Stable release:
    
    ```bash
    bash <(curl -s https://raw.githubusercontent.com/editor-code-assistant/eca/master/install)
    ```
    
    Or if facing issues with command above:
    ```bash
    curl -s https://raw.githubusercontent.com/editor-code-assistant/eca/master/install | sudo bash
    ```
    
    nightly build:
    
    ```bash
    bash <(curl -s https://raw.githubusercontent.com/editor-code-assistant/eca/master/install) --version nightly --dir ~/
    ```

=== "Homebrew"

    We have a custom tap using the native compiled binaries for users that use homebrew:
    
    ```bash
    brew install editor-code-assistant/brew/eca
    ```

=== "mise"

    Install using [mise](https://mise.jdx.dev) 
    
    ```bash
    # Install the plugin
    mise plugin install eca https://github.com/editor-code-assistant/eca-mise-plugin

    # Install latest version ECA
    mise install eca
    mise use -g eca

    # or install and use
    # desired version
    mise install eca@0.58.0
    mise use -g eca@0.58.0

    # Verify installation
    eca --version
    ```

=== "Gtihub releases"

    You can download the [native binaries from Github Releases](https://github.com/editor-code-assistant/eca/releases), although it's easy to have outdated ECA using this way.

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

    Convenient for editors but uncommon

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
- `env`: `${env:MY_ENV}` to get a system env value with support for default values: `${env:MY_ENV:foo}`
- `classpath`: `${classpath:path/to/eca/file}` to get a file content from [ECA's classpath](https://github.com/editor-code-assistant/eca/tree/master/resources)
- `netrc`: Support Unix RC [credential files](./models.md#credential-file-authentication)

!!! info Markdown support

    This is supported in markdown configurations of agents and skills as well, giving flexibility to template it.

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
          "summaryMaxLength": 30
        }
      },
      "mcpTimeoutSeconds" : 60,
      "lspTimeoutSeconds" : 30,
      "mcpServers" : {},
      "agent": {
        "code": {"prompts": {"chat": "${classpath:prompts/code_agent.md}"},
                 "disabledTools": ["preview_file_change"]},
        "plan": {"prompts": {"chat": "${classpath:prompts/plan_agent.md}"},
                  "disabledTools": ["edit_file", "write_file", "move_file"],
                  "toolCall": {"approval": {"deny": {"eca__shell_command":
                                                     {"argsMatchers": {"command": [".*[12&]?>>?\\s*(?!/dev/null($|\\s))(?!&\\d+($|\\s))\\S+.*",
                                                                                  ".*\\|\\s*(tee|dd|xargs).*",
                                                                                  ".*\\b(sed|awk|perl)\\s+.*-i.*",
                                                                                  ".*\\b(rm|mv|cp|touch|mkdir)\\b.*",
                                                                                  ".*git\\s+(add|commit|push).*",
                                                                                  ".*npm\\s+install.*",
                                                                                  ".*-c\\s+[\"'].*open.*[\"']w[\"'].*",
                                                                                  ".*bash.*-c.*[12&]?>>?\\s*(?!/dev/null($|\\s))(?!&\\d+($|\\s))\\S+.*"]}}}}}}
      },
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

!!! info "Network / Enterprise"

    For more details about configuring eca with mTLS or custom CA certificates, check [network page](./network.md)
