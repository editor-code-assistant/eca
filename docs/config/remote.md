---
description: "Configure ECA Remote: control your ECA session from a web browser."
---

# Remote

ECA Remote lets you control, approve, and observe your ECA session from a web browser.
When enabled, ECA starts an embedded HTTPS server that the web frontend at [web.eca.dev](https://web.eca.dev) connects to in real-time.

![](../images/features/remote.png)

## Connection Methods

=== "LAN / Private IPs"

    The simplest approach — connect directly from `https://web.eca.dev` to your machine on the local network. Works in all browsers with no extra setup.

    **Config:**

    ```javascript title="~/.config/eca/config.json"
    {
      "remote": {
        "enabled": true,
        "password": "something" // optional — or ${env:MY_PASS}
      }
    }
    ```

    **Steps:**

    1. Start ECA — it logs a connection URL you can open directly:

        ```
        https://web.eca.dev?host=192-168-1-42.local.eca.dev:7777&pass=a3f8b&protocol=https
        ```

    2. Or open `https://web.eca.dev`, enter your machine's LAN IP (e.g. `192.168.1.42`) and password — the web UI automatically resolves it to the secure `*.local.eca.dev` hostname.

    ECA serves HTTPS for `*.local.eca.dev` using a certificate it fetches from `tls.eca.dev` on first use and caches locally (you can self-supply or override the source via `remote.tls`). On startup, the server detects your LAN IP and serves HTTPS using a hostname like `192-168-1-42.local.eca.dev`, which resolves back to your IP via [sslip.io](https://sslip.io) DNS. This gives you a valid HTTPS connection to a private IP — no mixed-content blocking, no browser prompts.

    !!! note "Firewall"
        Make sure your firewall allows incoming TCP on ports `7777`–`7796` from your LAN.
        ECA uses port `7777` by default and increments if busy.

=== "VPN / Tailscale"

    For connecting from outside your LAN. [Tailscale](https://tailscale.com) (free) creates a secure private network between your devices with valid HTTPS certificates.

    **Config:**

    ```javascript title="~/.config/eca/config.json"
    {
      "remote": {
        "enabled": true,
        "password": "something-here",
        "host": "my-machine.tail1234.ts.net" // optional — used in the connect URL
      }
    }
    ```

    **Steps:**

    1. Install Tailscale and enable [HTTPS certificates](https://tailscale.com/kb/1153/enabling-https)
    2. Expose ECA's port via Tailscale HTTPS serve:

        ```bash
        sudo tailscale serve --bg --https 7777 https+insecure://localhost:7777
        sudo tailscale serve --bg --https 7778 https+insecure://localhost:7778
        # ... repeat for as many ports as you need (7777–7796)
        ```

        !!! warning "Use `--https`, not `--tcp`"
            `--tcp` creates a raw TCP proxy that browsers cannot connect to.
            `--https` creates a proper HTTPS reverse proxy with valid certificates.

        !!! note "Why `https+insecure://`?"
            ECA's built-in server runs HTTPS with a `*.local.eca.dev` certificate.
            Tailscale Serve must connect to it over TLS (`https+insecure://`), not plain
            HTTP. The `+insecure` flag tells Tailscale to skip certificate verification
            since the local cert won't match your Tailscale hostname.

    3. Start ECA — it logs a connection URL you can open directly:

        ```
        https://web.eca.dev?host=my-machine.tail1234.ts.net:7777&pass=a3f8b&protocol=https
        ```

    4. Open `https://web.eca.dev` and enter your Tailscale hostname and password

    !!! tip "Binding to a specific interface"
        Set `bindHost` to pin the server to a specific interface — e.g. your
        Tailscale IP — instead of the default auto-detection. ECA then binds
        only that interface:

        ```javascript title="~/.config/eca/config.json"
        {
          "remote": {
            "enabled": true,
            "bindHost": "100.101.146.110"
          }
        }
        ```

=== "Local Docker"

    Run the web frontend locally over HTTP — useful for development or restricted environments.

    **Config:**

    ```javascript title="~/.config/eca/config.json"
    {
      "remote": {
        "enabled": true,
        "password": "something-here"
      }
    }
    ```

    **Steps:**

    1. Run the web frontend locally via Docker:

        ```bash
        docker run --pull=always -p 8080:80 ghcr.io/editor-code-assistant/eca-web
        ```

    2. Open `http://localhost:8080` and connect to `localhost:7777`

## Config Options

```javascript title="~/.config/eca/config.json"
{
  "remote": {
    "enabled": true,
    // optional — override the hostname in the connect URL (e.g. Tailscale DNS).
    // Display-only; does NOT change which interface the server binds to.
    "host": "my-machine.tail1234.ts.net",
    // optional — pin the interface the server binds to (e.g. a Tailscale IP).
    // When unset, ECA auto-detects. Use "0.0.0.0" to bind all interfaces.
    "bindHost": "100.101.146.110",
    // optional — defaults to 7777, auto-increments up to 7796 if busy
    "port": 9876,
    // optional — auto-generated when unset, supports ${env:MY_PASS}
    "password": "my-secret-token",
    // optional — HTTPS cert for *.local.eca.dev. Fetched from tls.eca.dev and
    // cached by default; supply your own or override the fetch source.
    "tls": {
      "certFile": "/path/to/fullchain.pem", // use your own cert (with keyFile)
      "keyFile": "/path/to/privkey.pem",
      "certUrl": "https://tls.eca.dev/local-eca-dev-fullchain.pem", // or override source
      "keyUrl": "https://tls.eca.dev/local-eca-dev-privkey.pem"
    }
  }
}
```

## Web UI

The web frontend provides a connect form where you enter the host and password (or use the deep-link URL logged by ECA).

**Auto-discovery** — When you enter a host and click "Discover & Connect", the web UI scans ports `7777`–`7796` in parallel and finds all running ECA instances automatically.

**Multi-session** — You can connect to multiple ECA instances simultaneously. Each connection appears as a tab in the top bar.
