# Groovy REPL Test Plan

## Overview

This document outlines the comprehensive testing strategy for the Groovy REPL implementation, covering unit tests,
integration tests, performance tests, and end-to-end scenarios.

## Test Categories

### 1. Unit Tests

#### GroovyReplEngine Tests

**Location**: `src/test/groovy/com/github/albertocavalcante/groovylsp/repl/GroovyReplEngineTest.groovy`

```groovy
class GroovyReplEngineTest extends Specification {

    def "should evaluate simple expressions"() {
        given:
        def engine = new GroovyReplEngine([], [])

        when:
        def result = engine.evaluate("2 + 3")

        then:
        result.success
        result.value == 5
        result.type == "java.lang.Integer"
    }

    def "should maintain variable state across evaluations"() {
        given:
        def engine = new GroovyReplEngine([], [])

        when:
        engine.evaluate("def x = 42")
        def result = engine.evaluate("x * 2")

        then:
        result.success
        result.value == 84
    }

    def "should handle compilation errors gracefully"() {
        given:
        def engine = new GroovyReplEngine([], [])

        when:
        def result = engine.evaluate("def x = [")

        then:
        !result.success
        result.diagnostics.size() > 0
        result.diagnostics[0].severity == DiagnosticSeverity.Error
    }

    def "should handle runtime exceptions"() {
        given:
        def engine = new GroovyReplEngine([], [])

        when:
        def result = engine.evaluate("1 / 0")

        then:
        !result.success
        result.exception
        result.exception.class == ArithmeticException
    }

    def "should provide variable completions"() {
        given:
        def engine = new GroovyReplEngine([], [])
        engine.evaluate("def myVariable = 'hello'")

        when:
        def completions = engine.getCompletionCandidates("myV")

        then:
        completions.contains("myVariable")
    }

    def "should support method completions on objects"() {
        given:
        def engine = new GroovyReplEngine([], [])
        engine.evaluate("def str = 'hello'")

        when:
        def completions = engine.getCompletionCandidates("str.to")

        then:
        completions.any { it.startsWith("toString") }
        completions.any { it.startsWith("toUpperCase") }
    }

    def "should handle imports correctly"() {
        given:
        def engine = new GroovyReplEngine([], ["java.util.*"])

        when:
        def result = engine.evaluate("new ArrayList()")

        then:
        result.success
        result.type == "java.util.ArrayList"
    }

    def "should reset state correctly"() {
        given:
        def engine = new GroovyReplEngine([], [])
        engine.evaluate("def x = 42")

        when:
        engine.resetState()
        def result = engine.evaluate("x")

        then:
        !result.success
        result.diagnostics.any { it.message.contains("x") }
    }

    def "should handle closures and functional programming"() {
        given:
        def engine = new GroovyReplEngine([], [])

        when:
        def result = engine.evaluate("[1, 2, 3].collect { it * 2 }")

        then:
        result.success
        result.value == [2, 4, 6]
    }

    def "should capture output from print statements"() {
        given:
        def engine = new GroovyReplEngine([], [])

        when:
        def result = engine.evaluate("println 'Hello, World!'")

        then:
        result.success
        result.output.contains("Hello, World!")
    }
}
```

#### ReplSessionManager Tests

**Location**: `src/test/kotlin/com/github/albertocavalcante/groovylsp/repl/ReplSessionManagerTest.kt`

```kotlin
class ReplSessionManagerTest {

    @Test
    fun `should create session with unique ID`() = runTest {
        val manager = createSessionManager()

        val session = manager.createSession(CreateSessionParams())

        assertThat(session.id).isNotEmpty()
        assertThat(manager.getSession(session.id)).isEqualTo(session)
    }

    @Test
    fun `should use provided session ID`() = runTest {
        val manager = createSessionManager()
        val customId = "custom-session-id"

        val session = manager.createSession(CreateSessionParams(sessionId = customId))

        assertThat(session.id).isEqualTo(customId)
    }

    @Test
    fun `should throw when session not found`() = runTest {
        val manager = createSessionManager()

        assertThrows<IllegalArgumentException> {
            manager.getSession("non-existent-id")
        }
    }

    @Test
    fun `should cleanup expired sessions`() = runTest {
        val manager = createSessionManager(sessionTimeout = Duration.ofMillis(100))

        val session = manager.createSession(CreateSessionParams())
        delay(200)
        manager.cleanupExpiredSessions()

        assertThrows<IllegalArgumentException> {
            manager.getSession(session.id)
        }
    }

    @Test
    fun `should limit concurrent sessions`() = runTest {
        val manager = createSessionManager(maxSessions = 2)

        manager.createSession(CreateSessionParams())
        manager.createSession(CreateSessionParams())

        assertThrows<IllegalStateException> {
            manager.createSession(CreateSessionParams())
        }
    }

    @Test
    fun `should evaluate code in session context`() = runTest {
        val manager = createSessionManager()
        val session = manager.createSession(CreateSessionParams())

        val result1 = manager.evaluateCode(session.id, "def x = 42")
        val result2 = manager.evaluateCode(session.id, "x * 2")

        assertThat(result1.success).isTrue()
        assertThat(result2.success).isTrue()
        assertThat(result2.value).isEqualTo(84)
    }

    private fun createSessionManager(
        maxSessions: Int = 10,
        sessionTimeout: Duration = Duration.ofMinutes(30)
    ): ReplSessionManager {
        val mockWorkspaceService = mockk<WorkspaceCompilationService>()
        every { mockWorkspaceService.getDependencyClasspath() } returns emptyList()

        return ReplSessionManager(
            workspaceService = mockWorkspaceService,
            coroutineScope = TestScope(),
            maxSessions = maxSessions,
            sessionTimeout = sessionTimeout
        )
    }
}
```

### 2. Integration Tests

#### Workspace Integration Tests

**Location**: `src/test/kotlin/com/github/albertocavalcante/groovylsp/repl/ReplWorkspaceIntegrationTest.kt`

```kotlin
class ReplWorkspaceIntegrationTest {

    @Test
    fun `should access workspace classes in REPL`() = runTest {
        // Setup workspace with test classes
        val workspaceRoot = createTempWorkspace()
        writeTestClass(workspaceRoot, "TestClass", "def getMessage() { 'Hello from workspace' }")

        val workspaceService = WorkspaceCompilationService(TestScope())
        workspaceService.initializeWorkspace(workspaceRoot)

        val sessionManager = ReplSessionManager(workspaceService, TestScope())
        val session = sessionManager.createSession(CreateSessionParams(contextName = "main"))

        // Should be able to use workspace class
        val result = sessionManager.evaluateCode(session.id, "new TestClass().getMessage()")

        assertThat(result.success).isTrue()
        assertThat(result.value).isEqualTo("Hello from workspace")
    }

    @Test
    fun `should access workspace dependencies in REPL`() = runTest {
        val workspaceRoot = createTempWorkspaceWithDependencies()

        val workspaceService = WorkspaceCompilationService(TestScope())
        workspaceService.initializeWorkspace(workspaceRoot)

        val sessionManager = ReplSessionManager(workspaceService, TestScope())
        val session = sessionManager.createSession(CreateSessionParams())

        // Should be able to use dependency classes
        val result = sessionManager.evaluateCode(session.id, "new org.apache.commons.lang3.StringUtils()")

        assertThat(result.success).isTrue()
    }

    @Test
    fun `should switch between contexts`() = runTest {
        val workspaceRoot = createTempWorkspaceWithTestContext()

        val workspaceService = WorkspaceCompilationService(TestScope())
        workspaceService.initializeWorkspace(workspaceRoot)

        val sessionManager = ReplSessionManager(workspaceService, TestScope())
        val session = sessionManager.createSession(CreateSessionParams(contextName = "main"))

        // Switch to test context
        val switchResult = sessionManager.switchContext(session.id, "test")

        assertThat(switchResult.success).isTrue()
        assertThat(switchResult.newContext).isEqualTo("test")
    }
}
```

#### LSP Protocol Tests

**Location**: `src/test/kotlin/com/github/albertocavalcante/groovylsp/repl/ReplProtocolTest.kt`

```kotlin
class ReplProtocolTest {

    @Test
    fun `should handle create session command`() = runTest {
        val handler = createReplCommandHandler()
        val params = CreateSessionParams(
            contextName = "main",
            imports = listOf("java.util.*")
        )

        val result = handler.createSession(params).await()

        assertThat(result.sessionId).isNotEmpty()
        assertThat(result.contextName).isEqualTo("main")
    }

    @Test
    fun `should handle evaluate command`() = runTest {
        val handler = createReplCommandHandler()
        val session = handler.createSession(CreateSessionParams()).await()

        val result = handler.evaluateCode(
            EvaluateParams(
                sessionId = session.sessionId,
                code = "2 + 3",
                includeBindings = true
            )
        ).await()

        assertThat(result.success).isTrue()
        assertThat(result.value).isEqualTo(5)
    }

    @Test
    fun `should handle completion command`() = runTest {
        val handler = createReplCommandHandler()
        val session = handler.createSession(CreateSessionParams()).await()

        // Set up variable
        handler.evaluateCode(
            EvaluateParams(session.sessionId, "def myVar = 'hello'")
        ).await()

        val result = handler.getCompletions(
            ReplCompletionParams(
                sessionId = session.sessionId,
                code = "myV",
                position = 3
            )
        ).await()

        assertThat(result.bindingCompletions).isNotEmpty()
        assertThat(result.bindingCompletions.any { it.name == "myVar" }).isTrue()
    }

    private fun createReplCommandHandler(): ReplCommandHandler {
        val mockWorkspaceService = mockk<WorkspaceCompilationService>()
        val sessionManager = ReplSessionManager(mockWorkspaceService, TestScope())
        val historyManager = ReplHistoryManager()

        return ReplCommandHandler(sessionManager, historyManager)
    }
}
```

### 3. Performance Tests

#### Load Testing

**Location**: `src/test/kotlin/com/github/albertocavalcante/groovylsp/repl/ReplPerformanceTest.kt`

```kotlin
class ReplPerformanceTest {

    @Test
    fun `should handle multiple concurrent sessions`() = runTest {
        val sessionManager = createSessionManager()
        val sessionCount = 10
        val evaluationsPerSession = 100

        val startTime = System.currentTimeMillis()

        // Create multiple sessions concurrently
        val sessions = (1..sessionCount).map {
            async { sessionManager.createSession(CreateSessionParams()) }
        }.awaitAll()

        // Run evaluations concurrently
        val results = sessions.map { session ->
            async {
                repeat(evaluationsPerSession) { i ->
                    sessionManager.evaluateCode(session.id, "def x$i = $i * 2")
                }
            }
        }.awaitAll()

        val duration = System.currentTimeMillis() - startTime

        // Should complete within reasonable time
        assertThat(duration).isLessThan(10000) // 10 seconds
        assertThat(sessions).hasSize(sessionCount)
    }

    @Test
    fun `should handle large evaluation results`() = runTest {
        val sessionManager = createSessionManager()
        val session = sessionManager.createSession(CreateSessionParams())

        val startTime = System.currentTimeMillis()

        // Create large list
        val result = sessionManager.evaluateCode(
            session.id,
            "(1..10000).collect { it * it }"
        )

        val duration = System.currentTimeMillis() - startTime

        assertThat(result.success).isTrue()
        assertThat(duration).isLessThan(5000) // 5 seconds
    }

    @Test
    fun `should handle memory pressure gracefully`() = runTest {
        val sessionManager = createSessionManager()
        val session = sessionManager.createSession(CreateSessionParams())

        // Create memory-intensive objects
        repeat(100) { i ->
            val result = sessionManager.evaluateCode(
                session.id,
                "def largeList$i = (1..1000).collect { 'data' * 100 }"
            )
            assertThat(result.success).isTrue()
        }

        // System should still be responsive
        val finalResult = sessionManager.evaluateCode(session.id, "2 + 2")
        assertThat(finalResult.success).isTrue()
        assertThat(finalResult.value).isEqualTo(4)
    }
}
```

#### Memory Tests

```kotlin
class ReplMemoryTest {

    @Test
    fun `should not leak memory with session cleanup`() {
        val initialMemory = getUsedMemory()

        // Create and destroy many sessions
        repeat(100) {
            val sessionManager = createSessionManager()
            val session = sessionManager.createSession(CreateSessionParams())
            sessionManager.evaluateCode(session.id, "def x = (1..1000).toList()")
            sessionManager.destroySession(session.id)
        }

        System.gc()
        Thread.sleep(1000) // Allow GC to complete

        val finalMemory = getUsedMemory()
        val memoryIncrease = finalMemory - initialMemory

        // Memory increase should be minimal
        assertThat(memoryIncrease).isLessThan(50 * 1024 * 1024) // 50MB
    }

    private fun getUsedMemory(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}
```

### 4. End-to-End Tests

#### REPL Workflow Tests

**Location**: `src/test/kotlin/com/github/albertocavalcante/groovylsp/repl/ReplWorkflowTest.kt`

```kotlin
class ReplWorkflowTest {

    @Test
    fun `complete REPL session workflow`() = runTest {
        val handler = createReplCommandHandler()

        // 1. Create session
        val session = handler.createSession(
            CreateSessionParams(
                contextName = "main",
                imports = listOf("java.util.*", "groovy.transform.*")
            )
        ).await()

        assertThat(session.sessionId).isNotEmpty()

        // 2. Evaluate simple expression
        val result1 = handler.evaluateCode(
            EvaluateParams(session.sessionId, "def x = 42")
        ).await()

        assertThat(result1.success).isTrue()

        // 3. Use previous variable
        val result2 = handler.evaluateCode(
            EvaluateParams(session.sessionId, "x * 2")
        ).await()

        assertThat(result2.success).isTrue()
        assertThat(result2.value).isEqualTo(84)

        // 4. Get completions
        val completions = handler.getCompletions(
            ReplCompletionParams(session.sessionId, "x.to", 4)
        ).await()

        assertThat(completions.completions).isNotEmpty()

        // 5. Inspect variable
        val inspection = handler.inspectVariable(
            InspectParams(session.sessionId, "x")
        ).await()

        assertThat(inspection.variable.name).isEqualTo("x")
        assertThat(inspection.variable.type).isEqualTo("java.lang.Integer")

        // 6. Get history
        val history = handler.getHistory(
            HistoryParams(session.sessionId, limit = 10)
        ).await()

        assertThat(history.entries).hasSize(2)

        // 7. Reset session
        handler.resetSession(ResetParams(session.sessionId)).await()

        // 8. Variable should be gone
        val result3 = handler.evaluateCode(
            EvaluateParams(session.sessionId, "x")
        ).await()

        assertThat(result3.success).isFalse()

        // 9. Destroy session
        handler.destroySession(DestroyParams(session.sessionId)).await()
    }

    @Test
    fun `error handling workflow`() = runTest {
        val handler = createReplCommandHandler()
        val session = handler.createSession(CreateSessionParams()).await()

        // Compilation error
        val compileError = handler.evaluateCode(
            EvaluateParams(session.sessionId, "def x = [")
        ).await()

        assertThat(compileError.success).isFalse()
        assertThat(compileError.diagnostics).isNotEmpty()

        // Runtime error
        val runtimeError = handler.evaluateCode(
            EvaluateParams(session.sessionId, "1 / 0")
        ).await()

        assertThat(runtimeError.success).isFalse()
        assertThat(runtimeError.exception).isNotNull()

        // Session should still be functional
        val recovery = handler.evaluateCode(
            EvaluateParams(session.sessionId, "2 + 2")
        ).await()

        assertThat(recovery.success).isTrue()
        assertThat(recovery.value).isEqualTo(4)
    }
}
```

### 5. Security Tests

#### Sandboxing Tests

**Location**: `src/test/kotlin/com/github/albertocavalcante/groovylsp/repl/ReplSecurityTest.kt`

```kotlin
class ReplSecurityTest {

    @Test
    fun `should prevent file system access when sandboxed`() = runTest {
        val sessionManager = createSessionManager(sandboxing = true)
        val session = sessionManager.createSession(
            CreateSessionParams(
                configuration = SessionConfiguration(sandboxing = true)
            )
        )

        val result = sessionManager.evaluateCode(
            session.id,
            "new File('/etc/passwd').exists()"
        )

        assertThat(result.success).isFalse()
        assertThat(result.exception?.message).contains("SecurityException")
    }

    @Test
    fun `should prevent system exit when sandboxed`() = runTest {
        val sessionManager = createSessionManager(sandboxing = true)
        val session = sessionManager.createSession(
            CreateSessionParams(
                configuration = SessionConfiguration(sandboxing = true)
            )
        )

        val result = sessionManager.evaluateCode(
            session.id,
            "System.exit(1)"
        )

        assertThat(result.success).isFalse()
        assertThat(result.exception?.message).contains("SecurityException")
    }

    @Test
    fun `should enforce execution timeout`() = runTest {
        val sessionManager = createSessionManager()
        val session = sessionManager.createSession(
            CreateSessionParams(
                configuration = SessionConfiguration(executionTimeout = 1000) // 1 second
            )
        )

        val result = sessionManager.evaluateCode(
            session.id,
            "Thread.sleep(5000)" // 5 seconds
        )

        assertThat(result.success).isFalse()
        assertThat(result.exception?.message).contains("timeout")
    }
}
```

## Test Data and Fixtures

### Test Workspace Structure

```
test-workspace/
├── src/
│   ├── main/
│   │   └── groovy/
│   │       ├── TestClass.groovy
│   │       └── com/example/
│   │           └── Utils.groovy
│   └── test/
│       └── groovy/
│           └── TestClassTest.groovy
├── build.gradle
└── gradle.properties
```

### Test Classes

```groovy
// TestClass.groovy
class TestClass {
    String name

    def getMessage() {
        "Hello from ${name ?: 'TestClass'}"
    }

    def calculate(x, y) {
        x + y
    }
}

// Utils.groovy
package com.example

class Utils {
    static def formatNumber(number) {
        "Number: $number"
    }

    static def createList(size) {
        (1..size).toList()
    }
}
```

## Continuous Integration

### Test Execution Pipeline

```yaml
# .github/workflows/repl-tests.yml
name: REPL Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK
        uses: actions/setup-java@v3
        with:
          java-version: "17"

      - name: Run unit tests
        run: ./gradlew test --tests "*ReplTest*"

      - name: Run integration tests
        run: ./gradlew integrationTest

      - name: Run performance tests
        run: ./gradlew performanceTest

      - name: Generate test report
        run: ./gradlew jacocoTestReport

      - name: Upload coverage
        uses: codecov/codecov-action@v3
```

## Test Coverage Targets

- **Unit Tests**: 90% line coverage
- **Integration Tests**: 80% scenario coverage
- **Performance Tests**: All critical paths under load
- **Security Tests**: All sandboxing features
- **Protocol Tests**: All LSP commands and error scenarios

## Manual Testing Scenarios

### 1. Interactive Development

- Create variables and use them across evaluations
- Test method chaining and fluent interfaces
- Verify completion works in complex expressions

### 2. Error Recovery

- Introduce syntax errors and verify recovery
- Test runtime exceptions and session stability
- Verify error messages are clear and helpful

### 3. Performance

- Long-running evaluations
- Large data structures
- Memory usage patterns

### 4. Client Integration

- VS Code terminal experience
- IntelliJ console behavior
- Completion and inspection features

This comprehensive test plan ensures the REPL implementation is robust, performant, and user-friendly across all
supported scenarios.
