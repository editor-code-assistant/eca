---
title: ECA Terms of Service
description: Terms governing the use of ECA (Editor Code Assistant) and its integrations.
---

# Terms of Service

_Last updated: see file revision date at the bottom of this page._

These Terms of Service (the "Terms") govern your use of **ECA — Editor Code Assistant** (the "Software"), including the ECA server, its editor plugins (Emacs, VS Code, Neovim, IntelliJ, Desktop, CLI), and any integrations the Software makes available, including the Slack integration.

By installing, running, or otherwise using the Software, you agree to these Terms. If you do not agree, do not install or use the Software.

## 1. Open source license

The Software is open source under the **Apache License 2.0**. The full license text is available at <https://github.com/editor-code-assistant/eca/blob/master/LICENSE>. Your right to use, modify, and redistribute the Software's source code is governed by that license.

These Terms supplement, and do not replace, the Apache 2.0 license.

## 2. No warranty

The Software is provided **"AS IS"**, without warranty of any kind, express or implied, including but not limited to the warranties of merchantability, fitness for a particular purpose, and non-infringement. In no event shall the authors or copyright holders be liable for any claim, damages, or other liability arising from or in connection with the Software or the use or other dealings in the Software.

## 3. Third-party services

The Software lets you connect to third-party services that you choose to configure, including:

- **LLM providers** (Anthropic, OpenAI, GitHub Copilot, Google, Ollama, and others).
- **MCP servers**, including the Slack MCP server at `https://mcp.slack.com/mcp`.
- Any other services you configure via `~/.config/eca/config.json`.

When you use the Software with a third-party service, your interaction with that service is governed by **that service's own terms of service and privacy policy**, in addition to these Terms. You are responsible for ensuring your use of the Software with each third-party service complies with its terms.

In particular, when using the Slack integration:

- You agree to comply with the [Slack API Terms of Service](https://slack.com/terms-of-service/api).
- You must have authorization to access the Slack data the Software queries on your behalf.
- The Software acts as an MCP client to Slack — it executes Slack actions you initiate from your editor.

## 4. Data handling

The Software is a local tool. There are no ECA-operated servers, no user accounts on our side, and no telemetry collected by the project unless you explicitly opt in.

Detailed data handling is described in the [Privacy Policy](https://github.com/editor-code-assistant/eca/blob/master/PRIVACY.md). By using the Software you acknowledge the data flows described there.

## 5. Acceptable use

You agree not to use the Software to:

- Violate the terms of service of any third-party service you connect (including Slack, your LLM provider, or any MCP server).
- Access data you are not authorized to access.
- Engage in any activity prohibited by applicable law.
- Attempt to circumvent security or privacy controls of any service the Software interacts with.

## 6. Modifications and termination

You may stop using the Software at any time by uninstalling it. The maintainers may update, change, or discontinue the Software at any time, with or without notice. Continued use of the Software after a change to these Terms constitutes acceptance of the change.

## 7. Support

The Software is community-maintained. Support is provided on a best-effort basis through:

- GitHub issues: <https://github.com/editor-code-assistant/eca/issues>
- Community chat: <https://clojurians.slack.com/archives/C093426FPUG>

Support is provided by the community of contributors and is not guaranteed.

## 8. Contact

For questions about these Terms, open an issue at <https://github.com/editor-code-assistant/eca/issues> or contact the maintainers via the project's GitHub repository.
