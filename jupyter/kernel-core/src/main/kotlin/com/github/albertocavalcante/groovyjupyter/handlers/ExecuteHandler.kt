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
        val silent = request.content["silent"] as? Boolean ?: false
        if (!silent) {
            executionCount++
        }

        // 1. Publish busy status
        statusPublisher.publishBusy(request)

        try {
            // 2. Publish execute_input
            val code = (request.content["code"] as? String).orEmpty()
            if (code.isNotEmpty() && !silent) {
                val inputParams = mapOf(
                    "code" to code,
                    "execution_count" to executionCount,
                )
                val inputMsg = request.createReply(MessageType.EXECUTE_INPUT).apply {
                    content = inputParams
                }
                // Send to IOPub
                connection.sendIOPubMessage(inputMsg)
            }

            // 3. Execute the code
            val result = execute(request)

            // 4. Publish stream output (stdout)
            if (result.stdout.isNotEmpty()) {
                val streamContent = mapOf(
                    "name" to "stdout",
                    "text" to result.stdout,
                )
                val streamMsg = request.createReply(MessageType.STREAM).apply {
                    content = streamContent
                }
                connection.sendIOPubMessage(streamMsg)
            }

            // 5. Publish stream output (stderr)
            if (result.stderr.isNotEmpty()) {
                val streamContent = mapOf(
                    "name" to "stderr",
                    "text" to result.stderr,
                )
                val streamMsg = request.createReply(MessageType.STREAM).apply {
                    content = streamContent
                }
                connection.sendIOPubMessage(streamMsg)
            }

            // 6. Publish result or error
            if (result.status == ExecuteStatus.OK) {
                if (result.result != null && result.result.toString().isNotEmpty() && !silent) {
                    val resultContent = mapOf(
                        "execution_count" to executionCount,
                        "data" to mapOf("text/plain" to result.result.toString()),
                        "metadata" to emptyMap<String, Any>(),
                    )
                    val resultMsg = request.createReply(MessageType.EXECUTE_RESULT).apply {
                        content = resultContent
                    }
                    connection.sendIOPubMessage(resultMsg)
                }

                // 7. Send execute_reply (OK)
                val replyContent = mapOf(
                    "status" to "ok",
                    "execution_count" to executionCount,
                    "user_expressions" to emptyMap<String, Any>(),
                    "payload" to emptyList<Any>(),
                )
                val replyMsg = request.createReply(MessageType.EXECUTE_REPLY).apply {
                    content = replyContent
                }
                connection.sendMessage(replyMsg) // Send to Shell
            } else {
                // Publish error to IOPub
                val errorContent = mapOf(
                    "ename" to (result.errorName ?: "Error"),
                    "evalue" to (result.errorValue ?: ""),
                    "traceback" to (result.traceback ?: emptyList<String>()),
                )
                val errorMsg = request.createReply(MessageType.ERROR).apply {
                    content = errorContent
                }
                connection.sendIOPubMessage(errorMsg)

                // Send execute_reply (Error) to Shell
                val replyContent = mapOf(
                    "status" to "error",
                    "execution_count" to executionCount,
                    "ename" to (result.errorName ?: "Error"),
                    "evalue" to (result.errorValue ?: ""),
                    "traceback" to (result.traceback ?: emptyList<String>()),
                )
                val replyMsg = request.createReply(MessageType.EXECUTE_REPLY).apply {
                    content = replyContent
                }
                connection.sendMessage(replyMsg)
            }
        } catch (e: Exception) {
            logger.error("Error connecting execution", e)
            // Fallback error reply
            val replyContent = mapOf(
                "status" to "error",
                "execution_count" to executionCount,
                "ename" to "KernelError",
                "evalue" to (e.message ?: "Unknown error"),
                "traceback" to emptyList<String>(),
            )
            val replyMsg = request.createReply(MessageType.EXECUTE_REPLY).apply {
                content = replyContent
            }
            connection.sendMessage(replyMsg)
        } finally {
            // 8. Publish idle status
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

        if (code.isBlank()) {
            return ExecuteResult(status = ExecuteStatus.OK)
        }

        // Executor returns ExecuteResult directly
        return executor.execute(code)
    }
}
