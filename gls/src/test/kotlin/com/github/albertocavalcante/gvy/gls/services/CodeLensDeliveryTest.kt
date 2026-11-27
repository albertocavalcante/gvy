// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.compilation.setTestClasspath
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.testing.api.TestFramework
import com.github.albertocavalcante.gvy.testing.api.TestFrameworkDetector
import com.github.albertocavalcante.gvy.testing.api.TestItem
import com.github.albertocavalcante.gvy.testing.registry.TestFrameworkRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodeLensDeliveryTest {
    @Test
    fun `registry changes during final source qualification reject the empty answer`(@TempDir root: Path): Unit =
        runBlocking {
            val registry = TestFrameworkRegistry.default
            val previous = registry.getDetectors()
            val armed = AtomicBoolean()
            val changed = AtomicBoolean()
            val detector = object : TestFrameworkDetector {
                override val framework = TestFramework.SPOCK

                override fun appliesTo(classNode: ClassNode, module: ModuleNode?, classLoader: ClassLoader?): Boolean =
                    true

                override fun extractTests(classNode: ClassNode): List<TestItem> {
                    armed.set(true)
                    return emptyList()
                }
            }
            val workspace = WorkspaceManager()
            workspace.setTestClasspath(root) {
                if (armed.compareAndSet(true, false)) {
                    registry.register(detector)
                    changed.set(true)
                }
                emptyList()
            }
            workspace.initializeWorkspace(root)
            val uri = root.resolve("FinalCodeLens.groovy").toUri()
            val documents = DocumentProvider().apply { put(uri, "class Sample {}\n", DocumentVersion.of(1)) }
            val compilation = GroovyCompilationService(documentProvider = documents, workspaceManager = workspace)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            registry.clear()
            registry.register(detector)
            try {
                val service = GroovyTextDocumentService(scope, compilation)
                val answer = service.codeLens(CodeLensParams(TextDocumentIdentifier(uri.toString())))
                val error = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
                assertEquals(
                    ResponseErrorCode.ContentModified.value,
                    assertIs<ResponseErrorException>(error.cause).responseError.code,
                )
                assertTrue(changed.get(), "the final actual compiler-context callback must mutate the registry")
            } finally {
                scope.cancel()
                compilation.shutdown()
                registry.clear()
                previous.forEach(registry::register)
            }
        }
}
