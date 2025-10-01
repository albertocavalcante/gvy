# Groovy REPL Architecture

## Overview

The Groovy REPL implementation follows a hybrid Kotlin/Groovy architecture that leverages native Groovy capabilities for
execution while maintaining clean LSP protocol integration through Kotlin.

## Architecture Principles

### 1. Language Separation

- **Kotlin**: LSP protocol handling, session management, async operations
- **Groovy**: Native execution, dynamic dispatch, meta-programming

### 2. Layered Design

```
┌─────────────────────────────────────┐
│         LSP Client (VS Code)        │
├─────────────────────────────────────┤
│      LSP Protocol (JSON-RPC)       │
├─────────────────────────────────────┤
│    REPL Command Handler (Kotlin)   │
├─────────────────────────────────────┤
│   REPL Session Manager (Kotlin)    │
├─────────────────────────────────────┤
│    Groovy-Kotlin Interop Layer     │
├─────────────────────────────────────┤
│    Groovy REPL Engine (Groovy)     │
├─────────────────────────────────────┤
│  Workspace Compilation Service     │
└─────────────────────────────────────┘
```

### 3. Context Integration

- **WorkspaceCompilationService**: Provides classpath and compilation context
- **CompilationContextManager**: Manages different contexts (main, test, etc.)
- **SymbolTable**: Shared symbol resolution across REPL and LSP

## Core Components

### 1. GroovyReplEngine (Groovy)

**Location**: `src/main/groovy/com/github/albertocavalcante/groovylsp/repl/GroovyReplEngine.groovy`

```groovy
@CompileStatic
class GroovyReplEngine {
    private GroovyShell shell
    private Binding binding
    private CompilerConfiguration config
    private List<String> commandHistory
    private Map<String, Object> metadata

    // Core evaluation method
    ReplResult evaluate(String code)

    // State management
    Map<String, Object> getCurrentBindings()
    void resetState()
    void addImport(String importStatement)

    // Completion support
    List<String> getCompletionCandidates(String partial)

    // Introspection
    TypeInfo getTypeInfo(String variableName)
    List<MethodInfo> getAvailableMethods(String objectName)
}
```

**Key Features**:

- Native GroovyShell for evaluation
- Persistent binding state
- Auto-import management
- Dynamic completion generation
- Type introspection capabilities

### 2. ReplSessionManager (Kotlin)

**Location**: `src/main/kotlin/com/github/albertocavalcante/groovylsp/repl/ReplSessionManager.kt`

```kotlin
class ReplSessionManager(
    private val workspaceService: WorkspaceCompilationService,
    private val coroutineScope: CoroutineScope
) {
    private val sessions = ConcurrentHashMap<String, ReplSession>()
    private val sessionCleanup = ScheduledExecutorService.newSingleThreadScheduledExecutor()

    suspend fun createSession(params: CreateSessionParams): ReplSession
    suspend fun evaluateCode(sessionId: String, code: String): ReplResult
    suspend fun getCompletions(sessionId: String, code: String, position: Int): List<CompletionItem>
    suspend fun resetSession(sessionId: String)
    suspend fun destroySession(sessionId: String)

    // Lifecycle management
    private fun scheduleCleanup(session: ReplSession)
    private fun loadContextClasspath(contextName: String): List<Path>
}
```

**Responsibilities**:

- Session lifecycle management
- Groovy engine instantiation
- Context synchronization
- Memory management
- Cleanup scheduling

### 3. ReplCommandHandler (Kotlin)

**Location**: `src/main/kotlin/com/github/albertocavalcante/groovylsp/repl/ReplCommandHandler.kt`

```kotlin
class ReplCommandHandler(
    private val sessionManager: ReplSessionManager,
    private val historyManager: ReplHistoryManager
) {
    @JsonRequest("groovy/repl/create")
    fun createSession(params: CreateSessionParams): CompletableFuture<CreateSessionResult>

    @JsonRequest("groovy/repl/evaluate")
    fun evaluateCode(params: EvaluateParams): CompletableFuture<EvaluateResult>

    @JsonRequest("groovy/repl/complete")
    fun getCompletions(params: CompletionParams): CompletableFuture<CompletionResult>

    @JsonRequest("groovy/repl/inspect")
    fun inspectVariable(params: InspectParams): CompletableFuture<InspectResult>

    @JsonRequest("groovy/repl/history")
    fun getHistory(params: HistoryParams): CompletableFuture<HistoryResult>

    @JsonRequest("groovy/repl/reset")
    fun resetSession(params: ResetParams): CompletableFuture<Void>

    @JsonRequest("groovy/repl/destroy")
    fun destroySession(params: DestroyParams): CompletableFuture<Void>
}
```

### 4. Groovy-Kotlin Interop Layer

**Location**: `src/main/kotlin/com/github/albertocavalcante/groovylsp/repl/GroovyInterop.kt`

```kotlin
object GroovyInterop {
    fun createReplEngine(
        classpath: List<String>,
        imports: List<String>
    ): Any // Returns Groovy object

    fun evaluateCode(engine: Any, code: String): ReplResult
    fun getBindings(engine: Any): Map<String, Any?>
    fun getCompletions(engine: Any, partial: String): List<String>
    fun resetEngine(engine: Any)

    // Type-safe conversion utilities
    private fun convertGroovyResult(result: Any?): ReplResult
    private fun convertBindings(bindings: Map<String, Any?>): Map<String, VariableInfo>
}
```

## Data Models

### 1. ReplResult

```kotlin
data class ReplResult(
    val success: Boolean,
    val value: Any?,
    val type: String?,
    val output: String,
    val duration: Long,
    val bindings: Map<String, VariableInfo>,
    val diagnostics: List<Diagnostic>,
    val sideEffects: SideEffects
)

data class VariableInfo(
    val name: String,
    val value: String,
    val type: String,
    val isNull: Boolean
)

data class SideEffects(
    val printOutput: String,
    val imports: List<String>,
    val classesLoaded: List<String>,
    val systemPropertyChanges: Map<String, String>
)
```

### 2. ReplSession

```kotlin
data class ReplSession(
    val id: String,
    val contextName: String,
    val engine: Any, // Groovy engine instance
    val createdAt: Instant,
    var lastUsed: Instant,
    val configuration: SessionConfiguration
)

data class SessionConfiguration(
    val autoImports: List<String>,
    val executionTimeout: Duration,
    val maxMemory: String,
    val sandboxing: Boolean
)
```

### 3. Protocol Messages

```kotlin
// Request/Response models for LSP commands
data class CreateSessionParams(
    val sessionId: String? = null,
    val contextName: String? = null,
    val imports: List<String> = emptyList(),
    val configuration: SessionConfiguration? = null
)

data class EvaluateParams(
    val sessionId: String,
    val code: String,
    val async: Boolean = false
)

data class CompletionParams(
    val sessionId: String,
    val code: String,
    val position: Int
)
```

## Integration Points

### 1. WorkspaceCompilationService Integration

```kotlin
class ReplWorkspaceIntegration(
    private val workspaceService: WorkspaceCompilationService
) {
    fun getContextClasspath(contextName: String): List<String> {
        val context = workspaceService.getContextByName(contextName)
        return context?.classpath?.map { it.toString() } ?: emptyList()
    }

    fun getContextClasses(contextName: String): List<ClassInfo> {
        val symbolTable = workspaceService.getSymbolTableForContext(contextName)
        return symbolTable?.getAllClasses() ?: emptyList()
    }

    fun onWorkspaceChange(contextName: String) {
        // Invalidate REPL sessions using this context
        sessionManager.invalidateContextSessions(contextName)
    }
}
```

### 2. Type Resolution Integration

```kotlin
class ReplTypeResolver(
    private val typeResolver: GroovyTypeResolver
) {
    suspend fun resolveVariableType(
        sessionId: String,
        variableName: String
    ): ClassNode? {
        val session = sessionManager.getSession(sessionId)
        val binding = GroovyInterop.getBindings(session.engine)
        val value = binding[variableName]

        return value?.let { typeResolver.resolveType(it) }
    }
}
```

### 3. Completion Integration

```kotlin
class ReplCompletionProvider(
    private val completionProvider: CompletionProvider
) {
    suspend fun getContextualCompletions(
        sessionId: String,
        code: String,
        position: Int
    ): List<CompletionItem> {
        val session = sessionManager.getSession(sessionId)
        val bindings = GroovyInterop.getBindings(session.engine)

        // Combine REPL bindings with workspace completions
        val replCompletions = getReplCompletions(bindings, code, position)
        val workspaceCompletions = completionProvider.getCompletions(code, position)

        return replCompletions + workspaceCompletions
    }
}
```

## Concurrency and Threading

### 1. Session Isolation

- Each REPL session runs in its own thread
- Thread-safe session management
- Isolated class loaders per session

### 2. Async Execution

```kotlin
class AsyncReplExecutor(
    private val executorService: ExecutorService
) {
    suspend fun executeAsync(
        engine: Any,
        code: String,
        timeout: Duration
    ): ReplResult = withContext(Dispatchers.IO) {
        val future = executorService.submit {
            GroovyInterop.evaluateCode(engine, code)
        }

        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            ReplResult.timeout()
        }
    }
}
```

### 3. Resource Management

```kotlin
class ReplResourceManager {
    private val memoryMonitor = MemoryMonitor()
    private val sessionLimiter = Semaphore(MAX_CONCURRENT_SESSIONS)

    fun checkResourceLimits(sessionId: String): Boolean {
        return memoryMonitor.isWithinLimits(sessionId) &&
               sessionLimiter.availablePermits() > 0
    }

    fun cleanupSession(sessionId: String) {
        memoryMonitor.cleanup(sessionId)
        sessionLimiter.release()
    }
}
```

## Error Handling Strategy

### 1. Compilation Errors

```groovy
// In GroovyReplEngine
try {
    def result = shell.evaluate(code)
    return ReplResult.success(result)
} catch (MultipleCompilationErrorsException e) {
    return ReplResult.compilationError(
        extractErrorMessages(e)
    )
}
```

### 2. Runtime Errors

```groovy
try {
    def result = shell.evaluate(code)
    return ReplResult.success(result)
} catch (Exception e) {
    return ReplResult.runtimeError(
        message: e.message,
        stackTrace: extractRelevantStackTrace(e),
        type: e.class.name
    )
}
```

### 3. Timeout Handling

```kotlin
class TimeoutHandler {
    suspend fun executeWithTimeout(
        operation: suspend () -> ReplResult,
        timeout: Duration
    ): ReplResult = try {
        withTimeout(timeout.toMillis()) {
            operation()
        }
    } catch (e: TimeoutCancellationException) {
        ReplResult.timeout("Execution timed out after ${timeout.seconds}s")
    }
}
```

## Performance Considerations

### 1. Lazy Loading

- Classes loaded on first access
- Imports resolved on demand
- Completion candidates cached

### 2. Memory Management

- Weak references for large objects
- Periodic garbage collection
- Session memory limits

### 3. Caching Strategy

```kotlin
class ReplCache {
    private val compilationCache = LRUCache<String, CompiledScript>(100)
    private val completionCache = LRUCache<String, List<CompletionItem>>(50)
    private val typeCache = LRUCache<String, ClassNode>(200)

    fun getCachedCompilation(code: String): CompiledScript?
    fun cacheCompilation(code: String, compiled: CompiledScript)

    fun invalidateContextCache(contextName: String)
}
```

## Security Considerations

### 1. Sandboxing (Optional)

```groovy
class ReplSecurityManager extends SecurityManager {
    private final Set<String> allowedPackages

    @Override
    void checkPermission(Permission perm) {
        if (isRestrictedOperation(perm)) {
            throw new SecurityException("Operation not allowed in REPL")
        }
    }

    private boolean isRestrictedOperation(Permission perm) {
        return perm instanceof FilePermission ||
               perm instanceof SocketPermission ||
               perm instanceof RuntimePermission &&
               perm.name.startsWith("exitVM")
    }
}
```

### 2. Resource Limits

```kotlin
class ResourceLimiter {
    fun enforceMemoryLimit(sessionId: String, limit: Long)
    fun enforceTimeLimit(sessionId: String, limit: Duration)
    fun enforceThreadLimit(sessionId: String, limit: Int)
}
```

This architecture ensures clean separation of concerns while leveraging the strengths of both Kotlin and Groovy for
their respective domains.
