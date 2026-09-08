---
description: "Configure ECA network settings: custom CA certificates, mTLS client certificates, and enterprise proxy support."
---

# Network

ECA supports proxy, custom CA certificates, and mutual TLS (mTLS) for environments behind corporate firewalls or requiring client certificate authentication.

All network settings apply globally to LLM API requests **and** MCP HTTP transports.

## Proxy

ECA reads proxy configuration from the standard environment variables:

```bash
HTTP_PROXY="http://user:pass@host:port"
HTTPS_PROXY="http://user:pass@host:port"
http_proxy="http://user:pass@host:port"
https_proxy="http://user:pass@host:port"
```

Lowercase wins if both are set. Credentials (if used) must match for HTTP and HTTPS.

### Bypassing the proxy

For ECA's Hato HTTP client (including LLM API requests), set `no_proxy` or `NO_PROXY`:

```bash
export no_proxy="localhost,127.0.0.1,.internal.example"
```

Lowercase takes precedence even when it is empty. Entries are comma-separated;
whitespace and empty entries are ignored. Matching is case-insensitive and uses
the URL hostname without resolving DNS:

- `internal.example` or `.internal.example` matches that host and its subdomains,
  such as `api.internal.example`, but not `notinternal.example`.
- IP literals match exactly; write IPv6 addresses without brackets, such as `::1`.
- A value of `*` bypasses the configured proxy for every host.
- Host entries apply to all ports and both HTTP and HTTPS. Port-qualified entries
  (`internal.example:8443`), CIDR ranges and partial wildcards (`*.example`) are
  not supported and do not match.

When the variable is absent or empty, or no entry matches, the configured proxy
continues to be used. This bypass list applies to ECA's environment-configured
Hato proxies; it does not change JVM proxy properties or other HTTP clients.

## Custom CA certificates

When behind a corporate firewall that uses its own root CA, you will see errors like `PKIX path building failed`. To fix this, point ECA to a PEM file containing the additional CA certificates:

=== "Config file"

    ```javascript title="~/.config/eca/config.json"
    {
      "network": {
        "caCertFile": "/etc/ssl/certs/corporate-ca.pem"
      }
    }
    ```

=== "Environment variable"

    ```bash
    export SSL_CERT_FILE="/etc/ssl/certs/corporate-ca.pem"
    ```

    `NODE_EXTRA_CA_CERTS` is also supported as a fallback, for compatibility with Node.js tooling.

Custom CA certificates are **additive** — the JVM default trust store (public CAs) remains trusted.

The config value supports [dynamic string interpolation](./introduction.md#dynamic-string-contents), so you can reference an env var inside the config file:

```javascript title="~/.config/eca/config.json"
{
  "network": {
    "caCertFile": "${env:SSL_CERT_FILE}"
  }
}
```

## Mutual TLS (mTLS)

For environments that require client certificate authentication, configure the client certificate and private key:

=== "Config file"

    ```javascript title="~/.config/eca/config.json"
    {
      "network": {
        "clientCert": "/etc/ssl/certs/client.pem",
        "clientKey": "/etc/ssl/private/client-key.pem",
        // only needed when the key is encrypted
        "clientKeyPassphrase": "${env:ECA_CLIENT_KEY_PASSPHRASE}"
      }
    }
    ```

=== "Environment variables"

    ```bash
    export ECA_CLIENT_CERT="/etc/ssl/certs/client.pem"
    export ECA_CLIENT_KEY="/etc/ssl/private/client-key.pem"
    export ECA_CLIENT_KEY_PASSPHRASE="optional-passphrase"
    ```

Both `clientCert` and `clientKey` must be provided together. The key must be in PKCS8 PEM format (either unencrypted or encrypted with a passphrase). RSA and EC key types are supported.

## Full example

A complete network configuration combining a custom CA with mTLS:

```javascript title="~/.config/eca/config.json"
{
  "network": {
    "caCertFile": "/etc/ssl/certs/corporate-ca.pem",
    "clientCert": "/etc/ssl/certs/client.pem",
    "clientKey": "/etc/ssl/private/client-key.pem",
    "clientKeyPassphrase": "${env:ECA_CLIENT_KEY_PASSPHRASE}"
  }
}
```

## Reference

| Config key | Env var fallback | Description |
|---|---|---|
| `caCertFile` | `SSL_CERT_FILE`, `NODE_EXTRA_CA_CERTS` | PEM file with custom CA certificates |
| `clientCert` | `ECA_CLIENT_CERT` | PEM file with client certificate for mTLS |
| `clientKey` | `ECA_CLIENT_KEY` | PEM file with client private key for mTLS |
| `clientKeyPassphrase` | `ECA_CLIENT_KEY_PASSPHRASE` | Passphrase for encrypted client key |

Config values take precedence over environment variables.
