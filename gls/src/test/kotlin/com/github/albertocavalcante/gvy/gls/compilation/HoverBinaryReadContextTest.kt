// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.services.bytecodeOnlyClassLoader
import com.github.albertocavalcante.nativeapi.ParseRequest
import groovy.lang.GroovyResourceLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HoverBinaryReadContextTest {
    @Test
    fun `a suspended or cancelled binary read leaves ordinary source lookup available on the same thread`(): Unit =
        runBlocking {
            val parser = GroovyParserFacade()
            val request = ParseRequest(URI.create("file:///Client.groovy"), "class Client {}")
            val unit = parser.parse(request).compilationUnit
            unit.classLoader.use { loader ->
                val observed = mutableListOf<String>()
                loader.resourceLoader = GroovyResourceLoader { name ->
                    observed.add(name)
                    null
                }
                val view = bytecodeOnlyClassLoader(loader)
                val entered = CompletableDeferred<Unit>()
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
                    val reader = launch(worker + view.context) {
                        assertFailsWith<ClassNotFoundException> { loader.loadClass("missing.Guarded") }
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    try {
                        withTimeout(5_000) { entered.await() }
                        withContext(worker) {
                            assertFailsWith<ClassNotFoundException> { loader.loadClass("missing.Ordinary") }
                            val other = parser.parse(
                                request.copy(
                                    content = "class Client { Peer peer }",
                                    openSources = mapOf(URI.create("file:///Peer.groovy") to "class Peer {}"),
                                    compilePhase = Phases.CANONICALIZATION,
                                ),
                            )
                            other.compilationUnit.classLoader.use { assertTrue(other.isSuccessful) }
                        }
                    } finally {
                        reader.cancelAndJoin()
                    }
                    withContext(worker) {
                        assertFailsWith<IllegalStateException> {
                            withContext(view.context) {
                                assertFailsWith<ClassNotFoundException> { loader.loadClass("missing.BeforeError") }
                                error("leave scoped traversal")
                            }
                        }
                        assertFailsWith<ClassNotFoundException> { loader.loadClass("missing.AfterError") }
                    }
                    assertEquals(listOf("missing.Ordinary", "missing.AfterError"), observed)
                }
            }
        }
}
