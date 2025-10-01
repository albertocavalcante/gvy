# Groovy REPL LSP Protocol Extensions

## Overview

This document defines the Language Server Protocol extensions for the Groovy REPL functionality. These extensions follow
LSP conventions while providing comprehensive REPL capabilities.

## Protocol Extension Design

### 1. Custom Commands

All REPL functionality is exposed through LSP custom commands using the `workspace/executeCommand` method with specific
command identifiers.

### 2. Notification Flow

```
Client                     Server
  |                          |
  |  executeCommand          |
  |  (groovy/repl/create)    |
  |------------------------->|
  |                          |
  |  CreateSessionResult     |
  |<-------------------------|
  |                          |
  |  executeCommand          |
  |  (groovy/repl/evaluate)  |
  |------------------------->|
  |                          |
  |  EvaluateResult          |
  |<-------------------------|
```

### 3. Error Handling

All commands follow standard LSP error handling with specific error codes for REPL-related failures.

## Command Specifications

### 1. Create REPL Session

**Command**: `groovy/repl/create`

**Purpose**: Creates a new REPL session with optional configuration

**Request**:

```typescript
interface CreateSessionParams {
  sessionId?: string; // Optional session ID (auto-generated if not provided)
  contextName?: string; // Compilation context ("main", "test", etc.)
  imports?: string[]; // Auto-imports for the session
  configuration?: SessionConfiguration;
}

interface SessionConfiguration {
  autoImports?: string[]; // Default imports
  executionTimeout?: number; // Timeout in milliseconds
  maxMemory?: string; // Memory limit (e.g., "512m")
  sandboxing?: boolean; // Enable sandboxing
  historySize?: number; // Command history size
}
```

**Response**:

```typescript
interface CreateSessionResult {
  sessionId: string; // Unique session identifier
  contextName: string; // Active compilation context
  availableContexts: string[]; // All available contexts
  configuration: SessionConfiguration;
  initialBindings: VariableInfo[];
}
```

**Example**:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "workspace/executeCommand",
  "params": {
    "command": "groovy/repl/create",
    "arguments": [
      {
        "contextName": "main",
        "imports": ["java.util.*", "groovy.transform.*"],
        "configuration": {
          "executionTimeout": 30000,
          "maxMemory": "256m",
          "sandboxing": false
        }
      }
    ]
  }
}
```

### 2. Evaluate Code

**Command**: `groovy/repl/evaluate`

**Purpose**: Evaluates Groovy code in the specified session

**Request**:

```typescript
interface EvaluateParams {
  sessionId: string; // Session identifier
  code: string; // Groovy code to evaluate
  async?: boolean; // Execute asynchronously (default: false)
  includeBindings?: boolean; // Include current bindings in response
  captureOutput?: boolean; // Capture System.out/err output
}
```

**Response**:

```typescript
interface EvaluateResult {
  success: boolean; // Evaluation success
  value?: any; // Result value (serialized)
  type?: string; // Result type name
  output?: string; // Captured output
  duration: number; // Execution time in milliseconds
  bindings?: VariableInfo[]; // Current variable bindings
  diagnostics?: Diagnostic[]; // Compilation/runtime diagnostics
  sideEffects?: SideEffects; // Any side effects
}

interface VariableInfo {
  name: string;
  value: string; // String representation
  type: string; // Fully qualified type name
  isNull: boolean;
  size?: number; // For collections/arrays
}

interface SideEffects {
  printOutput?: string; // System.out content
  errorOutput?: string; // System.err content
  imports?: string[]; // New imports added
  classesLoaded?: string[]; // Classes loaded during execution
  systemProperties?: { [key: string]: string }; // System property changes
}
```

**Example**:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "workspace/executeCommand",
  "params": {
    "command": "groovy/repl/evaluate",
    "arguments": [
      {
        "sessionId": "session-uuid-123",
        "code": "def numbers = [1, 2, 3, 4, 5]\nnumbers.findAll { it % 2 == 0 }",
        "includeBindings": true,
        "captureOutput": true
      }
    ]
  }
}
```

### 3. Get Completions

**Command**: `groovy/repl/complete`

**Purpose**: Provides code completion for REPL context

**Request**:

```typescript
interface ReplCompletionParams {
  sessionId: string; // Session identifier
  code: string; // Current code being typed
  position: number; // Cursor position
  includeBindings?: boolean; // Include session variables
  includeWorkspace?: boolean; // Include workspace symbols
}
```

**Response**:

```typescript
interface ReplCompletionResult {
  completions: CompletionItem[];
  bindingCompletions: BindingCompletion[];
}

interface BindingCompletion {
  name: string;
  type: string;
  kind: "variable" | "method" | "property";
  documentation?: string;
  signature?: string; // For methods
}
```

### 4. Inspect Variable

**Command**: `groovy/repl/inspect`

**Purpose**: Provides detailed information about a variable or expression

**Request**:

```typescript
interface InspectParams {
  sessionId: string;
  variableName: string; // Variable to inspect
  includeMetaClass?: boolean; // Include metaclass information
  includeMethods?: boolean; // Include available methods
  includeProperties?: boolean; // Include properties
}
```

**Response**:

```typescript
interface InspectResult {
  variable: DetailedVariableInfo;
  methods?: MethodInfo[];
  properties?: PropertyInfo[];
  metaClass?: MetaClassInfo;
}

interface DetailedVariableInfo extends VariableInfo {
  documentation?: string;
  sourceLocation?: Location;
  hierarchy?: string[]; // Class hierarchy
}

interface MethodInfo {
  name: string;
  signature: string;
  returnType: string;
  parameters: ParameterInfo[];
  isStatic: boolean;
  visibility: "public" | "protected" | "private";
  documentation?: string;
}

interface PropertyInfo {
  name: string;
  type: string;
  getter?: boolean;
  setter?: boolean;
  isStatic: boolean;
  visibility: "public" | "protected" | "private";
}

interface MetaClassInfo {
  className: string;
  methods: string[];
  properties: string[];
  categories: string[]; // Applied categories
}
```

### 5. Get History

**Command**: `groovy/repl/history`

**Purpose**: Retrieves command history for a session

**Request**:

```typescript
interface HistoryParams {
  sessionId: string;
  limit?: number; // Maximum number of entries (default: 50)
  offset?: number; // Starting offset (default: 0)
  includeResults?: boolean; // Include evaluation results
}
```

**Response**:

```typescript
interface HistoryResult {
  entries: HistoryEntry[];
  totalCount: number;
}

interface HistoryEntry {
  id: number; // Sequential entry ID
  timestamp: string; // ISO timestamp
  code: string; // Executed code
  success: boolean; // Execution success
  result?: any; // Result value (if requested)
  duration: number; // Execution time
}
```

### 6. Reset Session

**Command**: `groovy/repl/reset`

**Purpose**: Resets a REPL session to initial state

**Request**:

```typescript
interface ResetParams {
  sessionId: string;
  preserveImports?: boolean; // Keep current imports (default: false)
  preserveHistory?: boolean; // Keep command history (default: true)
}
```

**Response**: `void`

### 7. Destroy Session

**Command**: `groovy/repl/destroy`

**Purpose**: Destroys a REPL session and releases resources

**Request**:

```typescript
interface DestroyParams {
  sessionId: string;
  saveHistory?: boolean; // Save history to file (default: false)
}
```

**Response**: `void`

### 8. List Sessions

**Command**: `groovy/repl/list`

**Purpose**: Lists all active REPL sessions

**Request**: No parameters

**Response**:

```typescript
interface ListSessionsResult {
  sessions: SessionInfo[];
}

interface SessionInfo {
  sessionId: string;
  contextName: string;
  createdAt: string; // ISO timestamp
  lastUsed: string; // ISO timestamp
  variableCount: number;
  memoryUsage: number; // In bytes
  isActive: boolean;
}
```

### 9. Switch Context

**Command**: `groovy/repl/switchContext`

**Purpose**: Switches the compilation context for a session

**Request**:

```typescript
interface SwitchContextParams {
  sessionId: string;
  contextName: string; // Target context name
  preserveBindings?: boolean; // Try to preserve compatible bindings
}
```

**Response**:

```typescript
interface SwitchContextResult {
  success: boolean;
  previousContext: string;
  newContext: string;
  preservedBindings: string[]; // Variables that were preserved
  lostBindings: string[]; // Variables that were lost
  warnings: string[]; // Any warnings about the switch
}
```

## Server Capabilities Extension

The server should advertise REPL support in its capabilities:

```typescript
interface ServerCapabilities {
  // ... standard capabilities
  experimental?: {
    groovyRepl?: {
      enabled: boolean;
      supportedCommands: string[];
      maxSessions: number;
      defaultTimeout: number;
      sandboxingSupported: boolean;
      contextSwitchingSupported: boolean;
    };
  };
}
```

## Error Codes

Custom error codes for REPL operations:

```typescript
enum ReplErrorCode {
  SessionNotFound = -32001,
  SessionLimitExceeded = -32002,
  ExecutionTimeout = -32003,
  CompilationError = -32004,
  RuntimeError = -32005,
  ContextNotFound = -32006,
  InsufficientMemory = -32007,
  SecurityViolation = -32008,
  InvalidCode = -32009,
  SessionExpired = -32010,
}
```

## Notifications

### Session State Changes

**Notification**: `groovy/repl/sessionStateChanged`

**Purpose**: Notifies client of session state changes

```typescript
interface SessionStateChangedParams {
  sessionId: string;
  state: "created" | "active" | "idle" | "expired" | "destroyed";
  timestamp: string;
  reason?: string;
}
```

### Workspace Context Changes

**Notification**: `groovy/repl/contextChanged`

**Purpose**: Notifies client when workspace contexts change

```typescript
interface ContextChangedParams {
  contextName: string;
  changeType: "created" | "modified" | "deleted";
  affectedSessions: string[];
}
```

## Protocol Flow Examples

### 1. Basic REPL Session

```
1. Client: workspace/executeCommand(groovy/repl/create)
2. Server: CreateSessionResult{sessionId: "abc123"}
3. Client: workspace/executeCommand(groovy/repl/evaluate, {code: "def x = 42"})
4. Server: EvaluateResult{success: true, value: 42, bindings: [{name: "x", value: "42", type: "Integer"}]}
5. Client: workspace/executeCommand(groovy/repl/evaluate, {code: "x * 2"})
6. Server: EvaluateResult{success: true, value: 84}
```

### 2. Error Handling

```
1. Client: workspace/executeCommand(groovy/repl/evaluate, {code: "def x = [ // syntax error"})
2. Server: EvaluateResult{
     success: false,
     diagnostics: [{
       severity: Error,
       message: "unexpected token: // at line: 1 column: 11",
       range: {start: {line: 0, character: 10}, end: {line: 0, character: 13}}
     }]
   }
```

### 3. Completion Flow

```
1. Client: workspace/executeCommand(groovy/repl/complete, {code: "x.to", position: 4})
2. Server: ReplCompletionResult{
     completions: [
       {label: "toString", kind: Method, detail: "String toString()"},
       {label: "toInteger", kind: Method, detail: "Integer toInteger()"}
     ]
   }
```

## Client Implementation Guidelines

### 1. Session Management

- Maintain session state locally
- Handle session expiration gracefully
- Provide session switching UI

### 2. User Experience

- Show typing indicators during evaluation
- Display results with syntax highlighting
- Provide variable inspection on hover/click

### 3. Error Display

- Show compilation errors inline
- Display runtime exceptions with stack traces
- Provide quick fixes for common errors

### 4. Performance

- Cache completion results locally
- Debounce completion requests
- Use async evaluation for long-running code

This protocol design ensures comprehensive REPL functionality while maintaining compatibility with standard LSP
practices.
