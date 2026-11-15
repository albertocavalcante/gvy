// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

internal sealed interface ExecutionStep<out T> {
    data class Value<T>(val value: T) : ExecutionStep<T>
    data class Stop(val response: FileExecutionResponse) : ExecutionStep<Nothing>
}

internal inline fun <T, R> ExecutionStep<T>.flatMap(next: (T) -> ExecutionStep<R>): ExecutionStep<R> = when (this) {
    is ExecutionStep.Value -> next(value)
    is ExecutionStep.Stop -> this
}

internal inline fun <T, R> ExecutionStep<T>.map(next: (T) -> R): ExecutionStep<R> =
    flatMap { ExecutionStep.Value(next(it)) }

internal fun ExecutionStep<FileExecutionResponse>.response(): FileExecutionResponse = when (this) {
    is ExecutionStep.Value -> value
    is ExecutionStep.Stop -> response
}

internal fun executionStop(reason: String, code: String? = null) = ExecutionStep.Stop(
    FileExecutionResponse("unavailable", reason = reason, reasonCode = code),
)
