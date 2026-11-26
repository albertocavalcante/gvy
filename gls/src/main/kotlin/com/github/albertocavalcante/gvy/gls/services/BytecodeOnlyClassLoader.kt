// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.nativeapi.NativeBinaryReadScope
import com.github.albertocavalcante.nativeapi.nativeBinaryReadScope
import groovy.lang.GroovyClassLoader
import kotlinx.coroutines.ThreadContextElement
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal fun bytecodeOnlyClassLoader(held: GroovyClassLoader): BytecodeOnlyClassLoader {
    val scope = held.nativeBinaryReadScope() ?: throw ResponseErrorException(
        ResponseError(
            ResponseErrorCode.RequestFailed,
            "Held compiler loader cannot isolate binary metadata reads",
            null,
        ),
    )
    return BytecodeOnlyClassLoader(held, scope)
}

/** The declaring class keeps its real loader. Signature resolution must therefore use the same scoped policy. */
internal class BytecodeOnlyClassLoader(private val held: GroovyClassLoader, private val scope: NativeBinaryReadScope) :
    ClassLoader(null) {
    val context: CoroutineContext = BinaryReadContext(scope)

    fun <T> read(action: () -> T): T = scope.enter().use { action() }

    override fun loadClass(name: String, resolve: Boolean): Class<*> = read {
        held.loadClass(name, false, true, resolve)
    }
}

private class BinaryReadContext(private val scope: NativeBinaryReadScope) :
    AbstractCoroutineContextElement(Key),
    ThreadContextElement<AutoCloseable> {
    private companion object Key : CoroutineContext.Key<BinaryReadContext>

    override fun updateThreadContext(context: CoroutineContext): AutoCloseable = scope.enter()
    override fun restoreThreadContext(context: CoroutineContext, oldState: AutoCloseable) = oldState.close()
}
