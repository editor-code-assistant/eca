---
description: "Configure ECA Remote: control your ECA session from a web browser via Tailscale or LAN."
---

# Remote

ECA Remote lets you control and observe your ECA session from a web browser. When enabled, ECA starts an embedded HTTP server that a web frontend can connect to in real-time.

## Configuration

```javascript title="~/.config/eca/config.json"
{
  "remote": {
    "enabled": true,
    // optional — useful for specifying custom dns like tailscale or your local ip
    "host": "192.168.1.42",
    // optional — defaults to 7777 (auto-increments if busy)
    "port": 7777,
    // optional — a random pass is auto-generated when unset
    "password": "my-secret-token"
  }
}
```

Minimal setup — just enable it and ECA handles the rest:

```javascript title="~/.config/eca/config.json"
{
  "remote": {
    "enabled": true
  }
}
```

When the server starts, ECA logs the connection URL and auth token to stderr and welcome message. The URL is a deep-link that you can open directly in the browser:

```
https://web.eca.dev?host=192.168.1.42:7777&pass=a3f8b2c1...&protocol=https
```

## Connection Methods

There are three ways to connect the web frontend to your ECA session.

### Direct (LAN / Private IP)

The simplest approach — connect directly from `https://web.eca.dev` to your machine's private IP.

1. Enable remote in your config:

    ```javascript title="~/.config/eca/config.json"
    {
      "remote": {
        "enabled": true,
        "password": "something" // optioal - or ${env:MY_PASS}
      }
    }
    ```

2. Start ECA — it will log the connection URL or auth token with random pass.
3. Open `https://web.eca.dev` and enter your machine's LAN IP (e.g. `192.168.1.42`) and password
4. Chrome will show a **Local Network Access** permission prompt — click **Allow**

!!! note "Firewall"
    Make sure your firewall allows incoming TCP connections on ports `7777`–`7787` (the default ECA port range) from your LAN.
    ECA start using `7777` and so one for each server running in your machine.

### Tailscale / VPN

For a seamless HTTPS-to-HTTPS connection with no browser prompts. [Tailscale](https://tailscale.com) (free) creates a secure private network between your devices with valid HTTPS certificates.

1. Install Tailscale and enable [HTTPS certificates](https://tailscale.com/kb/1153/enabling-https)
2. Set `host` to your machine's Tailscale domain name:

    ```javascript title="~/.config/eca/config.json"
    {
      "remote": {
        "enabled": true,
        "password": "something-here",
        "host": "my-machine.tail1234.ts.net" // optional - just to get url easily when starting
      }
    }
    ```

3. Start ECA — it will log a connection URL
4. Open `https://web.eca.dev` and paste the connection URL or manually enter host and password

Because Tailscale provides valid HTTPS certificates for your machine, the browser connects without any mixed-content issues or permission prompts.

### Local Docker

If you prefer to keep everything over plain HTTP, you can run the web frontend locally. This avoids all browser security restrictions.

1. Enable remote in your config:

    ```javascript title="~/.config/eca/config.json"
    {
      "remote": {
        "enabled": true,
        "password": "something-here"
      }
    }
    ```

2. Run the web frontend locally via Docker, usually in the same machine where server is running:

    ```bash
    docker run --pull=always -p 8080:80 ghcr.io/editor-code-assistant/eca-web
    ```

3. Start ECA — it will log the connection URL and auth token
4. Open `http://localhost:8080` and paste the connection URL

## Web UI

The web frontend provides a connect form where you enter the host and password (or use the deep-link URL logged by ECA).

**Auto-discovery** — When you enter a host and click "Discover", the web UI scans ports `7777`–`7787` in parallel and finds all running ECA instances automatically. This is the default port range ECA uses when auto-assigning ports.

**Multi-session** — You can connect to multiple ECA instances simultaneously. Each connection appears as a tab in the top bar, letting you switch between sessions.
