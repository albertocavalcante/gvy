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
) : MessageHandler {
    private val logger = LoggerFactory.getLogger(ExecuteHandler::class.java)

    var executionCount: Int = 0
        private set

    override fun canHandle(msgType: MessageType): Boolean = msgType == MessageType.EXECUTE_REQUEST

    override fun handle(request: JupyterMessage, connection: JupyterConnection) {
        logger.info("Handling execute_request")

        val statusPublisher = statusPublisherFactory(connection)

        // 1. Publish busy status
        statusPublisher.publishBusy(request)

        try {
            // 2. Execute the code
            val result = execute(request)

            // TODO: Publish execute_input, stream, execute_result/error
            // TODO: Send execute_reply on shell socket
        } finally {
            // 3. Publish idle status (always, even on exceptions)
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
