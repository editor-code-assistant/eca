# AWS Bedrock Provider for ECA

This document explains how to configure and use the AWS Bedrock provider in ECA.

## Configuration Examples

Here are comprehensive configuration examples for different AWS Bedrock scenarios:

### Example 1: Production Configuration with Proxy

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
          }
        },
        "claude-3-opus": {
          "modelName": "anthropic.claude-3-opus-20240229-v1:0",
          "extraPayload": {
            "temperature": 0.5,
            "max_tokens": 2048
          }
        },
        "claude-3-haiku": {
          "modelName": "anthropic.claude-3-haiku-20240307-v1:0",
          "extraPayload": {
            "stream": false,
            "temperature": 0.3
          }
        }
      }
    }
  }
}
```

**Generated URLs:**
- Converse: `https://api.company.com/api/cloud/api-management/ai-gateway/1.0/model/us-east-1.anthropic.claude-3-sonnet-20240229-v1:0/converse`
- ConverseStream: `https://api.company.com/api/cloud/api-management/ai-gateway/1.0/model/us-east-1.anthropic.claude-3-sonnet-20240229-v1:0/converse-stream`

### Example 2: Direct AWS Bedrock Configuration (No Proxy)

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
        },
        "cohere-command-r": {
          "modelName": "cohere.command-r-v1:0"
        }
      }
    }
  }
}
```

**Generated URLs:**
- Converse: `https://bedrock-runtime.us-west-2.amazonaws.com/model/us-west-2.anthropic.claude-3-sonnet-20240229-v1:0/converse`
- ConverseStream: `https://bedrock-runtime.us-west-2.amazonaws.com/model/us-west-2.anthropic.claude-3-sonnet-20240229-v1:0/converse-stream`

### Example 3: Multi-Region Configuration

```json
{
  "providers": {
    "bedrock-us-east": {
      "api": "bedrock",
      "key": "${env:BEDROCK_API_KEY}",
      "url": "https://proxy.us-east-1.example.com/bedrock/",
      "region": "us-east-1",
      "models": {
        "claude-3-sonnet": {
          "modelName": "anthropic.claude-3-sonnet-20240229-v1:0"
        }
      }
    },
    "bedrock-eu-west": {
      "api": "bedrock",
      "key": "${env:BEDROCK_API_KEY}",
      "url": "https://proxy.eu-west-1.example.com/bedrock/",
      "region": "eu-west-1",
      "models": {
        "claude-3-sonnet": {
          "modelName": "anthropic.claude-3-sonnet-20240229-v1:0"
        }
      }
    }
  }
}
```

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

### Basic Chat

```clojure
(provider/request bedrock-config messages {:temperature 0.7})
```

### With Tools

```clojure
(provider/request bedrock-config messages 
  {:tools [tool-spec]
   :temperature 0.7
   :top_k 200})
```

### Streaming Responses

```clojure
(provider/request bedrock-stream-config messages {:temperature 0.7})
```

## Supported Parameters

The AWS Bedrock provider supports the following parameters:

- `temperature`: Controls randomness (0.0 to 1.0)
- `top_k`: Number of top tokens to consider (default: 200)
- `max_tokens`: Maximum tokens to generate (default: 1024)
- `stopSequences`: Sequences that stop generation
- `tools`: Tool specifications for tool use
- `stream`: Controls streaming behavior (default: true)

## Streaming and Tool Calls

The AWS Bedrock provider fully supports both synchronous and streaming tool calls:

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