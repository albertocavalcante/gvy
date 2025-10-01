# Groovy REPL Specification

## Overview

The Groovy REPL (Read-Eval-Print Loop) is an interactive shell that provides immediate evaluation of Groovy expressions
with full access to the workspace compilation context. It leverages the existing `WorkspaceCompilationService` to
provide context-aware evaluation with proper classpath resolution.

## Core Requirements

### 1. Interactive Evaluation

- **Immediate Execution**: Evaluate Groovy expressions and statements instantly
- **Persistent State**: Variables and imports persist across evaluations
- **Context Awareness**: Access to all classes and dependencies in the workspace
- **Error Handling**: Graceful handling of compilation and runtime errors

### 2. Workspace Integration

- **Classpath Access**: Full access to project dependencies and compiled classes
- **Context Switching**: Ability to switch between different compilation contexts (main, test, etc.)
- **Hot Reload**: Automatic detection of workspace changes
- **Import Management**: Intelligent import suggestions and auto-imports

### 3. IDE Agnostic

- **LSP Protocol**: Standard LSP custom commands for REPL operations
- **Client Flexibility**: Support for various client implementations (VS Code, IntelliJ, etc.)
- **Protocol Extensions**: Clean extensions to LSP for REPL-specific features

## Functional Specifications

### Session Management

#### Session Creation

```json
{
  "command": "groovy/repl/create",
  "params": {
    "sessionId": "optional-session-id",
    "contextName": "main|test|specific-context",
    "imports": ["java.util.*", "groovy.transform.*"],
    "workingDirectory": "/path/to/workspace"
  }
}
```

#### Session Properties

- **Unique ID**: Each session has a unique identifier
- **Compilation Context**: Associated with a specific workspace context
- **Binding State**: Maintains variable bindings across evaluations
- **Import State**: Tracks imported packages and classes
- **History**: Maintains command history and results

### Code Evaluation

#### Basic Evaluation

```json
{
  "command": "groovy/repl/evaluate",
  "params": {
    "sessionId": "session-uuid",
    "code": "def x = [1, 2, 3]; x.collect { it * 2 }",
    "async": false
  }
}
```

#### Evaluation Response

```json
{
  "success": true,
  "value": "[2, 4, 6]",
  "type": "java.util.ArrayList",
  "output": "",
  "duration": 45,
  "bindings": {
    "x": {
      "value": "[1, 2, 3]",
      "type": "java.util.ArrayList"
    }
  },
  "diagnostics": [],
  "sideEffects": {
    "printOutput": "",
    "imports": [],
    "classesLoaded": []
  }
}
```

### Advanced Features

#### Code Completion

```json
{
  "command": "groovy/repl/getCompletions",
  "params": {
    "sessionId": "session-uuid",
    "code": "x.col",
    "position": 5
  }
}
```

#### Variable Inspection

```json
{
  "command": "groovy/repl/inspectVariable",
  "params": {
    "sessionId": "session-uuid",
    "variableName": "x"
  }
}
```

#### History Management

```json
{
  "command": "groovy/repl/getHistory",
  "params": {
    "sessionId": "session-uuid",
    "limit": 50
  }
}
```

## Technical Requirements

### Performance

- **Lazy Loading**: Classes loaded on-demand
- **Incremental Compilation**: Only recompile changed dependencies
- **Memory Management**: Efficient memory usage for long-running sessions
- **Concurrent Sessions**: Support multiple concurrent REPL sessions

### Error Handling

- **Compilation Errors**: Clear error messages with line numbers
- **Runtime Exceptions**: Stack traces with REPL context
- **Timeout Handling**: Configurable execution timeouts
- **Recovery**: Graceful recovery from errors without losing session state

### Security

- **Sandboxing**: Optional sandboxing for untrusted code execution
- **Resource Limits**: Configurable memory and CPU limits
- **Code Restrictions**: Optional restrictions on dangerous operations

## Use Cases

### 1. Interactive Development

```groovy
// Explore APIs
def person = new Person("John", 30)
person.metaClass.methods.findAll { it.name.startsWith("get") }

// Test data transformations
def data = [1, 2, 3, 4, 5]
data.findAll { it % 2 == 0 }.collect { it * it }

// Debug expressions
def result = complexCalculation()
result.class
result.properties
```

### 2. Learning and Exploration

```groovy
// Explore Groovy features
def closure = { x, y -> x + y }
closure.metaClass
closure.maximumNumberOfParameters

// Test regular expressions
def pattern = ~/\d+\.\d+/
"Price: 19.99".find(pattern)

// Experiment with AST transformations
@ToString class Sample { String name }
new Sample(name: "test")
```

### 3. Rapid Prototyping

```groovy
// Quick data processing
def csv = new File("data.csv").readLines()
def headers = csv[0].split(",")
def rows = csv.tail().collect { it.split(",") }

// API testing
@Grab('org.apache.commons:commons-lang3:3.12.0')
import org.apache.commons.lang3.StringUtils
StringUtils.capitalize("hello world")
```

### 4. Debugging and Inspection

```groovy
// Inspect runtime state
def obj = getComplexObject()
obj.properties.each { k, v -> println "$k: $v" }

// Test method behavior
def result = obj.someMethod()
result.getClass().declaredFields

// Analyze performance
def start = System.currentTimeMillis()
performOperation()
def duration = System.currentTimeMillis() - start
```

## Integration Scenarios

### VS Code Extension

- **Terminal Integration**: REPL appears as an integrated terminal
- **Command Palette**: Quick REPL commands via command palette
- **Code Execution**: Run selected code in REPL from editor
- **Variable View**: Sidebar showing current REPL variables

### IntelliJ Plugin

- **Tool Window**: Dedicated REPL tool window
- **Console Integration**: Standard IntelliJ console experience
- **Debugger Integration**: Set breakpoints in REPL code
- **Code Assistance**: Full code completion and inspection

### Jupyter Notebook Support

- **Notebook Documents**: Support for `.groovynb` files
- **Cell Execution**: Execute individual cells in shared context
- **Rich Output**: HTML/markdown output support
- **Export Features**: Export to various formats

## Configuration

### Server Configuration

```json
{
  "groovy.repl.enabled": true,
  "groovy.repl.maxSessions": 10,
  "groovy.repl.sessionTimeout": 3600000,
  "groovy.repl.executionTimeout": 30000,
  "groovy.repl.maxMemory": "512m",
  "groovy.repl.autoImports": ["java.util.*", "java.io.*", "groovy.transform.*"],
  "groovy.repl.sandboxing": false,
  "groovy.repl.historySize": 1000
}
```

### Client Configuration

```json
{
  "groovy.repl.terminal.enabled": true,
  "groovy.repl.terminal.autoStart": false,
  "groovy.repl.completion.enabled": true,
  "groovy.repl.inspection.enabled": true,
  "groovy.repl.history.persistent": true
}
```

## Success Criteria

### Phase 1: Basic REPL

- ✅ Execute simple Groovy expressions
- ✅ Maintain variable state across evaluations
- ✅ Handle compilation and runtime errors gracefully
- ✅ Basic session management

### Phase 2: Workspace Integration

- ✅ Access to workspace classes and dependencies
- ✅ Context-aware evaluation
- ✅ Proper classpath resolution
- ✅ Import management

### Phase 3: Advanced Features

- ✅ Code completion in REPL context
- ✅ Variable inspection and debugging
- ✅ History management and persistence
- ✅ Multiple session support

### Phase 4: Client Integration

- ✅ VS Code terminal integration
- ✅ IntelliJ console integration
- ✅ Jupyter notebook support (optional)
- ✅ Rich client features (variable view, etc.)

## Testing Strategy

### Unit Tests

- Core REPL engine functionality
- Session management
- Error handling scenarios
- Memory management

### Integration Tests

- Workspace integration
- LSP command handling
- Client-server communication
- Performance benchmarks

### End-to-End Tests

- Full client-server scenarios
- Multi-session workflows
- Complex evaluation scenarios
- Error recovery testing
