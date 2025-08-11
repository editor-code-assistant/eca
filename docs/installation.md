# Installation

:warning: ECA is already automatically downloaded in all editor plugins, so you don't need to download it manually, even so, if you want that, follow below:

Eca is written in Clojure and compiled into a native binary via graalvm. You can download the [native binaries from Github Releases](https://github.com/editor-code-assistant/eca/releases) or use one of the methods below:

## Script (recommended)

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

## Homebrew (MacOS and Linux)

We have a custom tap using the native compiled binaries for users that use homebrew:

```bash
brew install editor-code-assistant/brew/eca
```

