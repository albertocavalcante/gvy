// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.spi.json.JsonSmartJsonProvider
import com.jayway.jsonpath.spi.mapper.JsonSmartMappingProvider
import groovy.lang.GroovyClassLoader
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.readText

class GradleDependencyScenarioTest {
    @TempDir
    lateinit var workspaceRoot: Path

    private val scenarioPath get() = Path.of(resolveScenarioDir()).resolve("gradle-hover-deps.yaml")
    private val definition by lazy { ScenarioLoader().load(scenarioPath) }
    private val document get() = definition.scenario.steps.filterIsInstance<ScenarioStep.OpenDocument>().single()
    private val fixtureRoot get() = scenarioPath.parent.parent.resolve("workspaces")
        .resolve(requireNotNull(definition.scenario.workspace?.fixture))
    private val libraryPath get() = fixtureRoot.resolve("libs/testlib.jar")
    private val context by lazy {
        ScenarioContext(
            definition = definition,
            session = mockk(),
            workspace = ScenarioWorkspace(workspaceRoot),
            jsonPathConfig = Configuration.builder()
                .jsonProvider(JsonSmartJsonProvider())
                .mappingProvider(JsonSmartMappingProvider())
                .build(),
        ).also { it.registerBuiltInVariables() }
    }
    private val uri get() = context.workspace.resolveUri(requireNotNull(document.path))

    @Test
    fun `diagnostic oracle accepts an empty result for the clean document`() {
        assertThat(accepts(PublishDiagnosticsParams(uri, emptyList(), document.version))).isTrue()
    }

    @TestFactory
    fun `diagnostic oracle rejects compiler errors from every current source`(): List<DynamicTest> =
        listOf("groovy-parser", "GroovyParser", "Groovy").map { source ->
            dynamicTest(source) {
                val diagnostic = Diagnostic().apply {
                    range = Range(Position(0, 0), Position(0, 1))
                    message = "unable to resolve class com.example.testlib.Greeter"
                    severity = DiagnosticSeverity.Error
                    this.source = source
                }
                assertThat(accepts(PublishDiagnosticsParams(uri, listOf(diagnostic), document.version))).isFalse()
            }
        }

    @TestFactory
    fun `diagnostic oracle rejects missing and null diagnostic lists`(): List<DynamicTest> = listOf(
        "missing" to mapOf("uri" to uri, "version" to document.version),
        "null" to mapOf("uri" to uri, "version" to document.version, "diagnostics" to null),
    ).map { (name, payload) ->
        dynamicTest(name) { assertThat(accepts(payload)).isFalse() }
    }

    @Test
    fun `diagnostic oracle rejects another document's clean publication`() {
        assertThat(accepts(PublishDiagnosticsParams("$uri.other", emptyList(), document.version))).isFalse()
    }

    @Test
    fun `diagnostic oracle rejects a clean publication for another version`() {
        assertThat(accepts(PublishDiagnosticsParams(uri, emptyList(), document.version + 1))).isFalse()
    }

    @TestFactory
    fun `dependency gate accepts only nonzero Gradle completion messages`(): List<DynamicTest> = listOf(
        "Dependencies loaded: 1 JARs from Gradle" to true,
        "Dependencies loaded: 12 JARs from Gradle" to true,
        "Resolving build dependencies..." to false,
        "Dependencies loaded: 0 JARs from Gradle" to false,
        "Dependencies loaded: 12 JARs from Maven" to false,
        "Could not load build dependencies - LSP will work with project files only" to false,
    ).map { (message, expected) ->
        dynamicTest(message) {
            assertThat(accepts(MessageParams(MessageType.Info, message), "window/showMessage")).isEqualTo(expected)
        }
    }

    @Test
    fun `dependency resolution is required before the document is opened`() {
        val steps = definition.scenario.steps
        val dependencyGate = steps.indexOfFirst {
            it is ScenarioStep.WaitNotification && it.method == "window/showMessage"
        }
        assertThat(dependencyGate).isNotNegative()
        assertThat(dependencyGate).isLessThan(steps.indexOf(document))
    }

    @Test
    fun `the clean fixture compiles and runs only with its local testlib dependency`() {
        val text = document.text ?: fixtureRoot.resolve(requireNotNull(document.path)).readText()
        val fileName = Path.of(requireNotNull(document.path)).fileName.toString()
        GroovyClassLoader().use { loader ->
            assertThatThrownBy { loader.parseClass(text, fileName) }
                .isInstanceOf(MultipleCompilationErrorsException::class.java)
                .hasMessageContaining("com.example.testlib.Greeter")
        }
        GroovyClassLoader().use { loader ->
            loader.addURL(libraryPath.toUri().toURL())
            val consumer = loader.parseClass(text, fileName)
            val instance = consumer.getDeclaredConstructor().newInstance()
            assertThat(consumer.getMethod("greeting", String::class.java).invoke(instance, "gvy"))
                .isEqualTo("Hello, gvy!")
        }
    }

    @Test
    fun `the test library supports the minimum Java runtime`() {
        JarFile(libraryPath.toFile()).use { jar ->
            val classes = jar.entries().asSequence().filter { it.name.endsWith(".class") }.toList()
            assertThat(classes).isNotEmpty()
            classes.forEach { entry ->
                DataInputStream(jar.getInputStream(entry)).use { input ->
                    // Classfile header: u4 magic, u2 minor version, u2 major version.
                    input.skipBytes(CLASSFILE_MAJOR_VERSION_OFFSET)
                    assertThat(input.readUnsignedShort()).describedAs(entry.name)
                        .isLessThanOrEqualTo(JAVA_17_CLASSFILE_MAJOR_VERSION)
                }
            }
        }
    }

    private fun accepts(payload: Any?, method: String = "textDocument/publishDiagnostics"): Boolean {
        val checks = definition.scenario.steps.filterIsInstance<ScenarioStep.WaitNotification>()
            .single { it.method == method }.checks
        require(checks.isNotEmpty()) { "The scenario must assert its diagnostic result" }
        val node = JsonBridge.wrapJavaObject(payload)
        return checks.all { context.evaluateCheck(node, it, quiet = true) }
    }

    companion object {
        private const val CLASSFILE_MAJOR_VERSION_OFFSET = 6
        private const val JAVA_17_CLASSFILE_MAJOR_VERSION = 61
    }
}
