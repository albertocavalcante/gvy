package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.execution.GroovyExecutor
import com.github.albertocavalcante.groovyjupyter.protocol.Header
import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * TDD tests for ExecuteHandler - handles execute_request messages.
 *
 * execute_request is the core message for running code in the kernel.
 * The handler must:
 * 1. Execute Groovy code via GroovyExecutor
 * 2. Capture stdout/stderr
 * 3. Return results or error info
 * 4. Track execution count
 */
class ExecuteHandlerTest {

    private lateinit var executor: GroovyExecutor
    private lateinit var handler: ExecuteHandler

    @BeforeEach
    fun setup() {
        executor = GroovyExecutor()
        handler = ExecuteHandler(executor)
    }

    @Test
    fun `should handle execute_request message type`() {
        // Then: Should handle execute_request
        assertThat(handler.canHandle(MessageType.EXECUTE_REQUEST)).isTrue()
    }

    @Test
    fun `should not handle other message types`() {
        // Then: Should not handle other types
        assertThat(handler.canHandle(MessageType.KERNEL_INFO_REQUEST)).isFalse()
        assertThat(handler.canHandle(MessageType.SHUTDOWN_REQUEST)).isFalse()
    }

    @Test
    fun `should increment execution count on each execution`() {
        // Given: A handler with initial count 0
        assertThat(handler.executionCount).isEqualTo(0)

        // When: Executing code
        val request = createExecuteRequest("1 + 1")
        handler.execute(request)

        // Then: Count should increment
        assertThat(handler.executionCount).isEqualTo(1)

        // When: Executing again
        handler.execute(createExecuteRequest("2 + 2"))

        // Then: Count should be 2
        assertThat(handler.executionCount).isEqualTo(2)
    }

    @Test
    fun `should execute code and return result`() {
        // Given: A simple expression
        val request = createExecuteRequest("1 + 1")

        // When: Executing
        val result = handler.execute(request)

        // Then: Should return success with result
        assertThat(result.status).isEqualTo(ExecuteStatus.OK)
        assertThat(result.result?.toString()).isEqualTo("2")
    }

    @Test
    fun `should capture stdout`() {
        // Given: Code that prints
        val request = createExecuteRequest("println 'Hello, Jupyter!'")

        // When: Executing
        val result = handler.execute(request)

        // Then: Should capture stdout
        assertThat(result.stdout).contains("Hello, Jupyter!")
    }

    @Test
    fun `should capture stderr`() {
        // Given: Code that writes to stderr
        val request = createExecuteRequest("System.err.println 'Error message'")

        // When: Executing
        val result = handler.execute(request)

        // Then: Should capture stderr
        assertThat(result.stderr).contains("Error message")
    }

    @Test
    fun `should handle execution errors`() {
        // Given: Code with error
        val request = createExecuteRequest("throw new RuntimeException('Test error')")

        // When: Executing
        val result = handler.execute(request)

        // Then: Should return error status
        assertThat(result.status).isEqualTo(ExecuteStatus.ERROR)
        assertThat(result.errorName).isEqualTo("RuntimeException")
        assertThat(result.errorValue).contains("Test error")
    }

    @Test
    fun `should not store history when silent is true`() {
        // Given: A silent execution request
        val request = createExecuteRequest("1 + 1", silent = true)

        // When: Executing
        val initialCount = handler.executionCount
        handler.execute(request)

        // Then: Execution count should not increment
        assertThat(handler.executionCount).isEqualTo(initialCount)
    }

    @Test
    fun `should extract code from request content`() {
        // Given: Request with code
        val request = createExecuteRequest("def x = 42; x * 2")

        // When: Executing
        val result = handler.execute(request)

        // Then: Should execute the code
        assertThat(result.result?.toString()).isEqualTo("84")
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
