// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.diagnostics

import com.google.gson.JsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI
import java.util.concurrent.CompletableFuture

/**
 * Typed client arguments; the server validates the original JSON before converting values.
 * Numeric version representations are limited to 64 characters before exact signed 32-bit conversion.
 */
data class AwaitDiagnosticsParams(val uri: String? = null, val version: Int? = null)

/** Bridges one cancellable protocol waiter to the independently owned retained analysis. */
internal class DiagnosticsRequestHandler(
    private val scope: CoroutineScope,
    private val awaitCurrent: suspend (URI, Int?) -> DiagnosticsRunResult,
) {
    fun awaitDiagnostics(params: JsonElement?): CompletableFuture<DiagnosticsRunResult> = scope.future {
        if (params == null || !params.isJsonObject) invalidParams("Diagnostics parameters must be an object")
        val request = params.asJsonObject
        val document = request.get("uri")
        if (document == null || !document.isJsonPrimitive || !document.asJsonPrimitive.isString) {
            invalidParams("A document URI string is required")
        }
        val text = document.asString.takeIf { it.isNotBlank() } ?: invalidParams("A document URI is required")
        val uri = try {
            URI.create(text)
        } catch (_: IllegalArgumentException) {
            invalidParams("The document URI is malformed")
        }
        if (!uri.isAbsolute) invalidParams("The document URI must be absolute")

        try {
            awaitCurrent(uri, requestVersion(request.get("version")))
        } catch (unavailable: DiagnosticsRunUnavailableException) {
            throw ResponseErrorException(
                ResponseError(ResponseErrorCode.ContentModified, unavailable.message, null),
            ).apply { initCause(unavailable) }
        }
    }

    private fun requestVersion(value: JsonElement?): Int? {
        if (value == null || value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            invalidParams("The document version must be an integer")
        }
        // Bound coefficient parsing before allocating a BigDecimal from the retained JSON lexeme.
        val literal = value.asString
        if (literal.length > MAX_VERSION_NUMBER_LENGTH) {
            invalidParams("The document version number exceeds 64 characters")
        }
        return try {
            literal.toBigDecimal().intValueExact()
        } catch (_: NumberFormatException) {
            invalidParams("The document version must be a 32-bit integer")
        } catch (_: ArithmeticException) {
            invalidParams("The document version must be a 32-bit integer")
        }
    }

    private fun invalidParams(message: String): Nothing =
        throw ResponseErrorException(ResponseError(ResponseErrorCode.InvalidParams, message, null))

    private companion object {
        const val MAX_VERSION_NUMBER_LENGTH = 64
    }
}
