package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.kernel.core.KernelExecutor
import com.github.albertocavalcante.groovyjupyter.protocol.Header
import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.zmq.JupyterConnection
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests for the side effects of ExecuteHandler (sending messages).
 */
class ExecuteHandlerSideEffectsTest {

    private lateinit var handler: ExecuteHandler
    private lateinit var connection: JupyterConnection
    private lateinit var statusPublisher: StatusPublisher
    private lateinit var executor: KernelExecutor

    @BeforeTest
    fun setup() {
        // Setup mocks
        connection = mockk(relaxed = true)
        statusPublisher = mockk(relaxed = true)
        executor = mockk(relaxed = true)

        handler = ExecuteHandler(
            executor = executor,
            statusPublisherFactory = { statusPublisher },
        )
    }

    @Test
    fun `should send execute_input, stream, ExecuteResult and execute_reply on success`() {
        val request = createExecuteRequest("println 'Hello'")

        // Mock executor result
        every { executor.execute(any()) } returns ExecuteResult(
            status = ExecuteStatus.OK,
            result = "null",
            stdout = "Hello\n",
        )

        // Capture messages sent to IOPub and Shell
        val iopubSlot = slot<JupyterMessage>()
        val shellSlot = slot<JupyterMessage>()

        every { connection.sendIOPubMessage(capture(iopubSlot)) } just Runs
        every { connection.sendMessage(capture(shellSlot), any()) } just Runs

        handler.handle(request, connection)

        // Verify execute_input
        verify { connection.sendIOPubMessage(match { it.header.msgType == MessageType.EXECUTE_INPUT.value }) }

        // Verify stream (stdout)
        verify {
            connection.sendIOPubMessage(
                match {
                    it.header.msgType == MessageType.STREAM.value &&
                        (it.content["text"] as? String)?.contains("Hello") == true
                },
            )
        }

        // Verify execute_reply
        verify {
            connection.sendMessage(
                match {
                    it.header.msgType == MessageType.EXECUTE_REPLY.value &&
                        it.content["status"] == "ok"
                },
                any(),
            )
        }
    }

    @Test
    fun `should send stream for stderr`() {
        val request = createExecuteRequest("System.err.println('oops')")

        // Mock executor result
        every { executor.execute(any()) } returns ExecuteResult(
            status = ExecuteStatus.OK,
            stderr = "oops\n",
        )

        handler.handle(request, connection)

        verify {
            connection.sendIOPubMessage(
                match {
                    it.header.msgType == MessageType.STREAM.value &&
                        it.content["name"] == "stderr" &&
                        (it.content["text"] as? String)?.contains("oops") == true
                },
            )
        }
    }

    @Test
    fun `should send error and execute_reply error on exception`() {
        val request = createExecuteRequest("throw new RuntimeException('Boom')")

        // Mock executor result
        every { executor.execute(any()) } returns ExecuteResult(
            status = ExecuteStatus.ERROR,
            errorName = "RuntimeException",
            errorValue = "Boom",
            traceback = listOf("RuntimeException: Boom"),
        )

        handler.handle(request, connection)

        // Verify error message on IOPub
        verify {
            connection.sendIOPubMessage(
                match {
                    it.header.msgType == MessageType.ERROR.value &&
                        it.content["ename"] == "RuntimeException"
                },
            )
        }

        // Verify execute_reply with error status
        verify {
            connection.sendMessage(
                match {
                    it.header.msgType == MessageType.EXECUTE_REPLY.value &&
                        it.content["status"] == "error"
                },
                any(),
            )
        }
    }

    @Test
    fun `should increment execution count on non-silent execution`() {
        val request = createExecuteRequest("1 + 1", silent = false)

        // Mock executor result
        every { executor.execute(any()) } returns ExecuteResult(
            status = ExecuteStatus.OK,
            result = "2",
        )

        // Initial count is 0
        val initialCount = handler.executionCount

        handler.handle(request, connection)

        // Count should increment
        verify { connection.sendMessage(match { it.content["execution_count"] == initialCount + 1 }, any()) }
    }

    @Test
    fun `should not increment execution count on silent execution`() {
        val request = createExecuteRequest("1 + 1", silent = true)

        // Mock executor result
        every { executor.execute(any()) } returns ExecuteResult(
            status = ExecuteStatus.OK,
            result = "2",
        )

        // Initial count is 0
        val initialCount = handler.executionCount

        handler.handle(request, connection)

        // Count should NOT increment
        verify { connection.sendMessage(match { it.content["execution_count"] == initialCount }, any()) }
    }

    @Test
    fun `should not publish execute_input for silent execution`() {
        val request = createExecuteRequest("1 + 1", silent = true)

        // Mock executor result
        every { executor.execute(any()) } returns ExecuteResult(
            status = ExecuteStatus.OK,
            result = "2",
        )

        handler.handle(request, connection)

        // Verify execute_input is NOT sent
        verify(exactly = 0) {
            connection.sendIOPubMessage(match { it.header.msgType == MessageType.EXECUTE_INPUT.value })
        }
    }

    @Test
    fun `should not publish execute_result for silent execution`() {
        val request = createExecuteRequest("1 + 1", silent = true)

        // Mock executor result
        every { executor.execute(any()) } returns ExecuteResult(
            status = ExecuteStatus.OK,
            result = "2",
        )

        handler.handle(request, connection)

        // Verify execute_result is NOT sent
        verify(exactly = 0) {
            connection.sendIOPubMessage(match { it.header.msgType == MessageType.EXECUTE_RESULT.value })
        }
    }

    @Test
    fun `should use correct execution count in all messages`() {
        val request1 = createExecuteRequest("1 + 1", silent = false)
        val request2 = createExecuteRequest("2 + 2", silent = false)

        // Mock executor result
        every { executor.execute(any()) } returns ExecuteResult(
            status = ExecuteStatus.OK,
            result = "result",
        )

        // First execution
        handler.handle(request1, connection)

        // Verify first execution used count 1
        verify {
            connection.sendIOPubMessage(
                match {
                    it.header.msgType == MessageType.EXECUTE_INPUT.value &&
                        it.content["execution_count"] == 1
                },
            )
        }

        // Second execution
        handler.handle(request2, connection)

        // Verify second execution used count 2
        verify {
            connection.sendIOPubMessage(
                match {
                    it.header.msgType == MessageType.EXECUTE_INPUT.value &&
                        it.content["execution_count"] == 2
                },
            )
        }
    }

    // --- Helpers ---

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
        ),
    )
}
