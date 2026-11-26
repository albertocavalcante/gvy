// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.setTestClasspath
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionStatus
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionTelemetryEvent
import com.github.albertocavalcante.gvy.source.DocumentVersion
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrossFileTargetDeliveryTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `definition target changes after the requested location was constructed`(links: Boolean, @TempDir root: Path) {
        val origin = root.resolve("Use.groovy").toUri()
        val target = root.resolve("Target.groovy").toUri()
        val text = "import demo.Target\nclass Use {}\n"
        val targetLine = "/* 🎯 */ class Target {}"
        val targetText = "package demo\n$targetLine\n"
        val version = DocumentVersion.of(3)
        val documents = DocumentProvider().apply {
            put(origin, text, version)
            put(target, targetText, version)
        }
        val compilation = GroovyCompilationService(documentProvider = documents)
        val armed = AtomicBoolean()
        val observed = AtomicBoolean()
        val client = mockk<LanguageClient>(relaxed = true)
        every { client.telemetryEvent(any()) } answers {
            val event = firstArg<DefinitionTelemetryEvent>()
            if (event.status == DefinitionStatus.SUCCESS && armed.compareAndSet(true, false)) {
                documents.remove(target)
                documents.put(target, targetText, version)
                observed.set(true)
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val options = GroovyTextDocumentServiceOptions(
                documentProvider = documents,
                definitionLinkSupport = links,
                client = { client },
            )
            val service = GroovyTextDocumentService(scope, compilation, options)
            val params = DefinitionParams(TextDocumentIdentifier(origin.toString()), Position(0, 13))
            val first = service.definition(params).get(15, TimeUnit.SECONDS)
            val expected = Range(Position(1, targetLine.indexOf("class")), Position(1, targetLine.length))
            if (links) {
                val link = first.right.single()
                assertEquals(target.toString(), link.targetUri)
                assertEquals(expected, link.targetRange)
                assertEquals(expected, link.targetSelectionRange)
            } else {
                val location = first.left.single()
                assertEquals(target.toString(), location.uri)
                assertEquals(expected, location.range)
            }
            armed.set(true)
            val failure = assertThrows<ExecutionException> { service.definition(params).get(15, TimeUnit.SECONDS) }
            assertTrue(observed.get())
            val error = assertIs<ResponseErrorException>(failure.cause).responseError
            assertEquals(ResponseErrorCode.ContentModified.value, error.code)
            // Keep origin-token precision independent of the post-render retirement gate above.
            if (links) {
                assertEquals(Range(Position(0, 12), Position(0, 18)), first.right.single().originSelectionRange)
            }
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    @Test
    fun `implementation discards a detached hierarchy after its producer classpath changes`(@TempDir root: Path): Unit =
        runBlocking {
            val contract = root.resolve("Contract.groovy").toUri()
            val implementation = root.resolve("Impl.groovy").toUri()
            val contractText = "interface Contract {\n    String greet()\n}\n"
            val implText = "class Impl implements Contract {\n    String greet() { 'hi' }\n}\n"
            val documents = DocumentProvider().apply {
                put(contract, contractText)
                put(implementation, implText)
            }
            val paths = AtomicReference<List<Path>>(emptyList())
            val actual = GroovyCompilationService(documentProvider = documents)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                actual.workspaceManager.setTestClasspath(root, paths::get)
                assertTrue(actual.compile(contract, contractText).isSuccess)
                assertTrue(actual.compile(implementation, implText).isSuccess)
                val compilation = spyk(actual)
                val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
                val service = GroovyTextDocumentService(scope, compilation, options)
                val params = ImplementationParams(TextDocumentIdentifier(contract.toString()), Position(0, 11))
                val first = service.implementation(params).get(15, TimeUnit.SECONDS).left.single()
                assertEquals(implementation.toString(), first.uri)
                assertEquals(0, first.range.start.line)
                val observed = AtomicBoolean()
                every { compilation.captureImplementationSnapshot(any()) } answers {
                    callOriginal().also { detached ->
                        val facts = assertNotNull(detached)
                        assertEquals(contractText, facts[contract]?.sourceText)
                        assertEquals(implText, facts[implementation]?.sourceText)
                        if (observed.compareAndSet(false, true)) {
                            val fingerprint = actual.workspaceManager.fingerprintFor(contract)
                            paths.set(listOf(Files.createDirectory(root.resolve("classpath"))))
                            assertEquals(fingerprint, actual.workspaceManager.fingerprintFor(contract))
                            val context = actual.workspaceManager.compileContextFor(contract, contractText)
                            assertEquals(paths.get(), context.classpath)
                        }
                    }
                }
                val failure = assertThrows<ExecutionException> {
                    service.implementation(params).get(15, TimeUnit.SECONDS)
                }
                assertTrue(observed.get())
                val error = assertIs<ResponseErrorException>(failure.cause).responseError
                assertEquals(ResponseErrorCode.ContentModified.value, error.code)
            } finally {
                scope.cancel()
                actual.shutdown()
            }
        }
}
