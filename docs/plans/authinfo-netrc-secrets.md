# Plan: Authinfo and Netrc Secrets

## Overview

Add support for reading API credentials from `.netrc` and `.authinfo` files to provide a secure, standard way for users to store and manage authentication credentials for LLM providers without storing them in configuration files or environment variables.

## Background

`.netrc` and `.authinfo` are standard file formats used by various tools to store login credentials for remote hosts:

- **`.netrc`**: Standard Unix format used by curl, git, and other tools
- **`.authinfo`**: Emacs-style format with similar functionality, popular in the Emacs ecosystem
- **`.authinfo.gpg`**: GPG-encrypted version of `.authinfo` for additional security

These formats provide:

- **Security**: File permissions restrict access (should be 0600); `.authinfo.gpg` provides encryption
- **Standardization**: Well-known formats used across many tools
- **Convenience**: Single location for managing credentials across multiple tools
- **Privacy**: Keeps secrets out of version-controlled config files

### File Formats

**Netrc format** (`~/.netrc`):
```
machine api.openai.com
login apikey
password sk-...

machine api.anthropic.com
login work
password sk-ant-...
```

**Authinfo format** (`~/.authinfo` or `~/.authinfo.gpg`):
```
machine api.openai.com login apikey password sk-... port 443
machine api.anthropic.com login work password sk-ant-... port 443
```

**Important Notes:**
- **Login field is required**: All credential entries must include a `login` field. Entries without a login will be ignored.
- Authinfo uses a single-line format with space-separated key-value pairs, while netrc uses a multi-line format.
- The `port` field is optional.

## Goals

1. Support reading API keys from `.netrc`, `.authinfo`, and `.authinfo.gpg` files as an additional authentication method
2. Maintain backward compatibility with existing authentication methods (config files, env vars, OAuth)
3. Follow standard `.netrc` and `.authinfo` conventions and security practices
4. Support GPG-encrypted credentials via `.authinfo.gpg`
5. Provide clear error messages and documentation

## Authentication Priority Order

When resolving credentials, ECA should check sources in this order:

1. **Config file** - explicit `key` in provider config (highest priority)
2. **Netrc/Authinfo files** - `keyNetrc` setting pointing to machine name in credential files
3. **Environment variable** - value from `keyEnv` setting
4. **OAuth flow** - for providers that support it (e.g., GitHub Copilot)

This ensures explicit configuration takes precedence while providing credential files as an additional option between direct config and environment variables.

### Credential File Discovery

When `keyNetrc` is specified, ECA checks credential files in this order:
1. `~/.authinfo.gpg` (encrypted, most secure)
2. `~/.authinfo` (plaintext)
3. `~/.netrc` (plaintext)
4. `~/_netrc` (Windows)

The first matching credential found is used.

## Implementation Details

### 0. Dependencies

Add `org.clojure/test.check` to project dependencies for property-based testing:

```clojure
;; deps.edn
{:aliases
 {:test {:extra-deps {org.clojure/test.check {:mvn/version "1.1.1"}}}}}
```

**Notes:**
- `clojure.test.check` is only needed for test code, not production
- `clojure.java.process` is part of Clojure 1.12+ core library (no additional dependency needed)

### 1. Credential File Parser Modules

Create three namespace modules under `eca.secrets`:

#### `src/eca/secrets/netrc.clj` - Netrc format parser

```clojure
(ns eca.secrets.netrc
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]))

(set! *warn-on-reflection* true)

;; Core functions:
;; - (parse content) -> [{:machine String :login String :password String :port String|nil}]
;;   Parses netrc multi-line format into credential maps
;; - (parse-entry lines) -> {:machine :login :password :port} | nil
;;   Parses a single netrc entry from multiple lines
;; - (format-entry credential-map) -> String
;;   Formats a single credential map into netrc format string
;; - (format-entries [credential-map]) -> String
;;   Formats multiple credential maps into a complete netrc file content
;; - (read-file filename) -> [{:machine :login :password :port}]
;;   Reads and parses all entries from a netrc file
;; - (write-file filename [credential-maps]) -> nil
;;   Formats and writes credential entries to a netrc file
```

**File I/O Functions (for testing round-trip):**
```clojure
(defn read-file
  "Reads and parses all credential entries from a netrc file"
  [filename]
  (-> filename
      slurp
      parse))

(defn write-file
  "Formats and writes credential entries to a netrc file"
  [filename entries]
  (let [content (format-entries entries)]
    (spit filename content)))
```

**Format Functions:**
```clojure
(defn format-entry
  "Formats a single credential map into netrc format"
  [{:keys [machine login password port]}]
  (str "machine " machine "\n"
       "login " login "\n"
       "password " password "\n"
       (when port (str "port " port "\n"))))

(defn format-entries
  "Formats multiple credential maps into complete netrc file content"
  [entries]
  (string/join "\n" (map format-entry entries)))
```

**Netrc Format Parsing:**
- Multi-line format with keywords: `machine`, `login`, `password`, `port`
- Each entry can span multiple lines
- Comments start with `#`
- Whitespace-insensitive
- Entries without `login` field are skipped
- Empty files return empty vector `[]`

**Example:**
```
machine api.openai.com
login apikey
password sk-...

# This is a comment
machine api.anthropic.com
login work
password sk-ant-...
port 443
```

#### `src/eca/secrets/authinfo.clj` - Authinfo format parser

```clojure
(ns eca.secrets.authinfo
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]))

(set! *warn-on-reflection* true)

;; Core functions:
;; - (parse content) -> [{:machine String :login String :password String :port String|nil}]
;;   Parses authinfo single-line format into credential maps
;; - (parse-line line) -> {:machine :login :password :port} | nil
;;   Parses a single authinfo line into a credential map
;; - (format-entry credential-map) -> String
;;   Formats a single credential map into authinfo format string
;; - (format-entries [credential-map]) -> String
;;   Formats multiple credential maps into a complete authinfo file content
;; - (read-file filename) -> [{:machine :login :password :port}]
;;   Reads and parses all entries from an authinfo file
;; - (write-file filename [credential-maps]) -> nil
;;   Formats and writes credential entries to an authinfo file
```

**File I/O Functions (for testing round-trip):**
```clojure
(defn read-file
  "Reads and parses all credential entries from an authinfo file"
  [filename]
  (-> filename
      slurp
      parse))

(defn write-file
  "Formats and writes credential entries to an authinfo file"
  [filename entries]
  (let [content (format-entries entries)]
    (spit filename content)))
```

**Format Functions:**
```clojure
(defn format-entry
  "Formats a single credential map into authinfo format"
  [{:keys [machine login password port]}]
  (str "machine " machine " "
       "login " login " "
       "password " password
       (when port (str " port " port))))

(defn format-entries
  "Formats multiple credential maps into complete authinfo file content"
  [entries]
  (string/join "\n" (map format-entry entries)))
```

**Authinfo Format Parsing:**
- Single-line format with space-separated key-value pairs
- Format: `machine <host> login <user> password <pass> [port <num>]`
- Comments start with `#`
- Order of fields can vary
- Entries without `login` field are skipped
- Empty files return empty vector `[]`

**Example:**
```
machine api.openai.com login apikey password sk-...
machine api.anthropic.com login work password sk-ant-... port 443
# This is a comment
machine custom.api port 8443 login admin password custom-key...
```

#### `src/eca/secrets.clj` - Main secrets manager module

```clojure
(ns eca.secrets
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.java.process :as process]
   [eca.secrets.netrc :as secrets.netrc]
   [eca.secrets.authinfo :as secrets.authinfo])
  (:import
   [java.io File]))

(set! *warn-on-reflection* true)

;; Core functions:
;; - (credential-file-paths) -> [String] - returns ordered list of credential file paths to check
;; - (decrypt-gpg file) -> String|nil - decrypts .gpg file using gpg command via clojure.java.process
;; - (parse-key-netrc key-netrc) -> {:login String :machine String :port String} - parses keyNetrc value
;; - (get-credential key-netrc) -> String|nil - retrieves password for keyNetrc specifier from credential files
;; - (validate-permissions file) -> boolean - checks file has secure permissions
;; - (load-credentials-from-file file) -> [credential-map] - loads and parses credentials from a file
```

**GPG Decryption using `clojure.java.process`:**

```clojure
(defn decrypt-gpg
  "Decrypts a .gpg file using gpg command. Returns decrypted content or nil on failure."
  [file-path]
  (try
    (let [result (process/exec {:out :string
                                :err :string}
                               "gpg" "--quiet" "--batch" "--decrypt" file-path)]
      (if (zero? (:exit result))
        (:out result)
        (do
          (logger/warn logger-tag "GPG decryption failed:" (:err result))
          nil)))
    (catch Exception e
      (logger/warn logger-tag "GPG command failed:" (.getMessage e))
      nil)))

;; Alternative: Check if gpg is available
(defn gpg-available?
  "Checks if gpg command is available on system"
  []
  (try
    (let [result (process/exec {:err :discard}
                               "gpg" "--version")]
      (zero? (:exit result)))
    (catch Exception e
      false)))
```

**Responsibilities:**
- Coordinate file discovery and reading
- GPG decryption using `clojure.java.process`
- Permission validation
- Delegate parsing to `eca.secrets.netrc` or `eca.secrets.authinfo` based on file type
- Implement credential matching logic
- Cache GPG decryption results (5-second TTL)

**Credential Data Structure:**

Parsed credentials should be stored as:
```clojure
[{:machine "api.openai.com"
  :login "apikey"
  :password "sk-..."
  :port nil}
 {:machine "api.anthropic.com"
  :login "work"
  :password "sk-ant-work-..."
  :port nil}
 {:machine "custom.api"
  :login "apikey"
  :password "custom-key..."
  :port "8443"}]
```

**Note:** The `:login` field is required for all credentials. Entries without a login field should be skipped during parsing.

**Parsing keyNetrc Format:**

The `parse-key-netrc` function parses the format `[login@]machine[:port]`:
```clojure
(parse-key-netrc "api.openai.com")
;; => {:machine "api.openai.com" :login nil :port nil}

(parse-key-netrc "work@api.anthropic.com")
;; => {:machine "api.anthropic.com" :login "work" :port nil}

(parse-key-netrc "personal@api.anthropic.com:443")
;; => {:machine "api.anthropic.com" :login "personal" :port "443"}

(parse-key-netrc "api.custom.com:8443")
;; => {:machine "api.custom.com" :login nil :port "8443"}
```

**Credential Matching Logic:**

The `get-credential` function matches credentials with the following logic:

**Example credential entries:**
```clojure
[{:machine "api.anthropic.com" :login "work" :password "sk-ant-work-..." :port nil}
 {:machine "api.anthropic.com" :login "personal" :password "sk-ant-personal-..." :port nil}
 {:machine "api.openai.com" :login "apikey" :password "sk-proj-..." :port nil}
 {:machine "custom.api" :login "admin" :password "custom-key..." :port "8443"}]
```

**Matching behavior:**
```clojure
;; Login specified - exact match only
(get-credential "work@api.anthropic.com")
;; => "sk-ant-work-..." (matches login "work")

(get-credential "personal@api.anthropic.com")
;; => "sk-ant-personal-..." (matches login "personal")

;; No login specified - first matching entry
(get-credential "api.anthropic.com")
;; => "sk-ant-work-..." (first entry for this machine)

(get-credential "api.openai.com")
;; => "sk-proj-..." (first entry for this machine)

;; Port specified - must match port
(get-credential "custom.api:8443")
;; => "custom-key..." (matches machine + port)

(get-credential "custom.api")
;; => nil (no entry without port or with port nil)
```

**Matching Rules:**

1. **Login specified in keyNetrc**: Only match credentials with that exact login
2. **No login specified**: Return first credential matching machine (and port, if specified)
3. **Port specified in keyNetrc**: Only match credentials with that exact port (as string)
4. **No port specified**: Match credentials where port is nil or not specified

**Important:** All credential entries MUST have a login field. Entries without a login field are ignored during parsing.

**Features:**
- Check multiple credential file locations in order:
  - `~/.authinfo.gpg` (with GPG decryption)
  - `~/.authinfo`
  - `~/.netrc`
  - `~/_netrc` (Windows)
- Parse both netrc format (multi-line) and authinfo format (single-line)
- Support GPG decryption via `gpg` command-line tool
- Support `machine`, `login`, `password`, and `port` tokens
- **Require `login` field**: Skip/ignore credential entries without a login field
- **Handle empty files**: Return empty vector `[]` for empty files
- Validate file permissions (warn if not 0600 on Unix)
- Handle parsing errors gracefully
- Support comments (lines starting with `#`)
- Cache GPG decryption results (with short TTL) to avoid repeated decryption

### 2. Integration with Provider Authentication

Modify `src/eca/llm_util.clj`:

Update `provider-api-key` function to support `keyNetrc`:

```clojure
(ns eca.llm-util
  (:require
   ;; ... other requires
   [eca.secrets :as secrets]))

(defn provider-api-key [provider provider-auth config]
  (or (get-in config [:providers (name provider) :key])                     ; explicit config
      (:api-key provider-auth)                                               ; oauth token
      (when-let [key-netrc (get-in config [:providers (name provider) :keyNetrc])]
        (secrets/get-credential key-netrc))                                  ; credential files
      (some-> (get-in config [:providers (name provider) :keyEnv])
              config/get-env)))                                              ; env var
```

**Example Usage:**
```clojure
;; Simple machine lookup
(secrets/get-credential "api.openai.com")
;; => "sk-proj-..."

;; With specific login
(secrets/get-credential "work@api.anthropic.com")
;; => "sk-ant-work-..."

;; With port
(secrets/get-credential "api.custom.com:8443")
;; => "custom-key..."

;; Full format
(secrets/get-credential "personal@api.anthropic.com:443")
;; => "sk-ant-personal-..."
```

**Configuration Example:**
```javascript
{
  "providers": {
    "openai": {
      "url": "https://api.openai.com",
      "keyNetrc": "api.openai.com"  // machine name in credential file
    },
    "anthropic": {
      "url": "https://api.anthropic.com",
      "keyNetrc": "work@api.anthropic.com"  // with login prefix
    },
    "custom": {
      "url": "https://custom.api:8443",
      "keyNetrc": "personal@custom.api:8443"  // with login prefix and port
    }
  }
}
```

**Machine Name Lookup Format:**

The `keyNetrc` value supports multiple formats:

1. **Simple machine**: `"api.openai.com"` - matches any login for this machine
2. **With login**: `"personal@api.openai.com"` - matches specific login for this machine
3. **With port**: `"api.openai.com:443"` - matches specific port for this machine
4. **Full format**: `"work@api.anthropic.com:443"` - matches login, machine, and port

**Parsing Logic:**
- Format: `[login@]machine[:port]`
- `login`: Optional, if specified only match credentials with this login field
- `machine`: Required hostname
- `port`: Optional, if specified only match credentials with this port

**Matching Priority:**
When looking up credentials, match in order of specificity:
1. Exact match: login + machine + port
2. login + machine (any port)
3. machine + port (any login)
4. machine only (any login, any port)

**Credential File Examples:**

*Netrc format:*
```
machine api.openai.com
login apikey
password sk-...

machine api.anthropic.com
login work
password sk-ant-work-...

machine api.anthropic.com
login personal
password sk-ant-personal-...

machine custom.api
port 8443
login apikey
password custom-key...
```

*Authinfo format:*
```
machine api.openai.com login apikey password sk-...
machine api.anthropic.com login work password sk-ant-work-...
machine api.anthropic.com login personal password sk-ant-personal-...
machine custom.api port 8443 login apikey password custom-key...
```

**Important:** All credential entries MUST include a `login` field. Entries without a login field will be ignored during parsing.

**Benefits:**
- **Multiple accounts per provider**: Use login prefix to select specific credentials (work vs personal)
- **Simple default**: Without login prefix, uses first matching entry (predictable behavior)
- **Port-specific credentials**: Support custom deployments with non-standard ports
- **Order matters**: First matching entry wins when login not specified, so organize credential files accordingly
- For standard providers, consider adding default `keyNetrc` values to `initial-config` in `config.clj`

**GPG Integration:**
- Use `clojure.java.process/exec` to call `gpg --quiet --batch --decrypt <file>`
- Capture stdout (decrypted content) and stderr (errors) separately
- Cache decrypted content (5-second TTL) to avoid repeated GPG invocations
- Handle cases where GPG is not available (log warning, skip `.authinfo.gpg`)
- Check GPG availability before attempting decryption
- Support both GPG 1.x and 2.x
- Handle GPG passphrase prompts (use gpg-agent if available)
- Non-zero exit code indicates decryption failure

### 3. Configuration Schema Updates

Add the new `keyNetrc` setting to provider configuration:

```javascript
{
  "providers": {
    "openai": {
      "url": "https://api.openai.com",
      "key": null,                           // explicit API key
      "keyEnv": "OPENAI_API_KEY",            // environment variable name
      "keyNetrc": "api.openai.com"           // NEW: credential file lookup
    },
    "anthropic": {
      "url": "https://api.anthropic.com",
      "keyNetrc": "work@api.anthropic.com"   // with login for multi-account
    },
    "custom": {
      "url": "https://custom.api:8443",
      "keyNetrc": "personal@custom.api:8443" // with login and port
    }
  }
}
```

**Schema Definition:**
```typescript
interface ProviderConfig {
  url?: string;
  urlEnv?: string;
  key?: string;              // Direct API key
  keyEnv?: string;           // Environment variable name
  keyNetrc?: string;         // NEW: Credential file lookup in format [login@]machine[:port]
  // ... other settings
}
```

**keyNetrc Format:**
- Format: `[login@]machine[:port]`
- Examples:
  - `"api.openai.com"` - simple machine
  - `"work@api.anthropic.com"` - with login
  - `"api.custom.com:8443"` - with port
  - `"personal@api.custom.com:8443"` - full format

**Backward Compatibility:**
- `keyNetrc` is completely optional
- Existing configs without `keyNetrc` continue to work unchanged
- Priority order ensures `key` and `keyEnv` behavior is unchanged

### 4. Error Handling & Logging

- **Debug logging**: Log when checking credential files and which file is being used
- **Warn on insecure permissions**: Alert if plaintext files are world-readable
- **Graceful degradation**: If credential files don't exist or are invalid, silently fall through to next auth method
- **Parse errors**: Log warnings for malformed entries but continue
- **GPG errors**: Log warnings if GPG decryption fails (missing GPG, wrong passphrase, corrupted file)
- **Cache logging**: Log cache hits/misses for GPG decryption at debug level

### 5. Testing Strategy

#### Property-Based Testing with clojure.test.check

Use generative testing to verify parser correctness through round-trip properties:

**Core Generators - Generate Plain Data Structures:**
```clojure
(def gen-hostname
  "Generates valid hostnames"
  (gen/fmap
   (fn [parts] (string/join "." parts))
   (gen/vector (gen/such-that #(not (string/blank? %))
                              (gen/resize 10 gen/string-alphanumeric))
               1 4)))

(def gen-login-name
  "Generates login field values"
  (gen/elements ["apikey" "api-key" "x-api-key" "work" "personal" "admin"]))

(def gen-password
  "Generates password-like strings"
  (gen/fmap
   (fn [prefix] (str prefix "-" (apply str (repeatedly 32 #(rand-nth "abcdefghijklmnopqrstuvwxyz0123456789")))))
   (gen/elements ["sk-proj" "sk-ant" "key"])))

(def gen-port
  "Generates port numbers as strings"
  (gen/fmap str (gen/choose 1 65535)))

(def gen-credential-entry
  "Generates a single credential entry as a plain map"
  (gen/hash-map
   :machine gen-hostname
   :login gen-login-name
   :password gen-password
   :port (gen/one-of [(gen/return nil) gen-port])))

(def gen-credential-entries
  "Generates multiple credential entries (1-10 entries)"
  (gen/vector gen-credential-entry 1 10))
```

**Round-Trip Property:**

The core property to test using file I/O:
```clojure
;; For netrc format
(prop/for-all [entries gen-credential-entries]
  (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")]
    (try
      (let [;; 1. Generate entries (plain data)
            original-entries entries

            ;; 2. Write entries to netrc file
            _ (netrc/write-file (.getPath temp-file) original-entries)

            ;; 3. Read the netrc file back
            parsed-entries (netrc/read-file (.getPath temp-file))]

        ;; 4. Verify round-trip: parsed entries should match original
        (= (set original-entries) (set parsed-entries)))
      (finally
        (.delete temp-file)))))

;; For authinfo format
(prop/for-all [entries gen-credential-entries]
  (let [temp-file (java.io.File/createTempFile "authinfo-test" ".authinfo")]
    (try
      (let [;; 1. Generate entries (plain data)
            original-entries entries

            ;; 2. Write entries to authinfo file
            _ (authinfo/write-file (.getPath temp-file) original-entries)

            ;; 3. Read the authinfo file back
            parsed-entries (authinfo/read-file (.getPath temp-file))]

        ;; 4. Verify round-trip: parsed entries should match original
        (= (set original-entries) (set parsed-entries)))
      (finally
        (.delete temp-file)))))
```

**Test Flow:**
1. **Generate** → Random credential entries as plain maps
2. **Write** → Write entries to temporary file using `write-file`
3. **Read** → Read entries back from file using `read-file`
4. **Verify** → Parsed entries should equal original generated entries

**File I/O Functions:**
- `netrc/write-file` - takes filename and vector of credential maps, writes netrc format
- `netrc/read-file` - takes filename, returns vector of credential maps
- `authinfo/write-file` - takes filename and vector of credential maps, writes authinfo format
- `authinfo/read-file` - takes filename, returns vector of credential maps

**Benefits:**
- Tests the complete file write → read cycle (realistic usage)
- Verifies formatters and parsers are consistent
- Tests with actual file I/O (not just strings)
- Tests with multiple entries per file
- Automatically discovers edge cases
- Order-independent comparison (using sets)
- Uses temporary files with proper cleanup

### 5. Unit Tests

**Unit Tests - Netrc Parser** (`test/eca/secrets/netrc_test.clj`):

**Property-Based Tests (using `clojure.test.check`):**

Test the complete round-trip property using file I/O:

```clojure
(ns eca.secrets.netrc-test
  (:require
   [clojure.test :refer :all]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.java.io :as io]
   [eca.secrets.netrc :as netrc]))

(defspec netrc-file-roundtrip-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")]
      (try
        ;; 1. Generate credential entries (plain data)
        (let [original-entries entries

              ;; 2. Write entries to netrc file
              _ (netrc/write-file (.getPath temp-file) original-entries)

              ;; 3. Read the netrc file back
              parsed-entries (netrc/read-file (.getPath temp-file))]

          ;; 4. Verify round-trip: should get same entries back
          (= (set original-entries) (set parsed-entries)))
        (finally
          (.delete temp-file))))))

(defspec netrc-entries-have-required-fields-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")]
      (try
        (netrc/write-file (.getPath temp-file) entries)
        (let [parsed-entries (netrc/read-file (.getPath temp-file))]
          ;; All parsed entries must have machine, login, password
          (every? (fn [entry]
                    (and (:machine entry)
                         (:login entry)
                         (:password entry)))
                  parsed-entries))
        (finally
          (.delete temp-file))))))

(defspec netrc-handles-multiple-entries-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")]
      (try
        (netrc/write-file (.getPath temp-file) entries)
        (let [parsed-entries (netrc/read-file (.getPath temp-file))]
          ;; Should parse same number of entries
          (= (count entries) (count parsed-entries)))
        (finally
          (.delete temp-file))))))
```

**Traditional Unit Tests:**
- Parse valid netrc files (multi-line format):
  - Single entry
  - Multiple entries
  - Entry with all fields (machine, login, password, port)
  - Entry without port (port should be nil)
  - Multiple entries for same machine (different logins)
- Handle whitespace:
  - Leading/trailing whitespace on lines
  - Multiple spaces between keywords and values
  - Empty lines between entries
- Handle comments:
  - Full-line comments with `#`
  - Comments should be ignored
  - Mixed comments and valid entries
- Handle malformed entries:
  - Entry without login field (should be skipped)
  - Entry without machine field (should be skipped)
  - Entry without password field (should be skipped)
  - Incomplete entries
- Handle edge cases:
  - Empty file (should return empty vector `[]`)
  - File with only whitespace (should return empty vector `[]`)
  - File with only comments (should return empty vector `[]`)
  - Very long values
  - Special characters in passwords
  - Quoted values (if supported)

**Example edge case tests:**
```clojure
(deftest empty-file-test
  (is (= [] (netrc/parse ""))))

(deftest whitespace-only-file-test
  (is (= [] (netrc/parse "   \n\n  \t  \n"))))

(deftest comments-only-file-test
  (is (= [] (netrc/parse "# This is a comment\n# Another comment\n"))))
```

**Unit Tests - Authinfo Parser** (`test/eca/secrets/authinfo_test.clj`):

**Property-Based Tests (using `clojure.test.check`):**

Test the complete round-trip property using file I/O:

```clojure
(ns eca.secrets.authinfo-test
  (:require
   [clojure.test :refer :all]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.string :as string]
   [clojure.java.io :as io]
   [eca.secrets.authinfo :as authinfo]))

(defspec authinfo-file-roundtrip-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "authinfo-test" ".authinfo")]
      (try
        ;; 1. Generate credential entries (plain data)
        (let [original-entries entries

              ;; 2. Write entries to authinfo file
              _ (authinfo/write-file (.getPath temp-file) original-entries)

              ;; 3. Read the authinfo file back
              parsed-entries (authinfo/read-file (.getPath temp-file))]

          ;; 4. Verify round-trip: should get same entries back
          (= (set original-entries) (set parsed-entries)))
        (finally
          (.delete temp-file))))))

(defspec authinfo-entries-have-required-fields-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "authinfo-test" ".authinfo")]
      (try
        (authinfo/write-file (.getPath temp-file) entries)
        (let [parsed-entries (authinfo/read-file (.getPath temp-file))]
          ;; All parsed entries must have machine, login, password
          (every? (fn [entry]
                    (and (:machine entry)
                         (:login entry)
                         (:password entry)))
                  parsed-entries))
        (finally
          (.delete temp-file))))))

(defspec authinfo-handles-multiple-entries-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "authinfo-test" ".authinfo")]
      (try
        (authinfo/write-file (.getPath temp-file) entries)
        (let [parsed-entries (authinfo/read-file (.getPath temp-file))]
          ;; Should parse same number of entries
          (= (count entries) (count parsed-entries)))
        (finally
          (.delete temp-file))))))

;; Test field order independence using helper to write with random order
(defn write-file-random-order
  "Write entries with randomized field order to test order-independence"
  [filename entries]
  (let [content (string/join "\n"
                  (for [{:keys [machine login password port]} entries]
                    (let [fields (cond-> [["machine" machine]
                                          ["login" login]
                                          ["password" password]]
                               port (conj ["port" port]))
                          shuffled (shuffle fields)]
                      (string/join " " (mapcat identity shuffled)))))]
    (spit filename content)))

(defspec authinfo-field-order-independence-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "authinfo-test" ".authinfo")]
      (try
        ;; Write with random field order
        (write-file-random-order (.getPath temp-file) entries)

        ;; Read should still work
        (let [parsed-entries (authinfo/read-file (.getPath temp-file))]
          ;; Should get same entries regardless of field order
          (= (set entries) (set parsed-entries)))
        (finally
          (.delete temp-file))))))
```

**Traditional Unit Tests:**
- Parse valid authinfo files (single-line format):
  - Single entry
  - Multiple entries
  - Entry with all fields (machine, login, password, port)
  - Entry without port (port should be nil)
  - Multiple entries for same machine (different logins)
  - Fields in different orders
- Handle whitespace:
  - Multiple spaces between key-value pairs
  - Leading/trailing whitespace on lines
- Handle comments:
  - Full-line comments with `#`
  - Comments should be ignored
  - Mixed comments and valid entries
- Handle malformed entries:
  - Entry without login field (should be skipped)
  - Entry without machine field (should be skipped)
  - Entry without password field (should be skipped)
  - Odd number of tokens (incomplete key-value pairs)
  - Unknown keywords (should be ignored)
- Handle edge cases:
  - Empty file (should return empty vector `[]`)
  - File with only whitespace (should return empty vector `[]`)
  - File with only comments (should return empty vector `[]`)
  - Very long values
  - Special characters in passwords
  - Quoted values with spaces (if supported)

**Example edge case tests:**
```clojure
(deftest empty-file-test
  (is (= [] (authinfo/parse ""))))

(deftest whitespace-only-file-test
  (is (= [] (authinfo/parse "   \n\n  \t  \n"))))

(deftest comments-only-file-test
  (is (= [] (authinfo/parse "# This is a comment\n# Another comment\n"))))
```

**Unit Tests - Secrets Module** (`test/eca/secrets_test.clj`):
- Parse `keyNetrc` format:
  - Simple machine: `"api.openai.com"`
  - With login: `"work@api.anthropic.com"`
  - With port: `"api.custom.com:8443"`
  - Full format: `"personal@api.anthropic.com:443"`
  - Edge cases: empty string, malformed formats
- Test credential matching logic:
  - Exact login match when login specified
  - First entry match when no login specified
  - Port matching (exact match when specified, nil/any when not)
  - Multiple credentials for same machine
  - No match scenarios (return nil)
- File handling:
  - Handle missing files gracefully
  - Handle empty files (should return empty vector `[]`)
  - Validate permission checking (Unix only)
  - Test Windows vs Unix path handling
  - File priority order (authinfo.gpg > authinfo > netrc > _netrc)
- GPG integration:
  - Mock `clojure.java.process/exec` for GPG decryption
  - Test successful decryption (exit code 0, content in stdout)
  - Test GPG not available (command not found exception)
  - Test GPG decryption failure (non-zero exit code)
  - Test GPG error messages (captured from stderr)
  - Test cache behavior for GPG decryption (same file, multiple calls)
  - Test `gpg-available?` function
- Format detection:
  - Auto-detect netrc vs authinfo based on filename
  - Fallback to content-based detection

**Example GPG mocking:**
```clojure
(deftest gpg-decryption-success-test
  (with-redefs [process/exec (fn [opts & cmd]
                               {:exit 0
                                :out "machine api.openai.com login apikey password sk-..."
                                :err ""})]
    (let [result (secrets/decrypt-gpg "~/.authinfo.gpg")]
      (is (string/includes? result "api.openai.com")))))

(deftest gpg-decryption-failure-test
  (with-redefs [process/exec (fn [opts & cmd]
                               {:exit 2
                                :out ""
                                :err "gpg: decryption failed: No secret key"})]
    (let [result (secrets/decrypt-gpg "~/.authinfo.gpg")]
      (is (nil? result)))))
```

**Integration Tests** (`test/eca/secrets_integration_test.clj`):
- End-to-end authentication with credential files
- Priority order verification (config > credential files > env)
- Provider-specific hostname/login combinations
- Multi-account scenarios (work vs personal)
- Port-specific credentials
- Real GPG decryption test (if GPG available and configured)
- Test with actual LLM provider authentication flow

### 6. Documentation Updates

**New Section in `docs/models.md`**:

```markdown
### Credential File Authentication

ECA supports reading API credentials from `.authinfo` and `.netrc` files via the `keyNetrc` configuration.

**Configuration:**

Add `keyNetrc` to your provider configuration:

```javascript
{
  "providers": {
    "openai": {
      "url": "https://api.openai.com",
      "keyNetrc": "api.openai.com"  // simple machine name
    },
    "anthropic": {
      "url": "https://api.anthropic.com",
      "keyNetrc": "work@api.anthropic.com"  // with login for multi-account
    }
  }
}
```

**keyNetrc Format:**
- `"machine"` - Simple machine name
- `"login@machine"` - Specific login for multi-account scenarios
- `"machine:port"` - Machine with specific port
- `"login@machine:port"` - Full format with login and port

**Supported Files (checked in order):**
1. `~/.authinfo.gpg` - GPG-encrypted authinfo (most secure)
2. `~/.authinfo` - Plaintext authinfo format
3. `~/.netrc` - Plaintext netrc format
4. `~/_netrc` - Windows netrc format

**File Formats:**

*Netrc format* (multi-line):
```
machine api.openai.com
login apikey
password sk-proj-...

machine api.anthropic.com
login work
password sk-ant-...
```

*Authinfo format* (single-line):
```
machine api.openai.com login apikey password sk-proj-... port 443
machine api.anthropic.com login work password sk-ant-... port 443
```

**Requirements:**
- The `login` field is **required** for all credential entries
- Entries without a `login` field will be ignored
- The `port` field is optional

**GPG Encryption:**
To use encrypted credentials with `.authinfo.gpg`:
```bash
# Create/edit authinfo file
echo "machine api.openai.com login apikey password sk-..." > ~/.authinfo

# Encrypt it with GPG
gpg --output ~/.authinfo.gpg --symmetric ~/.authinfo

# Remove plaintext (optional but recommended)
rm ~/.authinfo
```

ECA will automatically decrypt `.authinfo.gpg` using the `gpg` command. Make sure `gpg` is installed and `gpg-agent` is configured for passphrase caching.

**Security:**
- Plaintext files should have restricted permissions (0600 on Unix)
- ECA will warn if permissions are too open
- `.authinfo.gpg` provides encryption at rest
- Keep credential files out of version control

**Supported Providers:**
All providers with API key authentication can use credential files.

**Priority Order:**
1. Config file (`key` setting)
2. Credential files (`keyNetrc` setting)
3. Environment variable (`keyEnv`)
4. OAuth flow (if supported)
```

**Update `docs/configuration.md`**:

Add a new "Credential Files" subsection under "Providers / Models":

```markdown
### Credential Files (netrc/authinfo)

Providers can use `keyNetrc` to read credentials from `.netrc` or `.authinfo` files:

```javascript
{
  "providers": {
    "openai": {
      "keyNetrc": "api.openai.com"
    }
  }
}
```

The value of `keyNetrc` is the machine name to look up in credential files.
See [Models documentation](./models.md#credential-file-authentication) for file formats and setup.
```

- Add to "Providers / Models" section showing `keyNetrc` alongside `key` and `keyEnv`
- Include security best practices
- Show examples for common providers
- Document GPG setup and usage
- Explain authentication and file priority order

**Update `AGENTS.md`**:
- Document secrets management modules:
  - `src/eca/secrets/netrc.clj` - Netrc format parser
  - `src/eca/secrets/authinfo.clj` - Authinfo format parser
  - `src/eca/secrets.clj` - Main secrets manager for credential file operations
- Include in authentication flow description
- Note GPG dependency for `.authinfo.gpg` support

### 7. Security Considerations

- **Never log passwords**: Only log which files are being checked, never log credentials
- **Validate permissions**: Warn users if plaintext file permissions are insecure (not 0600)
- **GPG security**: `.authinfo.gpg` provides encryption at rest; passphrase managed by gpg-agent
- **Error messages**: Don't leak credential information in errors
- **File access**: Use standard Java file I/O, respect OS permissions
- **Memory**: Use short TTL cache (5 seconds) for GPG decryption results to balance performance and security
- **GPG command safety**: Use `--batch` and `--quiet` flags to avoid interactive prompts in background processes
- **Plaintext warnings**: Log info-level message recommending `.authinfo.gpg` over plaintext files

### 8. Cross-Platform Compatibility

- **Path handling**: Use `System/getProperty "user.home"` for home directory
- **File check order**: `~/.authinfo.gpg` → `~/.authinfo` → `~/.netrc` → `~/_netrc` (Windows)
- **Line endings**: Handle both `\n` and `\r\n` for all file formats
- **Permissions**: Unix-only check, skip on Windows with info log
- **GPG availability**: Check if `gpg` command exists before attempting decryption
- **GPG path**: Use system PATH to find `gpg`, support both `gpg` and `gpg2` commands
- **Format detection**: Auto-detect netrc vs authinfo format based on file content structure

## Migration Path

No migration needed - this is purely additive:

1. Existing users continue using config files or env vars
2. New users can choose netrc as a convenient option
3. Users can gradually migrate secrets to netrc at their own pace

## Non-Goals

- ❌ Creating/editing credential files programmatically
- ❌ Managing GPG keys or passphrases (rely on existing gpg-agent setup)
- ❌ Supporting macros or advanced authinfo/netrc features
- ❌ Replacing OAuth flows (credential files are supplementary)
- ❌ GUI for GPG passphrase input (use gpg-agent/pinentry)
- ❌ Custom encryption formats beyond GPG

## Timeline

### Phase 1: Core Implementation (Priority 1)
- [ ] Implement netrc parser (`src/eca/secrets/netrc.clj`)
  - [ ] Parse multi-line netrc format
  - [ ] Format function for round-trip testing
  - [ ] Handle comments and whitespace
  - [ ] Skip entries without login field
  - [ ] Property-based tests with `clojure.test.check`
  - [ ] Traditional unit tests (`test/eca/secrets/netrc_test.clj`)
- [ ] Implement authinfo parser (`src/eca/secrets/authinfo.clj`)
  - [ ] Parse single-line authinfo format
  - [ ] Format function for round-trip testing
  - [ ] Handle key-value pairs in any order
  - [ ] Skip entries without login field
  - [ ] Property-based tests with `clojure.test.check`
  - [ ] Traditional unit tests (`test/eca/secrets/authinfo_test.clj`)
- [ ] Implement secrets manager (`src/eca/secrets.clj`)
  - [ ] File discovery and priority logic
  - [ ] keyNetrc format parsing
  - [ ] Credential matching logic
  - [ ] Permission validation
  - [ ] Unit tests (`test/eca/secrets_test.clj`)
- [ ] Integrate with `llm_util/provider-api-key`
- [ ] Add logging and error handling

### Phase 2: GPG Support (Priority 2)
- [ ] Implement GPG decryption for `.authinfo.gpg` using `clojure.java.process`
- [ ] Add GPG command detection (`gpg-available?`)
- [ ] Implement error handling for GPG failures
- [ ] Implement caching for GPG decryption results (5-second TTL)
- [ ] Add unit tests with mocked GPG process execution
- [ ] Add integration tests with real GPG (if available)

### Phase 3: Testing & Refinement (Priority 3)
- [ ] Cross-platform testing (Windows, macOS, Linux)
- [ ] Validate security considerations
- [ ] Performance testing (especially GPG decryption)
- [ ] Test file priority order
- [ ] Test permission validation

### Phase 4: Documentation (Priority 4)
- [ ] Update `docs/models.md`
- [ ] Update `docs/configuration.md`
- [ ] Update `AGENTS.md`
- [ ] Add examples for common providers
- [ ] Document GPG setup instructions

### Phase 5: Polish (Priority 5)
- [ ] Optional: Add credential file config schema extensions
- [ ] Optional: Add `/doctor` check for credential files
- [ ] Optional: Suggest `.authinfo.gpg` when plaintext files detected
- [ ] User feedback and iteration

## Success Criteria

- ✅ Users can authenticate with any provider using `keyNetrc` configuration pointing to credential files
- ✅ Authentication priority order is respected (`key` → `keyNetrc` → `keyEnv` → OAuth)
- ✅ File priority order is respected (`.authinfo.gpg` → `.authinfo` → `.netrc`)
- ✅ GPG decryption works seamlessly with gpg-agent
- ✅ No breaking changes to existing authentication methods
- ✅ `keyNetrc` is completely optional and backward compatible
- ✅ Security warnings for insecure file permissions
- ✅ Cross-platform support (Windows, macOS, Linux)
- ✅ Graceful handling when GPG is not available
- ✅ Comprehensive test coverage
- ✅ Clear documentation with examples and GPG setup instructions

## Open Questions

1. **Caching**: Should we cache parsed credential file contents? If so, what TTL?
   - **Recommendation**: Cache GPG decryption results with 5-second TTL; parse plaintext files on each request (fast enough)

2. **Login field behavior**: When login is not specified in keyNetrc, should we filter by common login names or return first match?
   - **Recommendation**: Return first matching entry (simpler, more predictable)

3. **Multiple credentials**: How to handle multiple credentials for same machine?
   - **Recommendation**: Use first match, document that order matters

4. **GPG command**: Should we support both `gpg` and `gpg2`?
   - **Recommendation**: Try `gpg` first, fall back to `gpg2` if not found

5. **GPG errors**: How to handle passphrase prompts in non-interactive contexts?
   - **Recommendation**: Rely on gpg-agent; log error if agent not configured; document setup requirements

6. **`/doctor` integration**: Should we check credential file validity in doctor command?
   - **Recommendation**: Yes, add check for file existence, permissions, parse validity, and GPG availability

7. **Format detection**: Should we auto-detect file format or infer from filename?
   - **Recommendation**: Infer from filename (`.authinfo` uses authinfo format, `.netrc` uses netrc format), with fallback to content-based detection

8. **Port matching**: Should we match port as string or number?
   - **Recommendation**: Match as strings to avoid type conversion issues; port "443" matches "443" but not 443 or nil

## References

- [GNU .netrc documentation](https://www.gnu.org/software/inetutils/manual/html_node/The-_002enetrc-file.html)
- [curl netrc format](https://everything.curl.dev/usingcurl/netrc)
- [Emacs auth-source documentation](https://www.gnu.org/software/emacs/manual/html_node/auth/index.html)
- [authinfo file format](https://www.gnu.org/software/emacs/manual/html_node/auth/Help-for-users.html)
- [GPG documentation](https://gnupg.org/documentation/)
- [Git credential storage](https://git-scm.com/docs/git-credential-store)
