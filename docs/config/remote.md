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

There are two ways to connect a web frontend to your ECA session. Which one to use depends on your network setup and whether you need HTTPS.

### Tailscale / VPN (Recommended)

The easiest approach. [Tailscale](https://tailscale.com)(free) creates a secure private network between your devices, and ECA can bind to your Tailscale interface so that `https://web.eca.dev` can reach it.

1. Install Tailscale and enable [HTTPS certificates](https://tailscale.com/kb/1153/enabling-https)
2. Set `host` to your machine's Tailscale domain name:

    ```javascript title="~/.config/eca/config.json"
    {
      "remote": {
        "enabled": true,
        "host": "my-machine.tail1234.ts.net"
      }
    }
    ```

3. Start ECA — it will log a connection URL
4. Open `https://web.eca.dev` and paste the connection URL or manual inform host and pass

Because Tailscale provides valid HTTPS certificates for your machine, the browser can connect without any mixed-content issues.

### LAN

For users without Tailscale, you can run the web frontend locally in the LAN (usually the same machine that is running the server). This keeps everything over plain HTTP, avoiding browser security restrictions entirely.

1. Enable remote in your config:

    ```javascript title="~/.config/eca/config.json"
    {
      "remote": {
        "enabled": true
      }
    }
    ```

2. Run the web frontend locally via Docker:

    ```bash
    docker run --pull=always -p 8080:80 ghcr.io/editor-code-assistant/eca-web
    ```

3. Start ECA — it will log the connection URL and auth token
4. Open `http://localhost:8080` and paste the connection URL

!!! tip
    You can also set `host` to your machine's LAN IP (e.g. `192.168.1.42`) if you want to connect from another device on the same network.

## Browser Security

!!! warning "Why two methods?"
    Modern browsers enforce strict security policies that affect how web pages connect to local services:

    - **Mixed content blocking** — HTTPS pages (like `https://web.eca.dev`) cannot make requests to plain HTTP endpoints. Browsers silently block these connections.
    - **Private Network Access** — Chrome additionally blocks requests from public websites to private/local IP addresses (e.g. `127.0.0.1`, `192.168.*`), even when the local server is running.

    **Tailscale** solves both problems by providing valid HTTPS certificates for your machine within your private network.

    **LAN mode** sidesteps both problems by serving the frontend locally over HTTP — since both the page and ECA are on `localhost` over HTTP, no security policies are triggered.

## Web UI

The web frontend provides a connect form where you enter the host and password (or use the deep-link URL logged by ECA).

**Auto-discovery** — When you enter a host and click "Discover", the web UI scans ports `7777`–`7787` in parallel and finds all running ECA instances automatically. This is the default port range ECA uses when auto-assigning ports.

**Multi-session** — You can connect to multiple ECA instances simultaneously. Each connection appears as a tab in the top bar, letting you switch between sessions.
