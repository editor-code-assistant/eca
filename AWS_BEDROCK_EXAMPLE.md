# AWS Bedrock Provider for ECA

This document explains how to configure and use the AWS Bedrock provider in ECA.

## Configuration Examples

The AWS Bedrock provider supports both Converse (synchronous) and ConverseStream (streaming) APIs. By default, streaming is enabled (`stream: true`).

### Converse vs ConverseStream Configuration

| Configuration | API Endpoint | Streaming | Use Case |
|---------------|--------------|-----------|----------|
| `"stream": false` | `/converse` | ❌ Disabled | Synchronous responses, simpler integration |
| `"stream": true` (default) | `/converse-stream` | ✅ Enabled | Real-time responses, better user experience |

### Example 1: Production Configuration with Proxy (Streaming Default)

```json
{
  "providers": {
    "bedrock": {
      "api": "bedrock",
      "key": "${env:BEDROCK_API_KEY}",
      "url": "https://api.company.com/api/cloud/api-management/ai-gateway/1.0/model/",
      "region": "us-east-1",
      "models": {
        "claude-3-sonnet": {
          "modelName": "anthropic.claude-3-sonnet-20240229-v1:0",
          "extraPayload": {
            "temperature": 0.7,
            "top_k": 200
            // stream: true (default - uses /converse-stream)
          }
        },
        "claude-3-opus": {
          "modelName": "anthropic.claude-3-opus-20240229-v1:0",
          "extraPayload": {
            "temperature": 0.5,
            "max_tokens": 2048
            // stream: true (default - uses /converse-stream)
          }
        },
        "claude-3-haiku": {
          "modelName": "anthropic.claude-3-haiku-20240307-v1:0",
          "extraPayload": {
            "stream": false,  // Explicitly disable streaming
            "temperature": 0.3
            // Uses /converse endpoint
          }
        }
      }
    }
  }
}
```

**Generated URLs:**
- `claude-3-sonnet`: `https://api.company.com/.../us-east-1.anthropic.claude-3-sonnet-20240229-v1:0/converse-stream`
- `claude-3-haiku`: `https://api.company.com/.../us-east-1.anthropic.claude-3-haiku-20240307-v1:0/converse`

### Example 2: Direct AWS Bedrock (No Proxy, Streaming)

```json
{
  "providers": {
    "bedrock": {
      "api": "bedrock",
      "key": "${env:BEDROCK_API_KEY}",
      "region": "us-west-2",
      "models": {
        "claude-3-sonnet": {
          "modelName": "anthropic.claude-3-sonnet-20240229-v1:0"
          // Uses /converse-stream by default
        }
      }
    }
  }
}
```

**Generated URL:** `https://bedrock-runtime.us-west-2.amazonaws.com/model/us-west-2.anthropic.claude-3-sonnet-20240229-v1:0/converse-stream`

### Example 3: Explicit Converse Configuration

```json
{
  "providers": {
    "bedrock": {
      "api": "bedrock",
      "key": "${env:BEDROCK_API_KEY}",
      "region": "eu-west-1",
      "models": {
        "cohere-command-r": {
          "modelName": "cohere.command-r-v1:0",
          "extraPayload": {
            "stream": false  // Force /converse endpoint
          }
        }
      }
    }
  }
}
```

**Generated URL:** `https://bedrock-runtime.eu-west-1.amazonaws.com/model/eu-west-1.cohere.command-r-v1:0/converse`

### URL Configuration Options

The AWS Bedrock provider supports two URL configuration patterns:

#### Option 1: Custom Proxy URL (Recommended)
```json
{
  "url": "https://api.company.com/api/cloud/api-management/ai-gateway/1.0/model/"
}
```
This will construct URLs like:
- `https://api.company.com/api/cloud/api-management/ai-gateway/1.0/model/us-east-1.anthropic.claude-3-sonnet-20240229-v1:0/converse`
- `https://api.company.com/api/cloud/api-management/ai-gateway/1.0/model/us-east-1.anthropic.claude-3-sonnet-20240229-v1:0/converse-stream` (when streaming)

#### Option 2: Standard AWS Bedrock URL
```json
{
  "region": "us-east-1"
  // No url specified
}
```
This will use the standard AWS Bedrock endpoint:
- `https://bedrock-runtime.us-east-1.amazonaws.com/model/us-east-1.anthropic.claude-3-sonnet-20240229-v1:0/converse`

### Environment Variable Setup

Set your AWS Bedrock API key as an environment variable:

```bash
export BEDROCK_API_KEY="your-api-key-here"
```

## Usage

Once configured, you can use the AWS Bedrock provider like any other provider in ECA:

### Basic Chat (Streaming Default)

```clojure
;; Uses ConverseStream API (streaming enabled by default)
(provider/request bedrock-config messages {:temperature 0.7})
```

### Explicit Synchronous Chat

```clojure
;; Uses Converse API (streaming disabled)
(provider/request bedrock-config messages 
  {:temperature 0.7
   :stream false})
```

### With Tools (Streaming)

```clojure
;; Streaming tool calls with ConverseStream
(provider/request bedrock-config messages 
  {:tools [tool-spec]
   :temperature 0.7
   :top_k 200
   :stream true})  ; Explicit (default behavior)
```

### With Tools (Synchronous)

```clojure
;; Synchronous tool calls with Converse
(provider/request bedrock-config messages 
  {:tools [tool-spec]
   :temperature 0.7
   :stream false})  ; Force synchronous mode
```

## Supported Parameters

The AWS Bedrock provider supports the following parameters:

- `temperature`: Controls randomness (0.0 to 1.0)
- `top_k`: Number of top tokens to consider (default: 200)
- `max_tokens`: Maximum tokens to generate (default: 1024)
- `stopSequences`: Sequences that stop generation
- `tools`: Tool specifications for tool use
- `stream`: Controls API endpoint selection (default: true)
  - `true`: Uses `/converse-stream` endpoint (streaming)
  - `false`: Uses `/converse` endpoint (synchronous)

## Converse vs ConverseStream APIs

The AWS Bedrock provider implements both AWS Bedrock APIs with automatic endpoint selection:

### API Endpoint Selection

```mermaid
flowchart TD
    A[Request] --> B{stream parameter}
    B -->|true (default)| C[/converse-stream]
    B -->|false| D[/converse]
    C --> E[Streaming Response]
    D --> F[Synchronous Response]
```

### Converse API (Synchronous)
- **Endpoint**: `/converse`
- **Behavior**: Returns complete response when generation finishes
- **Use Case**: Simple integrations, batch processing
- **Configuration**: `"stream": false`

### ConverseStream API (Streaming)
- **Endpoint**: `/converse-stream`
- **Behavior**: Streams response deltas via binary event stream
- **Use Case**: Real-time applications, better user experience
- **Configuration**: `"stream": true` (default)

## Streaming and Tool Calls

Both APIs fully support tool calls:

### Synchronous Tool Calls
```clojure
(provider/request bedrock-config messages 
  {:tools [tool-spec]
   :temperature 0.7})
```

### Streaming Tool Calls
```clojure
(provider/request bedrock-config messages 
  {:tools [tool-spec]
   :temperature 0.7
   :stream true})  ; Streaming enabled by default
```

When the model requests tool execution, the provider will:
1. Parse tool use requests from the response
2. Call the `on-prepare-tool-call` callback with formatted tool calls
3. Return `{:tools-to-call [...]}` for the caller to execute tools
4. Handle both text responses and tool requests appropriately

### Streaming Tool Call Events
The streaming implementation handles AWS Bedrock's binary event stream format and properly accumulates tool call data across multiple delta events, ensuring complete tool specifications are available for execution.

## Authentication

The AWS Bedrock provider supports two authentication approaches:

### Option 1: External Proxy (Recommended for Production)

```json
{
  "providers": {
    "bedrock": {
      "api": "bedrock",
      "key": "${env:BEDROCK_API_KEY}",
      "url": "https://your-proxy.example.com/api/bedrock/",
      "region": "us-east-1"
    }
  }
}
```

This approach uses an external proxy that:
1. Accepts Bearer token in Authorization header
2. Handles AWS SigV4 signing for AWS Bedrock API calls
3. Forwards requests to AWS Bedrock Converse/ConverseStream APIs

**Proxy Requirements:**
- Must support AWS SigV4 authentication
- Should forward Authorization header as Bearer token
- Must handle both `/converse` and `/converse-stream` endpoints

### Option 2: Direct AWS Bedrock Access (For Testing/Development)

```json
{
  "providers": {
    "bedrock": {
      "api": "bedrock",
      "key": "${env:BEDROCK_API_KEY}",
      "region": "us-east-1"
      // No url specified - uses standard AWS endpoints
    }
  }
}
```

**Important Note:** Direct AWS Bedrock access requires:
- AWS credentials configured in your environment
- Proper IAM permissions for Bedrock runtime
- AWS SDK configured for SigV4 signing

This implementation currently expects a proxy for production use, but the URL construction supports both patterns.

## Model Aliasing

You can use model aliases for convenience:

```json
"models": {
  "claude-3-sonnet": {
    "modelName": "anthropic.claude-3-sonnet-20240229-v1:0"
  }
}
```

Then use `bedrock/claude-3-sonnet` as the model identifier.

## Troubleshooting

### Common Issues

1. **Authentication Errors**: 
   - For proxy: Ensure `BEDROCK_API_KEY` is set and proxy is running
   - For direct AWS: Ensure AWS credentials are configured (`~/.aws/credentials`)
   
2. **URL Construction Issues**:
   - Verify URL ends with `/` for custom proxy configurations
   - Check region is correctly specified
   - Ensure modelName includes full AWS Bedrock model ID

3. **Model Not Found**:
   - Verify the model ID is correct and available in your AWS region
   - Check AWS Bedrock console for available models
   - Ensure proper IAM permissions for the model

4. **Streaming Issues**:
   - Ensure your proxy supports the `/converse-stream` endpoint
   - Check network connectivity and timeout settings
   - Verify binary event stream parsing is working

5. **Tool Call Errors**:
   - Ensure tool specifications match AWS Bedrock requirements
   - Verify tool input schemas are valid JSON Schema
   - Check tool results are properly formatted

### Debugging

Enable debug logging to see detailed request/response information:

```bash
ECA_LOG_LEVEL=debug eca
```

**Debug Output Includes:**
- API endpoint URLs
- Request payloads
- Response status codes
- Token usage information
- Streaming event parsing details

### URL Construction Verification

To verify URL construction, you can test the `build-endpoint` function:

```clojure
(require '[eca.llm-providers.aws-bedrock :as bedrock])

;; Test custom proxy URL
(bedrock/build-endpoint 
  {:url "https://proxy.example.com/model/" :region "us-east-1"} 
  "anthropic.claude-3-sonnet-20240229-v1:0" 
  false)
;; => "https://proxy.example.com/model/us-east-1.anthropic.claude-3-sonnet-20240229-v1:0/converse"

;; Test standard AWS URL
(bedrock/build-endpoint 
  {:region "eu-west-1"} 
  "cohere.command-r-v1:0" 
  true)
;; => "https://bedrock-runtime.eu-west-1.amazonaws.com/model/eu-west-1.cohere.command-r-v1:0/converse-stream"
```

## References

- [AWS Bedrock Documentation](https://docs.aws.amazon.com/bedrock/)
- [AWS Bedrock Converse API](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html)
- [AWS Bedrock ConverseStream API](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseStream.html)