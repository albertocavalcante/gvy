package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.kernel.core.GroovyKernelExecutorAdapter
import com.github.albertocavalcante.groovyjupyter.protocol.Header
import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyrepl.GroovyExecutor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD tests for ExecuteHandler - handles execute_request messages.
 *
 * execute_request is the core message for running code in the kernel.
 * The handler must:
 * 1. Execute Groovy code via KernelExecutor
 * 2. Capture stdout/stderr
 * 3. Return results or error info
 * 4. Track execution count
 */
class ExecuteHandlerTest {

    private lateinit var executor: GroovyKernelExecutorAdapter
    private lateinit var handler: ExecuteHandler

    @BeforeTest
    fun setup() {
        executor = GroovyKernelExecutorAdapter(GroovyExecutor())
        handler = ExecuteHandler(executor)
    }

    @Test
    fun `should handle execute_request message type`() {
        // Then: Should handle execute_request
        assertTrue(handler.canHandle(MessageType.EXECUTE_REQUEST))
    }

    @Test
    fun `should not handle other message types`() {
        // Then: Should not handle other types
        assertFalse(handler.canHandle(MessageType.KERNEL_INFO_REQUEST))
        assertFalse(handler.canHandle(MessageType.SHUTDOWN_REQUEST))
    }

    @Test
    fun `should not increment execution count when calling execute helper directly`() {
        // Given: A handler with initial count 0
        assertEquals(0, handler.executionCount)

        // When: Calling execute() helper directly (not through handle())
        val request = createExecuteRequest("1 + 1")
        handler.execute(request)

        // Then: Count should NOT increment (increment happens in handle(), not execute())
        assertEquals(0, handler.executionCount)

        // When: Executing again
        handler.execute(createExecuteRequest("2 + 2"))

        // Then: Count should still be 0
        assertEquals(0, handler.executionCount)
    }

    @Test
    fun `should execute code and return result`() {
        // Given: A simple expression
        val request = createExecuteRequest("1 + 1")

        // When: Executing
        val result = handler.execute(request)

        // Then: Should return success with result
        assertEquals(ExecuteStatus.OK, result.status)
        assertEquals("2", result.result?.toString())
    }

    @Test
    fun `should capture stdout`() {
        // Given: Code that prints
        val request = createExecuteRequest("println 'Hello, Jupyter!'")

        // When: Executing
        val result = handler.execute(request)

        // Then: Should capture stdout
        assertTrue(result.stdout.contains("Hello, Jupyter!"))
    }

    @Test
    fun `should capture stderr`() {
        // Given: Code that writes to stderr
        val request = createExecuteRequest("System.err.println 'Error message'")

        // When: Executing
        val result = handler.execute(request)

        // Then: Should capture stderr
        assertTrue(result.stderr.contains("Error message"))
    }

    @Test
    fun `should handle execution errors`() {
        // Given: Code with error
        val request = createExecuteRequest("throw new RuntimeException('Test error')")

        // When: Executing
        val result = handler.execute(request)

        // Then: Should return error status
        assertEquals(ExecuteStatus.ERROR, result.status)
        assertEquals("RuntimeException", result.errorName)
        assertTrue(result.errorValue?.contains("Test error") == true)
    }

    @Test
    fun `should not store history when silent is true`() {
        // Given: A silent execution request
        val request = createExecuteRequest("1 + 1", silent = true)

        // When: Executing
        val initialCount = handler.executionCount
        handler.execute(request)

        // Then: Execution count should not increment
        assertEquals(initialCount, handler.executionCount)
    }

    @Test
    fun `should extract code from request content`() {
        // Given: Request with code
        val request = createExecuteRequest("def x = 42; x * 2")

        // When: Executing
        val result = handler.execute(request)

        // Then: Should execute the code
        assertEquals("84", result.result?.toString())
    }

    private fun createExecuteRequest(code: String, silent: Boolean = false): JupyterMessage = JupyterMessage(
        header = Header(
            msgId = "test-msg-id",
            session = "test-session",
            username = "test-user",
            msgType = MessageType.EXECUTE_REQUEST.value,
        ),
        content = mapOf(
            "code" to code,
            "silent" to silent,
            "store_history" to !silent,
            "user_expressions" to emptyMap<String, String>(),
            "allow_stdin" to false,
            "stop_on_error" to true,
        ),
    )
}
