package com.github.albertocavalcante.gvy.gls.e2e

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RenameScenarioAssertionsTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @TestFactory
    fun `rename assertions require the right replacement and range in both files`(): List<DynamicTest> = listOf(
        "correct", "reversed document order", "wrong definition text", "wrong usage text", "swapped ends",
        "missing usage", "duplicate document", "extra document", "extra edit",
    ).map { variant ->
        dynamicTest(variant) {
            val fixture = ScenarioAssertionFixture("rename-symbol.yaml", workspaceRoot)
            val definitionRange = nameRange(fixture, "Definitions")
            val usageRange = nameRange(fixture, "Usages")
            val definition = document(
                fixture,
                "Definitions",
                if (variant == "swapped ends") {
                    Range(definitionRange.start, Position(definitionRange.end.line, usageRange.end.character))
                } else {
                    definitionRange
                },
                if (variant == "wrong definition text") "WRONG" else NEW_NAME,
            )
            val usage = document(
                fixture,
                "Usages",
                if (variant == "swapped ends") {
                    Range(usageRange.start, Position(usageRange.end.line, definitionRange.end.character))
                } else {
                    usageRange
                },
                if (variant == "wrong usage text") "WRONG" else NEW_NAME,
            )
            val documents = when (variant) {
                "reversed document order" -> listOf(usage, definition)
                "missing usage" -> listOf(definition)
                "duplicate document" -> listOf(definition, definition)
                "extra document" -> listOf(definition, usage, document(fixture, "Other", usageRange, NEW_NAME))
                "extra edit" -> listOf(
                    definition,
                    usage + ("edits" to listOf(edit(usageRange, NEW_NAME), edit(usageRange, NEW_NAME))),
                )
                else -> listOf(definition, usage)
            }
            // Independently construct the generic endpoint's raw protocol shape.
            val response = mapOf("documentChanges" to documents)
            if (variant in
                setOf("correct", "reversed document order")
            ) {
                fixture.accepts(response)
            } else {
                fixture.rejects(response)
            }
        }
    }

    @Test
    fun `rename assertions also accept typed WorkspaceEdit with its document change adapter`() {
        val fixture = ScenarioAssertionFixture("rename-symbol.yaml", workspaceRoot)
        val changes = listOf("Definitions", "Usages").map { name ->
            Either.forLeft<TextDocumentEdit, ResourceOperation>(
                TextDocumentEdit(
                    VersionedTextDocumentIdentifier(
                        fixture.context.workspace.resolveUri("src/main/groovy/$name.groovy"),
                        1,
                    ),
                    listOf(TextEdit(nameRange(fixture, name), NEW_NAME)),
                ),
            )
        }
        fixture.accepts(WorkspaceEdit(changes))
    }

    private fun document(
        fixture: ScenarioAssertionFixture,
        name: String,
        range: Range,
        text: String,
    ): Map<String, Any> = mapOf(
        "textDocument" to mapOf(
            "uri" to fixture.context.workspace.resolveUri("src/main/groovy/$name.groovy"),
            "version" to 1,
        ),
        "edits" to listOf(edit(range, text)),
    )

    private fun edit(range: Range, text: String): Map<String, Any> = mapOf(
        "range" to mapOf(
            "start" to mapOf("line" to range.start.line, "character" to range.start.character),
            "end" to mapOf("line" to range.end.line, "character" to range.end.character),
        ),
        "newText" to text,
    )

    private fun nameRange(fixture: ScenarioAssertionFixture, name: String): Range {
        val document = fixture.definition.scenario.steps.filterIsInstance<ScenarioStep.OpenDocument>()
            .single { it.path == "src/main/groovy/$name.groovy" }
        val text = CursorMarkers.strip(requireNotNull(document.text)).text
        val offset = text.indexOf(OLD_NAME)
        require(offset >= 0 && offset == text.lastIndexOf(OLD_NAME)) { "Expected one $OLD_NAME in $name" }
        val prefix = text.take(offset)
        val line = prefix.count { it == '\n' }
        val character = prefix.substringAfterLast('\n').length
        return Range(Position(line, character), Position(line, character + OLD_NAME.length))
    }

    companion object {
        private const val OLD_NAME = "performAction"
        private const val NEW_NAME = "doSomething"
    }
}
