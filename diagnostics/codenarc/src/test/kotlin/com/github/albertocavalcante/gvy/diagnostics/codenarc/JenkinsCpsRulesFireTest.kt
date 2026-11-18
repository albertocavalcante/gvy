// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * Pins which of CodeNarc's bundled Jenkins (CPS) rules produce a finding through gvy's real analysis path
 * (`CodeNarcDiagnosticProvider`, the Jenkins profile, one temporary file, no classpath) on a `Jenkinsfile` and on a
 * shared library `vars/` script. Each entry maps a rule to the 0-based lines it reports for its snippet; an empty list
 * records a rule that does not fire today.
 */
class JenkinsCpsRulesFireTest {

    @TempDir
    lateinit var root: Path

    private val context
        get() = object : WorkspaceContext {
            override val root: Path = this@JenkinsCpsRulesFireTest.root
            override fun getConfiguration() = object : DiagnosticConfiguration {
                override val isEnabled = true
                override val propertiesFile: String? = null
                override val autoDetectConfig = true
            }
        }

    /** One snippet per rule, each written so that only its own rule is expected to match. */
    private val snippets = linkedMapOf(
        "ClassNotSerializable" to "class Helper {\n    String name\n}\n",
        "ClosureInGString" to "def x = 1\necho \"value \${-> x}\"\n",
        "CpsCallFromNonCpsMethod" to "@NonCPS\ndef f(script) {\n    script.echo('x')\n}\n",
        "ExpressionInCpsMethodNotSerializable" to "def run() {\n    def s = new Scanner('x')\n    echo 'a'\n}\n",
        "ForbiddenCallInCpsMethod" to "def f(list) {\n    list.sort { a, b -> a <=> b }\n}\n",
        "ObjectOverrideOnlyNonCpsMethods" to "class Foo implements Serializable {\n    String toString() { 'x' }\n}\n",
        "ParameterOrReturnTypeNotSerializable" to "void f(Thread t) {\n}\n",
    )

    // FIXME(#2257): replace the entries below that are empty with the lines the rule reports once it fires.
    // See: https://github.com/albertocavalcante/gvy/issues/2257
    private val expectedLines = mapOf(
        "ClassNotSerializable" to listOf(0),
        "ClosureInGString" to listOf(1),
        "CpsCallFromNonCpsMethod" to listOf(2),
        "ExpressionInCpsMethodNotSerializable" to listOf(1),
        "ForbiddenCallInCpsMethod" to listOf(1),
        "ObjectOverrideOnlyNonCpsMethods" to listOf(1),
        "ParameterOrReturnTypeNotSerializable" to listOf(0),
    )

    private fun firedLines(relative: String): Map<String, List<Int>> = runBlocking {
        val provider = CodeNarcDiagnosticProvider(context)
        snippets.mapValues { (rule, code) ->
            val file = root.resolve(relative).also { it.parent.createDirectories() }.apply { writeText(code) }
            provider.analyze(code, file.toUri())
                .filter { it.code?.left == rule }
                .map { it.range.start.line }
                .sorted()
        }
    }

    @Test
    fun `Jenkins CPS rules that fire on a Jenkinsfile`() {
        assertEquals(expectedLines, firedLines("Jenkinsfile"))
    }

    @Test
    fun `Jenkins CPS rules that fire on a shared library vars script`() {
        assertEquals(expectedLines, firedLines("vars/buildPlugin.groovy"))
    }
}
