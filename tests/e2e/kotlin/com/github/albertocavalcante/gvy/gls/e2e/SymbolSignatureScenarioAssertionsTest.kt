// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SymbolSignatureScenarioAssertionsTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @TestFactory
    @Suppress("DEPRECATION") // The server's legacy SymbolInformation response is still an exercised protocol variant.
    fun `workspace symbol assertions accept each typed symbol and reject wrong results`(): List<DynamicTest> =
        symbols.flatMap { case ->
            listOf("correct", "missing", "duplicate", "wrong name", "wrong kind", "wrong URI").map { variant ->
                dynamicTest("${case.name}: $variant") {
                    val fixture = ScenarioAssertionFixture("workspace-symbols.yaml", workspaceRoot, case.source)
                    val symbol = SymbolInformation().apply {
                        name = case.name
                        kind = case.kind
                        location = Location(
                            fixture.context.workspace.resolveUri("src/main/groovy/Symbols.groovy"),
                            Range(Position(0, 0), Position(2, 1)),
                        )
                    }
                    val items = when (variant) {
                        "missing" -> emptyList()
                        "duplicate" -> listOf(symbol, symbol)
                        "wrong name" -> listOf(symbol.apply { name = "Other" })
                        "wrong kind" -> listOf(symbol.apply { kind = SymbolKind.Variable })
                        "wrong URI" -> listOf(symbol.apply { location.uri += ".other" })
                        else -> listOf(symbol)
                    }
                    val response = Either.forLeft<List<SymbolInformation>, List<WorkspaceSymbol>>(items)
                    if (variant == "correct") fixture.accepts(response) else fixture.rejects(response)
                }
            }
        }

    @TestFactory
    fun `signature assertions accept full labels and overloads and reject unrelated results`(): List<DynamicTest> =
        signatures.flatMap { (file, label) ->
            listOf("correct", "overloads", "missing", "wrong label").map { variant ->
                dynamicTest("$file: $variant") {
                    val fixture = ScenarioAssertionFixture(file, workspaceRoot)
                    val labels = when (variant) {
                        "missing" -> emptyList()
                        "wrong label" -> listOf("Other()")
                        "overloads" -> listOf("Other()", label)
                        else -> listOf(label)
                    }
                    val response = SignatureHelp().apply {
                        this.signatures = labels.map { SignatureInformation(it) }
                        activeSignature = 0
                        activeParameter = 0
                    }
                    if (variant in setOf("correct", "overloads")) {
                        fixture.accepts(response)
                    } else {
                        fixture.rejects(response)
                    }
                }
            }
        }

    @Test
    @Suppress("DEPRECATION") // Exercise the server's legacy SymbolInformation union variant.
    fun `missing workspace class matches its xfail while a wrong kind remains unexpected`() {
        val fixture = ScenarioAssertionFixture("workspace-symbols.yaml", workspaceRoot, "symbolResult")
        val missing = Either.forLeft<List<SymbolInformation>, List<WorkspaceSymbol>>(emptyList())
        assertThat(fixture.classifyFailure(missing)).isInstanceOf(XfailDecision.Expected::class.java)
        val symbol = SymbolInformation().apply {
            name = "UniqueController"
            kind = SymbolKind.Variable
            location = Location(
                fixture.context.workspace.resolveUri("src/main/groovy/Symbols.groovy"),
                Range(Position(0, 0), Position(2, 1)),
            )
        }
        val wrongKind = Either.forLeft<List<SymbolInformation>, List<WorkspaceSymbol>>(listOf(symbol))
        val decision = fixture.classifyFailure(wrongKind)
        assertThat(decision).isInstanceOf(XfailDecision.Unexpected::class.java)
        assertThat((decision as XfailDecision.Unexpected).failure).hasMessageContaining(".kind")
    }

    @TestFactory
    fun `missing signatures still match their declared xfail`(): List<DynamicTest> = signatures.map { (file, _) ->
        dynamicTest(file) {
            val response = SignatureHelp().apply { this.signatures = emptyList() }
            assertThat(ScenarioAssertionFixture(file, workspaceRoot).classifyFailure(response))
                .isInstanceOf(XfailDecision.Expected::class.java)
        }
    }

    private data class SymbolCase(val name: String, val kind: SymbolKind, val source: String)

    companion object {
        private val symbols = listOf(
            SymbolCase("UniqueController", SymbolKind.Class, "symbolResult"),
            SymbolCase("uniqueMethod", SymbolKind.Method, "methodSymbolResult"),
        )
        private val signatures = listOf(
            "repro-signature-help-constructor.yaml" to "LineItem(String sku)",
            "repro-signature-help-crossfile-static.yaml" to "Money of(Number amount, String currency)",
        )
    }
}
