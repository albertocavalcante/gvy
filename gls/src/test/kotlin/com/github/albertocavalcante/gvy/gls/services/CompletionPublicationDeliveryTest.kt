// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.ClassNameQuery
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SignatureCompletionRequest
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompletionPublicationDeliveryTest {
    enum class Input { WORKSPACE_CLASSES, CLASSPATH_NAMES }

    @ParameterizedTest
    @EnumSource(Input::class)
    fun `a lookup that retired after contributing a completion cannot answer`(input: Input): Unit = runBlocking {
        val candidate = URI.create("file:///Widget.groovy")
        val uri = URI.create("file:///Completions.groovy")
        val text = when (input) {
            Input.WORKSPACE_CLASSES -> "import java.util.List\nList<Wid"
            Input.CLASSPATH_NAMES -> "import java.util.ArrayL"
        }
        val documents = DocumentProvider().apply { put(uri, text) }
        val compilation = GroovyCompilationService(documentProvider = documents)
        try {
            if (input == Input.WORKSPACE_CLASSES) {
                val declaration = "package example\nclass Widget {}\n"
                documents.put(candidate, declaration)
                assertTrue(compilation.compile(candidate, declaration).isSuccess)
            }
            val request = SignatureCompletionRequest(compilation, documents, uri,
                Position(text.lines().lastIndex, text.lines().last().length), completion = true)
            val error = assertFailsWith<ResponseErrorException> {
                request.deliver {
                    val items = CompletionProvider.getContextualCompletions(request)
                    when (input) {
                        Input.WORKSPACE_CLASSES -> {
                            assertTrue(items.any { it.detail == "example.Widget" && !it.additionalTextEdits.isNullOrEmpty() })
                            compilation.symbolIndexingService.invalidate(candidate)
                            assertTrue(compilation.symbolIndexingService.findClasses(ClassNameQuery.Prefix("Wid")).isEmpty())
                        }
                        Input.CLASSPATH_NAMES -> {
                            assertTrue(items.any { it.label == "java.util.ArrayList" })
                            compilation.classpathService.updateClasspath(emptyList())
                        }
                    }
                    assertTrue(request.source.isCurrent(), "actual index retirement must be independent of the source ticket")
                    items
                }
            }
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        } finally {
            compilation.shutdown()
        }
    }
}
