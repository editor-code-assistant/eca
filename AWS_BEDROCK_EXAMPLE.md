# AWS Bedrock Provider for ECA

This document explains how to configure and use the AWS Bedrock provider in ECA.

## Configuration

To use AWS Bedrock with ECA, you need to configure the provider in your ECA configuration file (`.eca/config.json`).

### Basic Configuration

```json
{
  "providers": {
    "bedrock": {
      "api": "bedrock",
      "key": "${env:BEDROCK_API_KEY}",
      "url": "https://your-proxy.example.com/model/",
      "region": "us-east-1",
      "models": {
        "claude-3-sonnet": {
          "modelName": "anthropic.claude-3-sonnet-20240229-v1:0"
        },
        "claude-3-opus": {
          "modelName": "anthropic.claude-3-opus-20240229-v1:0"
        }
      }
    }
  }
}
```

### URL Configuration Options

The AWS Bedrock provider supports multiple URL configuration patterns:

#### Option 1: Base URL (Recommended)
```json
{
  "url": "https://api.company.com/api/cloud/api-management/ai-gateway/1.0/model/"
}
```
This will construct URLs like:
- `https://api.company.com/api/cloud/api-management/ai-gateway/1.0/model/us-east-1.anthropic.claude-3-sonnet-20240229-v1:0/converse`
- `https://api.company.com/api/cloud/api-management/ai-gateway/1.0/model/us-east-1.anthropic.claude-3-sonnet-20240229-v1:0/converse-stream` (when streaming)

#### Option 2: Legacy Placeholder URL (Backward Compatible)
```json
{
  "url": "https://your-proxy.example.com/model/{modelId}/converse"
}
```
This uses the original placeholder replacement pattern.

#### Option 3: No URL (Standard AWS Bedrock)
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

This implementation uses Bearer token authentication via an external proxy that handles AWS SigV4 signing. The proxy should:

1. Accept a Bearer token in the Authorization header
2. Handle AWS SigV4 signing for the actual AWS Bedrock API calls
3. Forward requests to the AWS Bedrock Converse API

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

1. **Authentication Errors**: Make sure your proxy is correctly configured and the API key is valid.
2. **Model Not Found**: Verify that the model ID is correct and available in your AWS region.
3. **Streaming Issues**: Ensure your proxy supports the ConverseStream API endpoint.

### Debugging

Enable debug logging to see detailed request/response information:

```bash
ECA_LOG_LEVEL=debug eca
```

## References

- [AWS Bedrock Documentation](https://docs.aws.amazon.com/bedrock/)
- [AWS Bedrock Converse API](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html)
- [AWS Bedrock ConverseStream API](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseStream.html)