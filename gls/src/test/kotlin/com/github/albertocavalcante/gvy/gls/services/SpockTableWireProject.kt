// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.source.DocumentVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.nio.file.Files
import java.nio.file.Path

internal class SpockTableWireProject(private val workspace: Path, superclass: String = "BaseSpec") {
    val src: Path = Files.createDirectories(workspace.resolve("src"))
    val base: Path = src.resolve(superclass.replace('.', '/') + ".groovy")
    val source = """
        class TableSpec extends $superclass {
            def "feature"() {
                expect:
                true
                where:
                a | b
                100|2
            }
        }
    """.trimIndent()
    val next = "$source\n// next version"
    val uri = Files.writeString(src.resolve("TableSpec.groovy"), source).toUri()
    val documents = DocumentProvider()
    val params = CodeActionParams(
        TextDocumentIdentifier(uri.toString()),
        Range(Position(5, 0), Position(6, 10)),
        CodeActionContext(emptyList()).apply { only = listOf(CodeActionKind.RefactorRewrite) },
    )

    init {
        Files.createDirectories(base.parent)
    }

    fun service(
        scope: CoroutineScope,
        compilation: GroovyCompilationService,
        formatter: Formatter = Formatter { it },
    ) = GroovyTextDocumentService(
        scope,
        compilation,
        GroovyTextDocumentServiceOptions(documentProvider = documents, formatter = formatter),
    )

    fun open(compilation: GroovyCompilationService) {
        fun jar(type: Class<*>) = Path.of(type.protectionDomain.codeSource.location.toURI())
        compilation.updateWorkspaceModel(
            workspace,
            listOf(jar(spock.lang.Specification::class.java), jar(groovy.lang.GroovyObject::class.java)),
            listOf(src),
        )
        compilation.onDocumentOpened(uri)
        documents.put(uri, source, DocumentVersion.of(7))
        runBlocking { compilation.compile(uri, source) }
    }
}
