// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.converters.toLspRange
import groovy.lang.GroovyClassLoader
import org.assertj.core.api.Assertions.assertThat
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NavigationScenarioAssertionsTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @TestFactory
    fun `navigation assertions accept the target URI and full declaration range`(): List<DynamicTest> =
        cases.map { case ->
            dynamicTest(case.file) {
                val fixture = ScenarioAssertionFixture(case.file, workspaceRoot)
                fixture.accepts(response(listOf(declaration(fixture, case))))
            }
        }

    @TestFactory
    fun `navigation assertions reject missing duplicate wrong file and wrong span targets`(): List<DynamicTest> =
        cases.flatMap { case ->
            listOf("missing", "duplicate", "wrong URI", "wrong span").map { mutant ->
                dynamicTest("${case.file}: $mutant") {
                    val fixture = ScenarioAssertionFixture(case.file, workspaceRoot)
                    val target = declaration(fixture, case)
                    val locations = when (mutant) {
                        "missing" -> emptyList()
                        "duplicate" -> listOf(target, target)
                        "wrong URI" -> listOf(Location("${target.uri}.other", target.range))
                        else -> listOf(Location(target.uri, Range(Position(0, 0), Position(0, 1))))
                    }
                    fixture.rejects(response(locations))
                }
            }
        }

    private fun response(locations: List<Location>): Either<List<Location>, List<LocationLink>> =
        Either.forLeft(locations)

    @TestFactory
    fun `known navigation failures still match their declared xfail`(): List<DynamicTest> = cases.map { case ->
        dynamicTest(case.file) {
            val fixture = ScenarioAssertionFixture(case.file, workspaceRoot)
            val locations = if (case.file in wrongFileFailures) {
                listOf(Location(fixture.context.cursors.getValue("use").uri, declaration(fixture, case).range))
            } else {
                emptyList()
            }
            assertThat(fixture.classifyFailure(response(locations))).isInstanceOf(XfailDecision.Expected::class.java)
        }
    }

    private fun declaration(fixture: ScenarioAssertionFixture, case: NavigationCase): Location {
        val (path, markedText) = fixture.definition.scenario.workspace?.files.orEmpty()
            .entries.single { it.value.contains("|^${case.marker}|") }
        return GroovyClassLoader().use { loader ->
            val unit = CompilationUnit(CompilerConfiguration(), null, loader)
            unit.addSource(path, CursorMarkers.strip(markedText).text)
            unit.compile(Phases.CONVERSION)
            val type = unit.ast.classes.single { it.name == case.type }
            val node = case.method?.let { name -> type.methods.single { it.name == name } } ?: type
            // These providers return Location.range for the entire AST declaration, not a selection range.
            Location(fixture.context.workspace.resolveUri(path), requireNotNull(node.toLspRange()))
        }
    }

    private data class NavigationCase(
        val file: String,
        val marker: String,
        val type: String,
        val method: String? = null,
    )

    companion object {
        private val wrongFileFailures = setOf(
            "repro-definition-extends-imported.yaml",
            "repro-definition-parameter-type.yaml",
            "repro-definition-super-call.yaml",
        )
        private val cases = listOf(
            NavigationCase("repro-definition-extends-imported.yaml", "baseDecl", "demo.base.Base"),
            NavigationCase("repro-definition-parameter-type.yaml", "moneyDecl", "demo.money.Money"),
            NavigationCase("repro-definition-super-call.yaml", "goDecl", "demo.MiscA", "go"),
            NavigationCase("repro-definition-nested-class-receiver.yaml", "nestedGoDecl", "demo.Nest\$In", "nestedGo"),
            NavigationCase("repro-definition-typedefinition-crossfile.yaml", "orderDecl", "demo.order.Order"),
            NavigationCase("repro-definition-typedefinition-def-call.yaml", "orderDecl", "demo.order.Order"),
        )
    }
}
