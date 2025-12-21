package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import com.github.albertocavalcante.groovyjupyter.zmq.JupyterConnection
import com.github.albertocavalcante.groovyrepl.GroovyExecutor
import org.slf4j.LoggerFactory

/**
 * Handles execute_request messages - the core code execution handler.
 *
 * This is the main handler for running Groovy code. On execute_request:
 * 1. Extract code from request
 * 2. Execute code via GroovyExecutor
 * 3. Capture stdout/stderr
 * 4. Return result or error
 * 5. Track execution count
 */
class ExecuteHandler(
    private val executor: GroovyExecutor,
    private val statusPublisherFactory: (JupyterConnection) -> StatusPublisher = { conn ->
        StatusPublisher(conn.iopubSocket, conn.signer)
    },
    private val streamPublisherFactory: (JupyterConnection) -> StreamPublisher = { conn ->
        StreamPublisher(conn.iopubSocket, conn.signer)
    },
) : MessageHandler {
    private val logger = LoggerFactory.getLogger(ExecuteHandler::class.java)

    var executionCount: Int = 0
        private set

    override fun canHandle(msgType: MessageType): Boolean = msgType == MessageType.EXECUTE_REQUEST

    override fun handle(request: JupyterMessage, connection: JupyterConnection, socket: org.zeromq.ZMQ.Socket) {
        logger.info("Handling execute_request")

        val statusPublisher = statusPublisherFactory(connection)
        val streamPublisher = streamPublisherFactory(connection)

        // 1. Publish busy status
        statusPublisher.publishBusy(request)

        try {
            // 2. Execute the code
            val result = execute(request)

            val code = (request.content["code"] as? String).orEmpty()
            val silent = request.content["silent"] as? Boolean ?: false

            // 3. Publish execute_input (IOPub)
            if (!silent && code.isNotBlank()) {
                val inputMsg = request.createReply(MessageType.EXECUTE_INPUT)
                inputMsg.content = mapOf(
                    "code" to code,
                    "execution_count" to executionCount,
                )
                connection.sendMessage(connection.iopubSocket, inputMsg)
            }

            // 4. Publish streams
            if (result.stdout.isNotEmpty()) {
                streamPublisher.publishStdout(result.stdout, request)
            }
            if (result.stderr.isNotEmpty()) {
                streamPublisher.publishStderr(result.stderr, request)
            }

            // 5. Publish execute_result (IOPub)
            if (!silent && result.status == ExecuteStatus.OK && result.result != null) {
                val resultMsg = request.createReply(MessageType.EXECUTE_RESULT)
                resultMsg.content = mapOf(
                    "data" to mapOf("text/plain" to result.result.toString()),
                    "metadata" to emptyMap<String, Any>(),
                    "execution_count" to executionCount,
                )
                connection.sendMessage(connection.iopubSocket, resultMsg)
            }

            // 6. Publish error (IOPub)
            if (result.status == ExecuteStatus.ERROR) {
                val errorMsg = request.createReply(MessageType.ERROR)
                errorMsg.content = mapOf(
                    "ename" to (result.errorName ?: "Error"),
                    "evalue" to result.errorValue.orEmpty(),
                    "traceback" to result.traceback,
                )
                connection.sendMessage(connection.iopubSocket, errorMsg)
            }

            // 7. Send execute_reply on shell socket
            val reply = request.createReply(MessageType.EXECUTE_REPLY)
            val content = mutableMapOf<String, Any>(
                "status" to result.status.name.lowercase(), // "ok" or "error"
                "execution_count" to executionCount,
            )

            if (result.status == ExecuteStatus.ERROR) {
                content["ename"] = result.errorName ?: "Error"
                content["evalue"] = result.errorValue.orEmpty()
                content["traceback"] = result.traceback
            } else {
                content["user_expressions"] = emptyMap<String, Any>()
            }

            reply.content = content
            connection.sendMessage(socket, reply)
        } finally {
            // 5. Publish idle status (always, even on exceptions)
            statusPublisher.publishIdle(request)
        }

        logger.info("Completed execute_request (execution_count={})", executionCount)
    }

    /**
     * Execute code from the request and return the result.
     *
     * This adapts the GroovyExecutor's ExecutionResult to our ExecuteResult.
     */
    fun execute(request: JupyterMessage): ExecuteResult {
        val code = (request.content["code"] as? String).orEmpty()
        val silent = request.content["silent"] as? Boolean ?: false

        if (!silent) {
            executionCount++
        }

        if (code.isBlank()) {
            return ExecuteResult(status = ExecuteStatus.OK)
        }

        // Use GroovyExecutor which already captures stdout/stderr
        val executionResult = executor.execute(code)

        return if (executionResult.isSuccess) {
            ExecuteResult(
                status = ExecuteStatus.OK,
                result = executionResult.value,
                stdout = executionResult.stdout,
                stderr = executionResult.stderr,
            )
        } else {
            ExecuteResult(
                status = ExecuteStatus.ERROR,
                stdout = executionResult.stdout,
                stderr = executionResult.stderr,
                errorName = executionResult.errorType,
                errorValue = executionResult.errorMessage.orEmpty(),
                traceback = executionResult.stackTrace,
            )
        }
    }
}
