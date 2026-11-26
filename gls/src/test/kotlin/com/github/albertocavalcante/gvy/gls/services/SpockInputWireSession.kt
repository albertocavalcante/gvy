// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.spock.SpecificationStatus
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class SpockInputWireSession(val project: SpockTableWireProject) : AutoCloseable {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val compilation = GroovyCompilationService(documentProvider = project.documents)
    private var version = 7

    fun open(extraClasspath: List<Path> = emptyList()) = project.open(compilation, extraClasspath)

    fun service(formatter: Formatter = Formatter { it }) = project.service(scope, compilation, formatter)

    fun owned(): ParseResult = runBlocking {
        assertIs<SnapshotAcquisition.Available>(compilation.acquireEditorSnapshot(project.uri)).snapshot.parseResult
    }

    fun edit() {
        version++
        val next = "${project.source}\n// version $version"
        assertNotNull(project.documents.update(project.uri, next, DocumentVersion.of(version)))
    }

    fun breakSourceBase() {
        val text = Files.readString(project.base)
        Files.writeString(project.base, text.replace("{}", "{ void broken( }"))
        edit()
        val parsed = owned()
        assertTrue(parsed.outcome.excludedSources.any { it.source == project.base.toString() }, "${parsed.outcome}")
        val module = assertNotNull(parsed.ast)
        val classes = SpockSourceContext.from(module, isPartial = true).classes
        val owner = classes.single { it.classNode.name == "TableSpec" }
        assertEquals(SpecificationStatus.UNCERTAIN, owner.status)
    }

    fun tables(service: GroovyTextDocumentService): List<CodeAction> =
        service.codeAction(project.params).get(10, TimeUnit.SECONDS).map { it.right }
            .filter { it.title == "Align Spock data table" }

    fun assertTable(service: GroovyTextDocumentService) {
        val action = tables(service).single()
        assertEquals(CodeActionKind.RefactorRewrite, action.kind)
        val change = action.edit.documentChanges.single().left
        assertEquals(project.uri.toString(), change.textDocument.uri)
        assertEquals(version, change.textDocument.version)
        assertEquals(listOf(5, 6), change.edits.map { it.range.start.line })
    }

    override fun close() {
        scope.cancel()
        compilation.shutdown()
    }
}
