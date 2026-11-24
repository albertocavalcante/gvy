// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.utils

import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

private val logger = KotlinLogging.logger {}

/**
 * An `InternalError` response for a request that failed while [operation] ("finding references"): the message
 * names the operation and the cause, and carries no `data`, so the client never receives a Java stack trace.
 */
internal fun internalError(operation: String, cause: Throwable): ResponseErrorException {
    val reason = cause.message ?: cause::class.simpleName
    return ResponseErrorException(
        ResponseError(ResponseErrorCode.InternalError, "Internal error while $operation: $reason", null),
    )
}

/**
 * Runs a request that must answer or fail, never answer wrongly: any failure becomes [internalError]. A
 * [ResponseErrorException] (for example ContentModified) passes through unchanged, and cancellation and
 * [Error]s propagate, as with [runSuspendCatching].
 */
internal suspend fun <T> failingAsInternalError(operation: String, block: suspend () -> T): T =
    runSuspendCatching { block() }.getOrElse { e ->
        if (e is ResponseErrorException) throw e
        logger.error(e) { "Failed while $operation" }
        throw internalError(operation, e)
    }
