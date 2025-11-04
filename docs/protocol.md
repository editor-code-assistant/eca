# ECA Protocol

The ECA (Editor Code Assistant) protocol is JSON-RPC 2.0-based protocol heavily insipired by the [LSP (Language Server Protocol)](https://microsoft.github.io/language-server-protocol/), that enables communication between multiple code editors/IDEs and ECA process (server), which will interact with multiple LLMs. It follows similar patterns to the LSP but is specifically designed for AI code assistance features.

Key characteristics:
- Provides a protocol standard so different editors can use the same language to offer AI features.
- Supports bidirectional communication (client to server and server to client)
- Handles both synchronous requests and asynchronous notifications
- Includes built-in support for streaming responses
- Provides structured error handling

## Base Protocol

The base protocol consists of a header and a content part (comparable to HTTP). The header and content part are
separated by a `\r\n`.

### Header Part

The header part consists of header fields. Each header field is comprised of a name and a value, separated by `: ` (a colon and a space). The structure of header fields conforms to the [HTTP semantic](https://tools.ietf.org/html/rfc7230#section-3.2). Each header field is terminated by `\r\n`. Considering the last header field and the overall header itself are each terminated with `\r\n`, and that at least one header is mandatory, this means that two `\r\n` sequences always immediately precede the content part of a message.

Currently the following header fields are supported:

| Header Field Name | Value Type  | Description |
|:------------------|:------------|:------------|
| Content-Length    | number      | The length of the content part in bytes. This header is required. |
| Content-Type      | string      | The mime type of the content part. Defaults to application/vscode-jsonrpc; charset=utf-8 |
{: .table .table-bordered .table-responsive}

The header part is encoded using the 'ascii' encoding. This includes the `\r\n` separating the header and content part.

### Content Part

Contains the actual content of the message. The content part of a message uses [JSON-RPC 2.0](https://www.jsonrpc.org/specification) to describe requests, responses and notifications. The content part is encoded using the charset provided in the Content-Type field. It defaults to `utf-8`, which is the only encoding supported right now. If a server or client receives a header with a different encoding than `utf-8` it should respond with an error.

### Example:

```
Content-Length: ...\r\n
\r\n
{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
        ...
    }
}
```

## Lifecycle Messages

The protocol defines a set of lifecycle messages that manage the connection and state between the client (editor) and server (code assistant).

=== "Initialization flow"

    Handshake between client and server, including the actions done by server after initialization.

    ```mermaid
    sequenceDiagram
        autonumber
        participant C as Client / Editor
        participant S as ECA Server
        C->>+S: initialize (request)
        Note right of S: Save workspace-folders/capabilties
        S->>-C: initialize (response)
        C--)+S: initialized (notification)
        Note right of S: Sync models: Request models.dev <br/>for models capabilities
        Note right of S: Notify which models/behaviors are <br/>avaialble and their defaults.
        S--)C: config/updated (notification)
        Note right of S: Init MCP servers
        S--)-C: tool/serverUpdated (notification)
    ```

=== "Shutdown flow"

    Shutdown process between client and server

    ```mermaid
    sequenceDiagram
        autonumber
        participant C as Client / Editor
        participant S as ECA Server
        C->>+S: shutdown
        Note right of S: Finish MCP servers process
        S->>-C: shutdown
        C--)S: exit
        Note right of S: Server stops its process
    ```

### Basic structures

```typescript
export type ErrorType = 'error' | 'warning' | 'info';

interface Error {
    type: ErrorType;
    message: string;
}

interface Range {
   start: {
       line: number;
       character: number;
   };
   
   end: {
       line: number;
       character: number;
   };
}
```


### Initialize (↩️)

The first request sent from client to server. This message:
- Establishes the connection
- Allows the server to index the project
- Enables capability negotiation
- Sets up the workspace context

_Request:_

* method: `initialize`
* params: `InitializeParams` defined as follows:

```typescript
interface InitializeParams {
    /**
     * The process Id of the parent process that started the server. Is null if
     * the process has not been started by another process. If the parent
     * process is not alive then the server should exit (see exit notification)
     * its process.
     */
     processId: integer | null;
     
     /**
     * Information about the client
     */
    clientInfo?: {
        /**
         * The name of the client as defined by the client.
         */
        name: string;

        /**
         * The client's version as defined by the client.
         */
        version?: string;
    };
    
    /**
     * User provided initialization options.
     */
    initializationOptions?: {
        /*
         * The chat behavior.
         */
         chatBehavior?: ChatBehavior;
    };
    
    /**
     * The capabilities provided by the client (editor or tool)
     */
    capabilities: ClientCapabilities;
    
    /**
     * The workspace folders configured in the client when the server starts.
     * If client doesn´t support multiple projects, it should send a single 
     * workspaceFolder with the project root.
     */
    workspaceFolders: WorkspaceFolder[];
}

interface WorkspaceFolder {
    /**
     * The associated URI for this workspace folder.
     */
    uri: string;

    /**
     * The name of the workspace folder. Used to refer to this folder in the user interface.
     */
    name: string;
}

interface ClientCapabilities {
    codeAssistant?: {
        /**
         * Whether client supports chat feature.
         */
        chat?: boolean;

        /**
         * Whether client supports rewrite feature.
         */
        rewrite?: boolean;
        
        /**
         * Whether client supports provide editor informations to server like
         * diagnostics, cursor information and others.
         */
        editor?: {
            /**
             * Whether client supports provide editor diagnostics 
             * information to server (Ex: LSP diagnostics) via `editor/getDiagnostics` 
             * server request.
             */ 
            diagnostics?: boolean;
        }
    }
}

type ChatBehavior = 'agent' | 'plan';
```

_Response:_

```typescript
interface InitializeResponse {}
```

### Initialized (➡️)

A notification sent from the client to the server after receiving the initialize response. This message:
- Confirms that the client is ready to receive requests
- Signals that the server can start sending notifications
- Indicates that the workspace is fully loaded

_Notification:_

* method: `initialized`
* params: `InitializedParams` defined as follows:

```typescript
interface InitializedParams {}
```

### Shutdown (↩️)

A request sent from the client to the server to gracefully shut down the connection. This message:
- Allows the server to clean up resources
- Ensures all pending operations are completed
- Prepares for a clean disconnection

_Request:_

* method: `shutdown`
* params: none

_Response:_

* result: null
* error: code and message set in case an exception happens during shutdown request.

### Exit (➡️)

A notification sent from the client to the server to terminate the connection. This message:
- Should be sent after a shutdown request
- Signals the server to exit its process
- Ensures all resources are released

_Notification:_

* method: `exit`
* params: none 

## Code Assistant Features

=== "Chat: text"

    Example of a basic chat conversation with only texts:

    ```mermaid
    sequenceDiagram
        autonumber
        participant C as Client / Editor
        participant S as ECA Server
        participant L as LLM
        C->>+S: chat/prompt
        Note over C,S: User sends: Hello there!
        S--)C: chat/contentReceived (system: start)
        S--)C: chat/contentReceived (user: "hello there!")
        Note right of S: Prepare prompt with all<br/>available contexts and tools.
        S->>+L: Send prompt
        S->>-C: chat/prompt
        Note over C,S: Success: sent to LLM
        loop LLM streaming
            Note right of L: Returns first `H`,<br/>then `i!`, etc
            L--)S: Stream data
            S--)C: chat/contentReceived (assistant: text)
            
        end
        L->>-S: Finish response
        S->>C: chat/contentReceived (system: finished)
    ```
    
=== "Chat: tool call"

    Example of a tool call loop LLM interaction:

    ```mermaid
    sequenceDiagram
        autonumber
        participant C as Client / Editor
        participant S as ECA Server
        participant L as LLM
        C->>S: chat/prompt
        Note over C,S: ...<br/>Same as text flow
        S->>+L: Send prompt with<br/>available tools
        loop LLM streaming / calling tools
            Note right of L: Returns first `will`,<br/>then `check`, etc
            L--)S: Stream data
            S--)C: chat/contentReceived (assistant: text)
            S--)C: chat/contentReceived (toolCallPrepare: name + args)
            L->>-S: Finish response:<br/>needs tool call<br/>'eca_directory_tree'
            S->>C: chat/contentReceived (toolCallRun)<br/>Ask user if should call tool
            C--)S: chat/toolCallApprove
            S->>C: chat/contentReceived (toolCallRunning)
            Note right of S: Call tool and get result
            S->>C: chat/contentReceived (toolCalled)
            S->>+L: Send previous prompt +<br/>LLM response +<br/>tool call result
            Note right of L: Stream response
        end
        L->>-S: Finish response
        S->>C: chat/contentReceived (system: finished)
    ```

### Chat Prompt (↩️)

A request sent from client to server, starting or continuing a chat in natural language as an agent.
Used for broader questions or continuous discussion of project/files.

_Request:_ 

* method: `chat/prompt`
* params: `ChatPromptParams` defined as follows:

```typescript
interface ChatPromptParams {
    /**
     * The chat session identifier. If not provided, a new chat session will be created.
     */
    chatId?: string;

    /**
     * The message from the user in native language
     */
    message: string;

    /**
     * Specifies the AI model to be used for chat responses.
     * Different models may have different capabilities, response styles,
     * and performance characteristics.
     */
    model?: Model;

    /**
     * The chat behavior used by server to handle chat communication and actions.
     */
    behavior?: ChatBehavior;

    /**
     * Optional contexts about the current workspace.
     * Can include multiple different types of context.
     */
    contexts?: ChatContext[];
}

/**
 * The LLM model name.
 */
type Model = string;

type ChatContext = FileContext | DirectoryContext | WebContext | RepoMapContext | CursorContext |McpResourceContext;

/**
 * Context related to a file in the workspace
 */
interface FileContext {
    type: 'file';
    /**
     * Path to the file
     */
    path: string;
    
    /**
     * Range of lines to retrive from file, if nil consider whole file.
     */
    linesRange?: LinesRange;
}

interface LinesRange {
   start: number;
   end: number;
}

/**
 * Context related to a directory in the workspace
 */
interface DirectoryContext {
    type: 'directory';
    /**
     * Path to the directory
     */
    path: string;
}

/**
 * Context related to web content
 */
interface WebContext {
    type: 'web';
    /**
     * URL of the web content
     */
    url: string;
}

/**
 * Context about the workspaces repo-map, automatically calculated by server.
 * Clients should include this to chat by default but users may want exclude 
 * this context to reduce context size if needed.
 *
 * @deprecated No longer needed, replaced by eca_directory_tree tool.
 */
interface RepoMapContext {
    type: 'repoMap'; 
}

/**
 * Context about the cursor position in editor, sent by client.
 * Clients should track path and cursor position.
 */
interface CursorContext {
    type: 'cursor'; 
    
    /**
     * File path of where the cursor is.
     */
    path: string;
    
    /**
     * Cursor position, if not using a selection start should be equal to end.
     */
    position: {
       start: {
           line: number;
           character: number;
       },
       end: {
           line: number;
           character: number;
       }
    }
}

/***
 * A MCP resource available from a MCP server.
 */
interface McpResourceContext {
    type: 'mcpResource';
    
   /** 
    * The URI of the resource like file://foo/bar.clj
    */
    uri: string;

    /** 
     * The name of the resource.
     */
    name: string;
    
    /** 
     * The description of the resource.
     */
    description: string;
    
    /** 
     * The mimeType of the resource like `text/markdown`.
     */
    mimeType: string;
    
    /** 
     * The server name of this MCP resource.
     */
    server: string;
}
```

_Response:_

```typescript
interface ChatPromptResponse {
    /**
     * Unique identifier for this chat session
     */
    chatId: string;
    
    /*
     * The model used for this chat request.
     */
    model: Model;
    
    /**
     * What the server is doing after receing this prompt
     */
    status: 'prompting' | 'login';
}
```

### Chat Content Received (⬅️)

A server notification with a new content from the LLM.

_Notification:_ 

* method: `chat/contentReceived`
* params: `ChatContentReceivedParams` defined as follows:

```typescript
interface ChatContentReceivedParams {
    /**
     * The chat session identifier this content belongs to
     */
    chatId: string;

    /**
     * The content received from the LLM
     */
    content: ChatContent;
    
    /**
     * The owner of this content.
     */
    role: 'user' | 'system' | 'assistant';
}

/**
 * Different types of content that can be received from the LLM
 */
type ChatContent = 
    ChatTextContent 
    | ChatURLContent 
    | ChatProgressContent 
    | ChatUsageContent
    | ChatReasonStartedContent 
    | ChatReasonTextContent 
    | ChatReasonFinishedContent 
    | ChatHookActionStartedContent 
    | ChatHookActionFinishedContent 
    | ChatToolCallPrepareContent
    | ChatToolCallRunContent
    | ChatToolCallRunningContent
    | ChatToolCalledContent
    | ChatToolCallRejectedContent
    | ChatMetadataContent;

/**
 * Simple text message from the LLM
 */
interface ChatTextContent {
    type: 'text';
    /**
     * The text content
     */
    text: string;
}

/**
 * Progress messages about the chat. 
 * Usually to mark what eca is doing/waiting or tell it finished processing messages.
 */
interface ChatProgressContent {
    type: 'progress';

    /**
     * The state of this progress.
     */
    state: 'running' | 'finished';

    /*
     * Extra text to show in chat about current state of this chat.
     */
    text: string;
}

/**
 * A reason started from the LLM
 *
 */
interface ChatReasonStartedContent {
    type: 'reasonStarted';
    
    /**
     * The id of this reason
     */
    id: string; 
}

/**
 * A reason text from the LLM
 *
 */
interface ChatReasonTextContent {
    type: 'reasonText';
    
    /**
     * The id of a started reason
     */
    id: string;
    
    /**
     * The text content of the reasoning
     */
    text: string;
}

/**
 * A reason finished from the LLM
 *
 */
interface ChatReasonFinishedContent {
    type: 'reasonFinished';
    
    /**
     * The id of this reason
     */
    id: string; 

    /**
     * The total time the reason took in milliseconds.
     */
    totalTimeMs: number;
}

/**
 * A hook action started to run
 *
 */
interface ChatHookActionStartedContent {
    type: 'hookActionStarted';
    
    /**
     * The id of this hook
     */
    id: string; 
    
    /**
     * The name of this hook
     */
    name: string;
    
    /**
     * The type of this hook action
     */
    actionType: 'shell';
}

/**
 * A hook action finished
 *
 */
interface ChatHookActionFinishedContent {
    type: 'hookActionFinished';
    
    /**
     * The id of this hook
     */
    id: string; 
    
    /**
     * The name of this hook
     */
    name: string;
    
    /**
     * The type of this hook action
     */
    actionType: 'shell';
    
    /**
     * The status code of this hook
     */
    status: number;
    
    /**
     * The output of this hook if any
     */
    output?: string;
    
    /**
     * The error of this hook if any
     */
    error?: string;
}

/**
 * URL content message from the LLM
 */
interface ChatURLContent {
    type: 'url';

    /**
     * The URL title
     */
    title: string;

    /**
     * The URL link
     */
    url: string;
}

/**
 * Details about the chat's usage, like used tokens and costs.
 */
interface ChatUsageContent {
    type: 'usage';
    
    /**
     * The total input + output tokens of the whole chat session so far.
     */
    sessionTokens: number;
    
    /**
     * The cost of the last sent message summing input + output tokens.
     */
    lastMessageCost?: string; 
    
    /**
     * The cost of the whole chat session so far.
     */
    sessionCost?: string;
    
    /**
     * Informations about limits.
     */
    limit?: {
        /**
         * The context limit for this chat.
         */
        context: number;
        /**
         * The output limit for this chat.
         */
        output: number;
    }
}

/**
 * Tool call that LLM is preparing to execute.
 * This will be sent multiple times for same tool id for each time LLM outputs 
 * a part of the arg, so clients should append the arguments to UI.
 */
interface ChatToolCallPrepareContent {
    type: 'toolCallPrepare';

    origin: ToolCallOrigin;
    
    /**
     * id of the tool call
     */
    id: string;
    
    /**
     * Name of the tool
     */
    name: string;
    
    /**
     * Server name of this tool
     */
    server: string;
    
    /*
     * Argument text of this tool call
     */
    argumentsText: string; 
    
    /**
     * Summary text to present about this tool call, 
     * ex: 'Reading file "foo"...'.
     */
    summary?: string;
    
    /**
     * Extra details about this call. 
     * Clients may use this to present different UX for this tool call.
     */
    details?: ToolCallDetails;
}

/**
 * Tool call that LLM will run, sent once per id.
 */
interface ChatToolCallRunContent {
    type: 'toolCallRun';
    
    origin: ToolCallOrigin;
    
    /**
     * id of the tool call
     */
    id: string;
    
    /**
     * Name of the tool
     */
    name: string;
    
    /**
     * Server name of this tool
     */
    server: string;
    
    /*
     * Arguments of this tool call
     */
    arguments: {[key: string]: string};
    
    /**
     * Whether this call requires manual approval from the user.
     */
    manualApproval: boolean;
     
    /**
     * Summary text to present about this tool call, 
     * ex: 'Reading file "foo"...'.
     */
    summary?: string;
    
    /**
     * Extra details about this call. 
     * Clients may use this to present different UX for this tool call.
     */
    details?: ToolCallDetails;
}

/**
 * Tool call that server is running to report to LLM later, sent once per id.
 */
interface ChatToolCallRunningContent {
    type: 'toolCallRunning';
    
    origin: ToolCallOrigin;
    
    /**
     * id of the tool call
     */
    id: string;
    
    /**
     * Name of the tool
     */
    name: string;
    
    /**
     * Server name of this tool
     */
    server: string;
    
    /*
     * Arguments of this tool call
     */
    arguments: {[key: string]: string};
    
    /**
     * Summary text to present about this tool call, 
     * ex: 'Reading file "foo"...'.
     */
    summary?: string;
    
    /**
     * Extra details about this call. 
     * Clients may use this to present different UX for this tool call.
     */
    details?: ToolCallDetails;
}

/**
 * Tool call result that LLM trigerred and was executed already, sent once per id.
 */
interface ChatToolCalledContent {
    type: 'toolCalled';
    
    origin: ToolCallOrigin;
    
    /**
     * id of the tool call
     */
    id: string;
    
    /**
     * Name of the tool
     */
    name: string;
    
    /**
     * Server name of this tool
     */
    server: string;
    
    /*
     * Arguments of this tool call
     */
    arguments: string[];
    
    /**
     * Whether it was a error
     */
    error: boolean;
    
    /**
     * the result of the tool call.
     */
    outputs: [{
        /*
         * The type of this output
         */
        type: 'text';
       
        /**
         * The content of this output
         */
        text: string; 
    }];
    
    /**
     * The total time the call took in milliseconds.
     */
    totalTimeMs: number;
    
    /**
     * Summary text to present about this tool call, 
     * ex: 'Reading file "foo"...'.
     */
    summary?: string;
    
    /**
     * Extra details about this call. 
     * Clients may use this to present different UX for this tool call.
     */
    details?: ToolCallDetails;
}

/**
 * Tool call rejected, sent once per id.
 */
interface ChatToolCallRejectedContent {
    type: 'toolCallRejected';
    
    origin: ToolCallOrigin;
    
    /**
     * id of the tool call
     */
    id: string;
    
    /**
     * Name of the tool
     */
    name: string;
    
    /**
     * Server name of this tool
     */
    server: string;
    
    /*
     * Arguments of this tool call
     */
    arguments: {[key: string]: string};
    
    /**
     * The reason why this tool call was rejected
     */
    reason: 'user-choice' | 'user-config';
    
    /**
     * Summary text to present about this tool call, 
     * ex: 'Reading file "foo"...'.
     */
    summary?: string;
    
    /**
     * Extra details about this call. 
     * Clients may use this to present different UX for this tool call.
     */
    details?: ToolCallDetails;
}

type ToolCallOrigin = 'mcp' | 'native';

type ToolCallDetails = FileChangeDetails | JsonOutputsDetails;

interface FileChangeDetails {
    type: 'fileChange';

     /**
      * The file path of this file change
      */
     path: string;
     
     /**
      * The content diff of this file change
      */
     diff: string;
     
     /**
      * The count of lines added in this change.
      */
     linesAdded: number;
     
     /**
      * The count of lines removed in this change.
      */
     linesRemoved: number;
}

interface JsonOutputsDetails {
    type: 'jsonOutputs';
    
    /**
     * The list of json outputs of this tool call properly formatted.
     */
    jsons: string[];
}

/**
 * Extra information about a chat
 */
interface ChatMetadataContent {
    type: 'metadata';

    /**
     * The chat title.
     */
    title: string;
}

```

### Chat approve tool call (➡️)

A client notification for server to approve a waiting tool call.
This will execute the tool call and continue the LLM chat loop.

_Notification:_

* method: `chat/toolCallApprove`
* params: `ChatToolCallApproveParams` defined as follows:

```typescript
interface ChatToolCallApproveParams {
    /**
     * The chat session identifier.
     */
    chatId: string;
    
    /**
     * The approach to save this tool call.
     */
    save?: 'session';

    /**
     * The tool call identifier to approve.
     */
    toolCallId: string; 
}
```

### Chat reject tool call (➡️)

A client notification for server to reject a waiting tool call.
This will not execute the tool call and return to the LLM chat loop.

_Notification:_

* method: `chat/toolCallReject`
* params: `ChatToolCallRejectParams` defined as follows:

```typescript
interface ChatToolCallRejectParams {
    /**
     * The chat session identifier.
     */
    chatId: string;
    
    /**
     * The tool call identifier to reject.
     */
    toolCallId: string; 
}
```

### Chat Query Context (↩️)

A request sent from client to server, querying for all the available contexts for user add to prompt calls.

_Request:_ 

* method: `chat/queryContext`
* params: `ChatQueryContextParams` defined as follows:

```typescript
interface ChatQueryContextParams {
    /**
     * The chat session identifier.
     */
    chatId?: string;

    /**
     * The query to filter results, blank string returns all available contexts.
     */
    query: string;
    
    /**
     * The already considered contexts.
     */
    contexts: ChatContext[];
}
```

_Response:_

```typescript
interface ChatQueryContextResponse {
    /**
     * The chat session identifier.
     */
    chatId?: string;

    /**
     * The returned available contexts.
     */
    contexts: ChatContext[];
}
```

### Chat Query Commands (↩️)

A request sent from client to server, querying for all the available commands for user to call.
Commands are multiple possible actions like MCP prompts, doctor, costs. Usually the 
UX follows `/<command>` to spawn a command.

_Request:_ 

* method: `chat/queryCommands`
* params: `ChatQueryCommandsParams` defined as follows:

```typescript
interface ChatQueryCommandsParams {
    /**
     * The chat session identifier.
     */
    chatId?: string;

    /**
     * The query to filter results, blank string returns all available commands.
     */
    query: string;
}
```

_Response:_

```typescript
interface ChatQueryCommandsResponse {
    /**
     * The chat session identifier.
     */
    chatId?: string;

    /**
     * The returned available Commands.
     */
    commands: ChatCommand[];
}

interface ChatCommand {
    /**
     * The name of the command.
     */
    name: string;

    /**
     * The description of the command.
     */
    description: string;
    
    /**
     * The type of this command
     */
    type: 'mcp-prompt' | 'native';
    
    /**
     * The arguments of the command.
     */
    arguments: [{
       name: string;
       description?: string;
       required: boolean; 
    }];
}
```

### Chat stop prompt (➡️)

A client notification for server to stop the current chat prompt with LLM if running.
This will stop LLM loops or ignore subsequent LLM responses so other prompts can be trigerred.

_Notification:_

* method: `chat/promptStop`
* params: `ChatPromptStopParams` defined as follows:

```typescript
interface ChatPromptStopParams {
    /**
     * The chat session identifier.
     */
    chatId: string;
}
```

### Chat delete (↩️)

A client request to delete a existing chat, removing all previous messages and used tokens/costs from memory, good for reduce context or start a new clean chat.
After response, clients should reset chat UI to a clean state.

_Request:_ 

* method: `chat/delete`
* params: `ChatDeleteParams` defined as follows:

```typescript
interface ChatDeleteParams {
    /**
     * The chat session identifier.
     */
    chatId?: string;
}
```

### Chat selected behavior changed (➡️)

A client notification for server telling the user selected a different behavior in chat.

_Notification:_

* method: `chat/selectedBehaviorChanged`
* params: `ChatSelectedBehaviorChanged` defined as follows:

```typescript
interface ChatSelectedBehaviorChanged {
    /**
     * The selected behavior.
     */
    behavior: ChatBehavior;
}
```

_Response:_

```typescript
interface ChatDeleteResponse {}
```

## Editor diagnostics (↪️)

A server request to retrieve LSP or any other kind of diagnostics if available from current workspaces.
Useful for server to provide to LLM information about errors/warnings about current code.

_Request:_ 

* method: `editor/getDiagnostics`
* params: `EditorGetDiagnosticsParams` defined as follows:

```typescript
interface EditorGetDiagnosticsParams {
    /**
     * Optional uri to get diagnostics, if nil return whole workspaces diagnostics.
     */
    uri?: string;
}
```

_Response:_

```typescript
interface EditorGetDiagnosticsResponse {
    /**
     * The list of diagnostics.
     */
    diagnostics: EditorDiagnostic[];
}

interface EditorDiagnostic {
    /**
     * The diagnostic file uri.
     */
    uri: string;
    
    /**
     * The diagnostic severity.
     */
    severity: 'error' | 'warning' | 'info' | 'hint';
    
    /**
     * The diagnostic source. Ex: 'clojure-lsp'
     */
    source: string;
    
    /**
     * The diagnostic range (1-based).
     */
    range: Range;
    
    /**
     * The diagnostic code. Ex: 'wrong-args'
     */
    code?: string;

    /**
     * The diagnostic message. Ex: 'Wrong number of args for function X'
     */
    message: string; 
}
```

### Completion (↩️)

A request sent from client to server, asking for a text to be presented to user as a inline completion for the current text code.

_Request:_ 

* method: `completion/inline`
* params: `CompletionInlineParams` defined as follows:

```typescript
interface CompletionInlineParams {
    /**
     * The current document text.
     */
    docText: string;

    /**
     * The document version.
     * Clients should increment this on their side each time document is changed.
     * Server will return this on its completion so clients can 
     * discard if document was changed/version was increased.
     */
    docVersion: number;

    /**
     * The cursor position.
     */
    position: {
       line: number;
       character: number;
    };
}
```

_Response:_ `CompletionInlineResponse | Error`

```typescript
interface CompletionInlineResponse {
    /**
     * The items available as completion.
     */
    items: CompletionInlineItem[];
}

interface CompletionInlineItem {
    /**
     * The item text
     */
    text: string;

    /**
     * The item doc-version
     */
    docVersion: number;
    
    /**
     * The range of this new text in the document
     */
    range: Range;
}
```

### Rewrite (↩️)

A request sent from client to server, asking to rewrite a piece of code in editor with what LLM respond.
The response is streamed via `rewrite/contentReceived` server notifications.

_Request:_ 

* method: `rewrite/prompt`
* params: `RewritePromptParams` defined as follows:

```typescript
interface RewritePromptParams {

    /**
     * The rewrite ID to be used in response and later notifications.
     */
    id: string;
    
    /**
     * The text to be rewritten.
     * This should be the content selected by user in their editor.
     */
    text: string;
    
    /**
     * The user prompt to LLM change the text.
     */
    prompt: string;

    /**
     * Optional path of the file.
     * Useful for give context to LLM about the file path.
     */
    path?: string;

    /**
     * The range of the selected text.
     */
    range: Range;
}
```

_Response:_ `RewritePromptResponse | Error`

```typescript
interface RewritePromptResponse {

    /**
     * The status of this rewrite.
     */
    status: 'prompting';
    
    /**
     * The model used by this rewrite request.
     */
    model: Model;
}
```

### Rewrite Content Received (⬅️)

A server notification with a new content from the rewrite LLM request.

_Notification:_ 

* method: `rewrite/contentReceived`
* params: `RewriteContentReceivedParams` defined as follows:

```typescript
interface RewriteContentReceivedParams {
    /**
     * The rewrite identifier this content belongs to
     */
    rewriteId: string;

    /**
     * The content received
     */
    content: RewriteContent;
}

interface RewriteStartedContent {
    type: 'started';
}

interface RewriteReasoningContent {
    type: 'reasoning';
}

interface RewriteTextContent {
    type: 'text';
    
    text: string;
}

interface RewriteFinishedContent {
    type: 'finished';
}

type RewriteContent = 
    RewriteStartedContent
    | RewriteReasoningContent
    | RewriteTextContent
    | RewriteFinishedContent;
             
```

## Configuration

### Config updated (⬅️)

A server notification with the new config server is considering (models, behaviors etc), usually related to config or auth changes.
Clients should update UI accordingly, if a field is missing/null, means it had no change since last config updated, so clients should ignore.

_Notification:_ 

* method: `config/updated`
* params: `configUpdatedParams` defined as follows:

```typescript
interface ConfigUpdatedParams {
    /**
     * Configs related to chat.
     */
    chat?: {

       /**
        * The models the user can use in chat.
        */
        models?: Model[];

        /**
        * The chat behaviors the user can select.
        */
        behaviors?: ChatBehavior[];
        
        /**
         * The model for client select in chat, if that is present
         * clients should forcefully update chat selected model.
         * 
         * Server returns this when starting and only when makes sense to 
         * force update a model, like a config change.
         */
        selectModel?: Model;

        /**
         * The behavior for client select in chat, if that is present
         * clients should forcefully update chat selected behavior.
         * 
         * Server returns this when starting and only when makes sense to 
         * force update a behavior, like a config change.
         */
        selectBehavior?: ChatBehavior;

        /**
        * Message to show when starting a new chat.
        */
        welcomeMessage?: string;
    }
}
```

### Tool updated (⬅️)

A server notification about a tool status update like a MCP or native tool.
This is useful for clients present to user the list of configured tools/MCPs,
their status and available tools and actions.

_Notification:_ 

* method: `tool/serverUpdated`
* params: `ToolServerUpdatedParams` defined as follows:

```typescript
type ToolServerUpdatedParams = EcaServerUpdatedParams | MCPServerUpdatedParams;

interface EcaServerUpdatedParams {
    type: 'native';
    
    name: 'ECA';
    
    status: 'running';

    /**
     * The built-in tools supported by eca.
     */
    tools: ServerTool[];
}

interface MCPServerUpdatedParams {
    type: 'mcp';
    
    /**
     * The server name.
     */
    name: string;
    
    /**
     * The command to start this server.
     */
    command: string;

    /**
     * The arguments to start this server.
     */
    args: string[];
    
    /**
     * The status of the server.
     */
    status: 'running' | 'starting' | 'stopped' | 'failed' | 'disabled';
    
    /**
     * The tools supported by this mcp server if not disabled.
     */
    tools?: ServerTool[];
}

interface ServerTool {
    /**
     * The server tool name.
     */
    name: string;
    
    /**
     * The server tool description.
     */
    description: string;
    
    /**
     * The server tool parameters.
     */
    parameters: any; 
    
    /**
     * Whther this tool is disabled.
     */
    disabled?: boolean;
}
```

### Stop MCP server (➡️)

A client notification for server to stop a MCP server, stopping the process.
Updates its status via `tool/serverUpdated` notification.

_Notification:_

* method: `mcp/stopServer`
* params: `MCPStopServerParams` defined as follows:

```typescript
interface MCPStopServerParams {
    /**
     * The MCP server name.
     */
    name: string;
}
```

### Start MCP server (➡️)

A client notification for server to start a stopped MCP server, starting the process again.
Updates its status via `tool/serverUpdated` notification.

_Notification:_

* method: `mcp/startServer`
* params: `MCPStartServerParams` defined as follows:

```typescript
interface MCPStartServerParams {
    /**
     * The server name.
     */
    name: string;
}
```

### Add MCP (↩️)

Soon

## General features

### showMessage (⬅️)

A notification from server telling client to present a message to user.

_Request:_ 

* method: `$/showMessage`
* params: `ShowMessageParams` defined as follows:

```typescript
type ShowMessageParams = Error;
```
