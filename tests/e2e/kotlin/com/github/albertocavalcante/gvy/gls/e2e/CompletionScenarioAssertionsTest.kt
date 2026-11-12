// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import groovy.lang.GroovyClassLoader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CompletionScenarioAssertionsTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @TestFactory
    fun `auto import assertions accept ordinary import edits in both completion variants`(): List<DynamicTest> =
        cases.flatMap { case ->
            listOf(
                dynamicTest("${case.file}: item list") {
                    fixture(case).accepts(Either.forLeft<List<CompletionItem>, CompletionList>(listOf(item(case))))
                },
                dynamicTest("${case.file}: completion list") {
                    fixture(case).accepts(
                        Either.forRight<List<CompletionItem>, CompletionList>(
                            CompletionList(false, listOf(item(case))),
                        ),
                    )
                },
            )
        }

    @TestFactory
    fun `auto import assertions reject missing duplicated or incorrect imports`(): List<DynamicTest> =
        cases.flatMap { case ->
            listOf(
                "missing candidate" to emptyList(),
                "duplicate candidate" to listOf(item(case), item(case)),
                "wrong label" to listOf(item(case).apply { label = "Other" }),
                "missing edit" to listOf(item(case).apply { additionalTextEdits = emptyList() }),
                "duplicate edit" to listOf(
                    item(case).apply {
                        additionalTextEdits = additionalTextEdits + additionalTextEdits
                    },
                ),
                "wrong package" to listOf(
                    item(case).apply {
                        additionalTextEdits = listOf(importEdit("other.Type"))
                    },
                ),
                "different class with the same prefix" to listOf(
                    item(case).apply {
                        additionalTextEdits = listOf(importEdit("${case.type}Other"))
                    },
                ),
            ).map { (name, items) ->
                dynamicTest("${case.file}: $name") {
                    fixture(case).rejects(
                        Either.forRight<List<CompletionItem>, CompletionList>(
                            CompletionList(false, items),
                        ),
                    )
                }
            }
        }

    @Test
    fun `JDK auto import fixture uses a class outside Groovy default imports`() {
        GroovyClassLoader().use { loader ->
            assertThatThrownBy { loader.parseClass("class NeedsImport { LinkedBlockingQueue queue }") }
                .isInstanceOf(MultipleCompilationErrorsException::class.java)
                .hasMessageContaining("unable to resolve class LinkedBlockingQueue")
            loader.parseClass(
                """
                import java.util.concurrent.LinkedBlockingQueue
                class WithImport { LinkedBlockingQueue queue }
                """.trimIndent(),
            )
            loader.parseClass("class DefaultImport { LinkedHashMap map }")
        }
    }

    @TestFactory
    fun `missing auto import candidates still match their declared xfail`(): List<DynamicTest> = cases.map { case ->
        dynamicTest(case.file) {
            val response = Either.forRight<List<CompletionItem>, CompletionList>(CompletionList(false, emptyList()))
            assertThat(fixture(case).classifyFailure(response)).isInstanceOf(XfailDecision.Expected::class.java)
        }
    }

    private fun fixture(case: ImportCase) = ScenarioAssertionFixture(case.file, workspaceRoot)

    private fun item(case: ImportCase) = CompletionItem(case.label).apply {
        additionalTextEdits = listOf(importEdit(case.type))
    }

    private fun importEdit(type: String) = TextEdit(Range(Position(0, 0), Position(0, 0)), "import $type\n")

    private data class ImportCase(val file: String, val label: String, val type: String)

    companion object {
        private val cases = listOf(
            ImportCase("completion-import-class.yaml", "StringUtils", "com.utils.StringUtils"),
            ImportCase("repro-completion-auto-import-generic-arg.yaml", "Rectangle", "demo.shapes.Rectangle"),
            ImportCase(
                "repro-completion-auto-import-jdk.yaml",
                "LinkedBlockingQueue",
                "java.util.concurrent.LinkedBlockingQueue",
            ),
        )
    }
}
