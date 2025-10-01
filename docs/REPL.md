# Groovy REPL Integration

This document provides comprehensive documentation for the Groovy Language Server Protocol (LSP) REPL implementation,
including architecture, usage, and client integration guidelines.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [LSP Protocol Extensions](#lsp-protocol-extensions)
- [Session Management](#session-management)
- [Client Integration Guide](#client-integration-guide)
- [Command Reference](#command-reference)
- [Error Handling](#error-handling)
- [Configuration](#configuration)
- [Examples](#examples)
- [Troubleshooting](#troubleshooting)

## Overview

The Groovy LSP REPL provides an interactive code evaluation environment that integrates seamlessly with IDE extensions
through the Language Server Protocol. It supports:

- **Multi-session management** - Multiple concurrent REPL sessions
- **Workspace integration** - Access to project classpath and dependencies
- **Context switching** - Switch between different compilation contexts (main, test, etc.)
- **Variable inspection** - Introspect variables, methods, and properties
- **Command history** - Track and replay previous commands
- **Auto-completion** - Context-aware completions including workspace symbols
- **Error diagnostics** - Rich error reporting with LSP diagnostics

## Architecture

The REPL implementation consists of several key components:

### Core Components

```
┌─────────────────────────────────────────────────────────────┐
│                    IDE Extension                            │
└─────────────────────┬───────────────────────────────────────┘
                      │ LSP executeCommand requests
                      ▼
┌─────────────────────────────────────────────────────────────┐
│                 ReplCommandHandler                          │
│  • Routes LSP commands to session manager                  │
│  • Converts between LSP and internal data structures       │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│                ReplSessionManager                           │
│  • Manages session lifecycle                               │
│  • Handles workspace integration                           │
│  • Coordinates context switching                           │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│                  GroovyInterop                              │
│  • Kotlin-Groovy bridge layer                              │
│  • Type-safe conversions                                   │
│  • Engine creation and management                          │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│               GroovyReplEngine.groovy                       │
│  • Actual Groovy REPL implementation                       │
│  • Code evaluation and execution                           │
│  • Variable binding management                             │
└─────────────────────────────────────────────────────────────┘
```

### Supporting Components

- **SessionLifecycleManager** - Handles session creation, cleanup, and timeout management
- **SessionValidation** - Validates session operations and parameters
- **ContextSwitchHelper** - Manages compilation context switching
- **ParameterParser** - Parses LSP command parameters into typed data structures
- **GroovyResultConverter** - Converts Groovy results to Kotlin data structures
- **GroovyEngineFactory** - Creates and configures Groovy REPL engines

## LSP Protocol Extensions

The REPL functionality is exposed through custom LSP `workspace/executeCommand` requests. All commands follow the
pattern:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "workspace/executeCommand",
  "params": {
    "command": "groovy/repl/{operation}",
    "arguments": [
      {
        // Command-specific parameters
      }
    ]
  }
}
```

### Supported Commands

| Command                     | Description                |
| --------------------------- | -------------------------- |
| `groovy/repl/create`        | Create a new REPL session  |
| `groovy/repl/evaluate`      | Evaluate code in a session |
| `groovy/repl/complete`      | Get code completions       |
| `groovy/repl/inspect`       | Inspect variable details   |
| `groovy/repl/history`       | Get command history        |
| `groovy/repl/reset`         | Reset session state        |
| `groovy/repl/destroy`       | Destroy a session          |
| `groovy/repl/list`          | List active sessions       |
| `groovy/repl/switchContext` | Switch compilation context |

## Session Management

### Session Lifecycle

1. **Creation** - Sessions are created with optional configuration
2. **Active** - Sessions accept commands and maintain state
3. **Idle** - Sessions not used recently (configurable timeout)
4. **Cleanup** - Automatic cleanup of expired sessions

### Session Configuration

```kotlin
data class SessionConfiguration(
    val autoImports: List<String> = emptyList(),
    val executionTimeout: Duration = Duration.ofSeconds(30),
    val maxMemory: String = "512m",
    val sandboxing: Boolean = false,
    val historySize: Int = 1000,
    val enableMetaClassModifications: Boolean = true,
    val allowedPackages: Set<String> = emptySet(),
    val disallowedMethods: Set<String> = emptySet()
)
```

### Context Switching

Sessions can switch between different compilation contexts:

- **main** - Main source set context
- **test** - Test source set context
- **custom** - User-defined contexts

Context switching preserves compatible variable bindings when possible.

## Client Integration Guide

### Basic Integration Steps

1. **Check Server Capabilities**

   ```typescript
   const capabilities = await client.sendRequest("initialize", params);
   const replSupported = capabilities.executeCommandProvider?.commands.some((cmd) => cmd.startsWith("groovy/repl/"));
   ```

2. **Create a Session**

   ```typescript
   const createResult = await client.sendRequest("workspace/executeCommand", {
     command: "groovy/repl/create",
     arguments: [
       {
         sessionId: "my-session", // Optional, auto-generated if not provided
         contextName: "main", // Optional, defaults to 'main'
         imports: ["java.util.*"], // Optional auto-imports
         configuration: {
           executionTimeout: 30000,
           historySize: 1000,
         },
       },
     ],
   });
   ```

3. **Evaluate Code**
   ```typescript
   const evalResult = await client.sendRequest("workspace/executeCommand", {
     command: "groovy/repl/evaluate",
     arguments: [
       {
         sessionId: "my-session",
         code: 'def greeting = "Hello, World!"\ngreetings.toUpperCase()',
         captureOutput: true,
         includeBindings: false,
       },
     ],
   });
   ```

### Recommended Client Architecture

```typescript
class GroovyReplClient {
  private sessionId: string;
  private client: LanguageClient;

  async createSession(config?: SessionConfig): Promise<void> {
    // Implementation
  }

  async evaluate(code: string): Promise<EvaluationResult> {
    // Implementation
  }

  async getCompletions(code: string, position: number): Promise<CompletionItem[]> {
    // Implementation
  }

  async inspectVariable(name: string): Promise<VariableInfo | null> {
    // Implementation
  }

  async getHistory(limit?: number): Promise<HistoryEntry[]> {
    // Implementation
  }
}
```

## Command Reference

### groovy/repl/create

Creates a new REPL session.

**Parameters:**

```json
{
  "sessionId": "optional-session-id",
  "contextName": "main",
  "imports": ["java.util.*", "groovy.transform.*"],
  "configuration": {
    "executionTimeout": 30000,
    "maxMemory": "512m",
    "sandboxing": false,
    "historySize": 1000
  }
}
```

**Response:**

```json
{
  "sessionId": "repl-1634567890123-456",
  "contextName": "main",
  "availableContexts": ["main", "test"],
  "configuration": {
    /* ... */
  },
  "initialBindings": []
}
```

### groovy/repl/evaluate

Evaluates code in a REPL session.

**Parameters:**

```json
{
  "sessionId": "repl-1634567890123-456",
  "code": "def x = 42\nx * 2",
  "async": false,
  "includeBindings": false,
  "captureOutput": true
}
```

**Response:**

```json
{
  "success": true,
  "value": 84,
  "type": "java.lang.Integer",
  "output": "",
  "duration": 45,
  "bindings": null,
  "diagnostics": null,
  "sideEffects": {
    "printOutput": "",
    "errorOutput": "",
    "imports": [],
    "classesLoaded": [],
    "systemPropertyChanges": {}
  }
}
```

### groovy/repl/complete

Gets code completions for the current input.

**Parameters:**

```json
{
  "sessionId": "repl-1634567890123-456",
  "code": "String.class.getMet",
  "position": 18,
  "includeBindings": true,
  "includeWorkspace": true
}
```

**Response:**

```json
{
  "completions": [
    {
      "label": "getMethods",
      "kind": 2,
      "detail": "REPL completion"
    }
  ],
  "bindingCompletions": [
    {
      "name": "x",
      "type": "java.lang.Integer",
      "kind": "variable",
      "documentation": null,
      "signature": null
    }
  ]
}
```

### groovy/repl/inspect

Inspects a variable for detailed information.

**Parameters:**

```json
{
  "sessionId": "repl-1634567890123-456",
  "variableName": "x",
  "includeMetaClass": false,
  "includeMethods": true,
  "includeProperties": true
}
```

**Response:**

```json
{
  "variable": {
    "name": "x",
    "value": "42",
    "type": "java.lang.Integer",
    "isNull": false,
    "hierarchy": ["java.lang.Integer", "java.lang.Number", "java.lang.Object"]
  },
  "methods": [
    {
      "name": "intValue",
      "signature": "intValue()",
      "returnType": "int",
      "isStatic": false,
      "visibility": "public"
    }
  ],
  "properties": [],
  "metaClass": null
}
```

### groovy/repl/history

Gets command history for a session.

**Parameters:**

```json
{
  "sessionId": "repl-1634567890123-456",
  "limit": 10,
  "offset": 0,
  "includeResults": false
}
```

**Response:**

```json
{
  "entries": [
    {
      "id": 0,
      "timestamp": "2023-10-18T10:30:00Z",
      "code": "def x = 42",
      "success": true,
      "duration": 12
    }
  ],
  "totalCount": 1
}
```

### groovy/repl/switchContext

Switches the compilation context for a session.

**Parameters:**

```json
{
  "sessionId": "repl-1634567890123-456",
  "contextName": "test",
  "preserveBindings": true
}
```

**Response:**

```json
{
  "success": true,
  "previousContext": "main",
  "newContext": "test",
  "preservedBindings": ["x", "greeting"],
  "lostBindings": [],
  "warnings": []
}
```

## Error Handling

### Error Response Format

When commands fail, they return error information in the response:

```json
{
  "success": false,
  "error": {
    "message": "Compilation error",
    "type": "GroovyCompilationException",
    "diagnostics": [
      {
        "range": {
          "start": { "line": 0, "character": 0 },
          "end": { "line": 0, "character": 10 }
        },
        "severity": 1,
        "message": "Unexpected token",
        "source": "groovy-repl"
      }
    ]
  }
}
```

### Common Error Scenarios

1. **Session Not Found**

   ```json
   {
     "error": {
       "code": -32602,
       "message": "Session not found: invalid-session-id"
     }
   }
   ```

2. **Compilation Errors**

   ```json
   {
     "success": false,
     "diagnostics": [
       {
         "message": "Unexpected token: '+'",
         "severity": 1
       }
     ]
   }
   ```

3. **Runtime Errors**

   ```json
   {
     "success": false,
     "error": {
       "message": "java.lang.NullPointerException",
       "type": "java.lang.NullPointerException"
     }
   }
   ```

4. **Timeout Errors**
   ```json
   {
     "success": false,
     "diagnostics": [
       {
         "message": "Execution timed out after 30000ms"
       }
     ]
   }
   ```

## Configuration

### Server Configuration

The REPL can be configured in the server initialization options:

```json
{
  "initializationOptions": {
    "replEnabled": true,
    "replConfig": {
      "maxSessions": 10,
      "sessionTimeoutMinutes": 60,
      "defaultExecutionTimeoutMs": 30000,
      "enableSandboxing": false
    }
  }
}
```

### Session-Level Configuration

Individual sessions can be configured during creation:

```typescript
const sessionConfig = {
  autoImports: ["java.util.*", "groovy.transform.*"],
  executionTimeout: 30000,
  maxMemory: "512m",
  sandboxing: false,
  historySize: 1000,
  enableMetaClassModifications: true,
  allowedPackages: new Set(["java.util", "groovy.lang"]),
  disallowedMethods: new Set(["System.exit"]),
};
```

## Examples

### Basic REPL Interaction

```typescript
// Create session
const session = await client.executeCommand("groovy/repl/create", [
  {
    contextName: "main",
    imports: ["java.util.*"],
  },
]);

// Evaluate simple expression
const result1 = await client.executeCommand("groovy/repl/evaluate", [
  {
    sessionId: session.sessionId,
    code: "def numbers = [1, 2, 3, 4, 5]",
  },
]);

// Evaluate expression using previous binding
const result2 = await client.executeCommand("groovy/repl/evaluate", [
  {
    sessionId: session.sessionId,
    code: "numbers.sum()",
    includeBindings: true,
  },
]);

console.log(result2.value); // 15
```

### Working with Classes

```typescript
// Define a class
await client.executeCommand("groovy/repl/evaluate", [
  {
    sessionId: session.sessionId,
    code: `
    class Person {
      String name
      int age

      String greet() {
        return "Hello, I'm \${name} and I'm \${age} years old"
      }
    }
  `,
  },
]);

// Create instance and use it
const result = await client.executeCommand("groovy/repl/evaluate", [
  {
    sessionId: session.sessionId,
    code: `
    def person = new Person(name: 'Alice', age: 30)
    person.greet()
  `,
  },
]);

console.log(result.value); // "Hello, I'm Alice and I'm 30 years old"
```

### Error Handling Example

```typescript
try {
  const result = await client.executeCommand("groovy/repl/evaluate", [
    {
      sessionId: session.sessionId,
      code: "invalid syntax +",
    },
  ]);

  if (!result.success) {
    // Handle compilation errors
    result.diagnostics?.forEach((diagnostic) => {
      console.error(`Error: ${diagnostic.message}`);
    });
  }
} catch (error) {
  // Handle LSP errors
  console.error("LSP Error:", error);
}
```

### Auto-completion Example

```typescript
const completions = await client.executeCommand("groovy/repl/complete", [
  {
    sessionId: session.sessionId,
    code: "numbers.ea",
    position: 10,
    includeBindings: true,
  },
]);

completions.completions.forEach((item) => {
  console.log(`${item.label} - ${item.detail}`);
});
// Output: "each - REPL completion"
```

## Troubleshooting

### Common Issues

1. **Session Creation Fails**

   - Check that REPL is enabled in server configuration
   - Verify workspace has valid Groovy project structure
   - Check server logs for initialization errors

2. **Code Evaluation Timeouts**

   - Increase execution timeout in session configuration
   - Check for infinite loops or blocking operations
   - Consider using async evaluation for long-running code

3. **Missing Completions**

   - Verify workspace dependencies are resolved
   - Check that compilation context includes required classpath
   - Ensure auto-imports are configured correctly

4. **Context Switch Failures**
   - Verify target context exists in workspace
   - Check that workspace compilation is enabled
   - Review binding preservation warnings

### Debug Information

Enable debug logging to get detailed information:

```json
{
  "initializationOptions": {
    "logLevel": "DEBUG",
    "replConfig": {
      "enableDebugLogging": true
    }
  }
}
```

### Performance Considerations

1. **Session Limits** - Configure appropriate maximum session count
2. **Memory Usage** - Monitor session memory consumption
3. **Cleanup** - Ensure automatic session cleanup is enabled
4. **Classpath Size** - Large classpaths may slow initialization

### Security Considerations

1. **Sandboxing** - Enable for untrusted code execution
2. **Method Restrictions** - Configure disallowed methods
3. **Package Access** - Limit accessible packages
4. **Resource Limits** - Set appropriate memory and timeout limits

This documentation should serve as a comprehensive guide for understanding and integrating with the Groovy LSP REPL
functionality. For additional support or questions, refer to the project's issue tracker or contribute improvements to
this documentation.
