package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.kernel.core.KernelExecutor
import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.zmq.JupyterConnection
import org.slf4j.LoggerFactory

/**
 * Handles execute_request messages - the core code execution handler.
 *
 * This is the main handler for running Groovy code. On execute_request:
 * 1. Extract code from request
 * 2. Execute code via KernelExecutor
 * 3. Capture stdout/stderr
 * 4. Return result or error
 * 5. Track execution count
 */
class ExecuteHandler(
    private val executor: KernelExecutor,
    private val statusPublisherFactory: (JupyterConnection) -> StatusPublisher = { conn ->
        StatusPublisher(conn.iopubSocket, conn.signer)
    },
) : MessageHandler {
    private val logger = LoggerFactory.getLogger(ExecuteHandler::class.java)

    var executionCount: Int = 0
        private set

    override fun canHandle(msgType: MessageType): Boolean = msgType == MessageType.EXECUTE_REQUEST

    override fun handle(request: JupyterMessage, connection: JupyterConnection) {
        logger.info("Handling execute_request")

        val statusPublisher = statusPublisherFactory(connection)
        val silent = request.isSilent()
        if (!silent) executionCount++

        statusPublisher.publishBusy(request)

        runCatching {
            handleExecuteRequest(request, connection, silent)
        }.onFailure { throwable ->
            if (throwable is Error) throw throwable
            logger.error("Error handling execute_request", throwable)
            sendKernelErrorReply(request, connection, throwable)
        }

        statusPublisher.publishIdle(request)

        logger.info("Completed execute_request (execution_count={})", executionCount)
    }

    private fun handleExecuteRequest(request: JupyterMessage, connection: JupyterConnection, silent: Boolean) {
        val code = request.code()
        publishExecuteInput(request, connection, code, silent)

        val result = executeCode(code)
        publishStreams(request, connection, result)

        when (result.status) {
            ExecuteStatus.OK -> {
                publishExecuteResult(request, connection, result, silent)
                sendOkReply(request, connection)
            }

            ExecuteStatus.ERROR -> {
                publishError(request, connection, result)
                sendErrorReply(request, connection, result)
            }
        }
    }

    private fun publishExecuteInput(
        request: JupyterMessage,
        connection: JupyterConnection,
        code: String,
        silent: Boolean,
    ) {
        if (code.isEmpty() || silent) return

        val inputParams = mapOf(
            "code" to code,
            "execution_count" to executionCount,
        )
        val inputMsg = request.createReply(MessageType.EXECUTE_INPUT).apply {
            content = inputParams
        }
        connection.sendIOPubMessage(inputMsg)
    }

    private fun publishStreams(request: JupyterMessage, connection: JupyterConnection, result: ExecuteResult) {
        if (result.stdout.isNotEmpty()) {
            publishStream(request, connection, name = "stdout", text = result.stdout)
        }
        if (result.stderr.isNotEmpty()) {
            publishStream(request, connection, name = "stderr", text = result.stderr)
        }
    }

    private fun publishStream(request: JupyterMessage, connection: JupyterConnection, name: String, text: String) {
        val streamContent = mapOf(
            "name" to name,
            "text" to text,
        )
        val streamMsg = request.createReply(MessageType.STREAM).apply {
            content = streamContent
        }
        connection.sendIOPubMessage(streamMsg)
    }

    private fun publishExecuteResult(
        request: JupyterMessage,
        connection: JupyterConnection,
        result: ExecuteResult,
        silent: Boolean,
    ) {
        val displayValue = result.result?.toString().orEmpty()
        if (displayValue.isEmpty() || silent) return

        val resultContent = mapOf(
            "execution_count" to executionCount,
            "data" to mapOf("text/plain" to displayValue),
            "metadata" to emptyMap<String, Any>(),
        )
        val resultMsg = request.createReply(MessageType.EXECUTE_RESULT).apply {
            content = resultContent
        }
        connection.sendIOPubMessage(resultMsg)
    }

    private fun publishError(request: JupyterMessage, connection: JupyterConnection, result: ExecuteResult) {
        val errorContent = mapOf(
            "ename" to (result.errorName ?: "Error"),
            "evalue" to (result.errorValue ?: ""),
            "traceback" to result.traceback,
        )
        val errorMsg = request.createReply(MessageType.ERROR).apply {
            content = errorContent
        }
        connection.sendIOPubMessage(errorMsg)
    }

    private fun sendOkReply(request: JupyterMessage, connection: JupyterConnection) {
        val replyContent = mapOf(
            "status" to "ok",
            "execution_count" to executionCount,
            "user_expressions" to emptyMap<String, Any>(),
            "payload" to emptyList<Any>(),
        )
        val replyMsg = request.createReply(MessageType.EXECUTE_REPLY).apply {
            content = replyContent
        }
        connection.sendMessage(replyMsg)
    }

    private fun sendErrorReply(request: JupyterMessage, connection: JupyterConnection, result: ExecuteResult) {
        val replyContent = mapOf(
            "status" to "error",
            "execution_count" to executionCount,
            "ename" to (result.errorName ?: "Error"),
            "evalue" to (result.errorValue ?: ""),
            "traceback" to result.traceback,
        )
        val replyMsg = request.createReply(MessageType.EXECUTE_REPLY).apply {
            content = replyContent
        }
        connection.sendMessage(replyMsg)
    }

    private fun sendKernelErrorReply(request: JupyterMessage, connection: JupyterConnection, throwable: Throwable) {
        val replyContent = mapOf(
            "status" to "error",
            "execution_count" to executionCount,
            "ename" to "KernelError",
            "evalue" to (throwable.message ?: "Unknown error"),
            "traceback" to emptyList<String>(),
        )
        val replyMsg = request.createReply(MessageType.EXECUTE_REPLY).apply {
            content = replyContent
        }
        connection.sendMessage(replyMsg)
    }

    private fun JupyterMessage.isSilent(): Boolean = content["silent"] as? Boolean ?: false

    private fun JupyterMessage.code(): String = (content["code"] as? String).orEmpty()

    private fun executeCode(code: String): ExecuteResult = if (code.isBlank()) {
        ExecuteResult(status = ExecuteStatus.OK)
    } else {
        executor.execute(code)
    }

    /**
     * Execute code from the request and return the result.
     *
     * This adapts the GroovyExecutor's ExecutionResult to our ExecuteResult.
     */
    fun execute(request: JupyterMessage): ExecuteResult = executeCode(request.code())
}
