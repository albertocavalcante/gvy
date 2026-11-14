// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceRulesetInterpreterTest {
    @TempDir
    lateinit var tempDir: Path

    private fun accepted(text: String): String =
        assertIs<WorkspaceRulesetInterpreter.Result.Accepted>(WorkspaceRulesetInterpreter.interpret(text)).xml

    private fun rejected(text: String): String =
        assertIs<WorkspaceRulesetInterpreter.Result.Rejected>(WorkspaceRulesetInterpreter.interpret(text)).reason

    @Test
    fun `rejects the whole ruleset when a bundled reference does not exist`() {
        val reason = rejected(
            """
            ruleset {
                ruleset('rulesets/imports.xml') { include 'UnusedImport' }
                ruleset('rulesets/empty.xml') { include 'EmptyCatchBlock' }
            }
            """.trimIndent(),
        )

        assertTrue(reason.contains("rulesets/empty.xml"), reason)
    }

    @Test
    fun `interprets the declarative subset into XML`() {
        val xml = accepted(
            """
            ruleset {
                description 'Team & rules'
                ruleset('rulesets/basic.xml') {
                    exclude 'EmptyCatchBlock'
                    include 'EmptyIfStatement'
                    EmptyIfStatement { priority = 2 }
                }
                TrailingWhitespace
                CyclomaticComplexity {
                    maxMethodComplexity = 10
                    priority = -1
                }
                LineLength(length: 140)
            }
            """.trimIndent(),
        )

        assertTrue(xml.contains("<description>Team &amp; rules</description>"), xml)
        assertTrue(xml.contains("<ruleset-ref path=\"rulesets/basic.xml\">"), xml)
        assertTrue(xml.contains("<exclude name=\"EmptyCatchBlock\"/>"), xml)
        assertTrue(xml.contains("<rule-config name=\"EmptyIfStatement\">"), xml)
        assertTrue(xml.contains("<rule class=\"org.codenarc.rule.formatting.TrailingWhitespaceRule\">"), xml)
        assertTrue(xml.contains("<property name=\"maxMethodComplexity\" value=\"10\"/>"), xml)
        assertTrue(xml.contains("<property name=\"priority\" value=\"-1\"/>"), xml)
        assertTrue(xml.contains("<property name=\"length\" value=\"140\"/>"), xml)
    }

    @Test
    fun `rejects every bypass payload and unsafe construct`() {
        val payloads = mapOf(
            "evaluate" to "ruleset { resolveStrategy = 2\n evaluate 'new File(\"x\").text = \"y\"' }",
            "constructor" to "ruleset { new File('x').text = 'y' }",
            "static call" to "ruleset { System.exit(1) }",
            "gstring" to "ruleset { description \"\${System.getenv('HOME')}\" }",
            "top-level statement" to "println 'x'\nruleset { TrailingWhitespace }",
            "import" to "import java.io.File\nruleset { TrailingWhitespace }",
            "class" to "class Evil { static { println 1 } }\nruleset { TrailingWhitespace }",
            "file ruleset" to "ruleset { ruleset('file:/tmp/evil.groovy') }",
            "absolute ruleset" to "ruleset { ruleset('/tmp/evil.groovy') }",
            "traversal" to "ruleset { ruleset('rulesets/../../evil.xml') }",
            "groovy ruleset" to "ruleset { ruleset('rulesets/basic.groovy') }",
            "rule()" to "ruleset { rule('file:/tmp/evil.groovy') }",
            "unknown rule" to "ruleset { NoSuchRuleAtAll }",
            "non-literal property" to "ruleset { TrailingWhitespace { priority = foo() } }",
            "syntax error" to "ruleset {",
        )

        for ((label, text) in payloads) {
            rejected(text).also { assertTrue(it.isNotBlank(), "$label must be rejected with a reason") }
        }
    }

    @Test
    fun `missing bundled ruleset preserves a multiline literal range`() = runBlocking {
        val file = tempDir.resolve(".codenarc")
        val text = "ruleset { ruleset('rulesets/emp" + '\\' + "\nty.xml') }"
        Files.writeString(file, text)
        val provider = CodeNarcDiagnosticProvider(
            object : WorkspaceContext {
                override val root: Path = tempDir
                override fun getConfiguration() = object : DiagnosticConfiguration {
                    override val isEnabled = true
                    override val propertiesFile: String? = null
                    override val autoDetectConfig = true
                }
            },
        )
        val diagnostic = provider.analyze("class A {}", tempDir.resolve("A.groovy").toUri())
            .single { it.code?.left == "gvy.providerFailure" }
        val range = diagnostic.relatedInformation.single().location.range
        assertEquals(org.eclipse.lsp4j.Position(0, 18), range.start)
        assertEquals(org.eclipse.lsp4j.Position(1, 7), range.end)
    }

    @Test
    fun `rejects nonexistent bundled rulesets at their literal`() {
        val result = assertIs<WorkspaceRulesetInterpreter.Result.Rejected>(
            WorkspaceRulesetInterpreter.interpret("ruleset { ruleset('rulesets/empty.xml') }"),
        )
        assertTrue(result.reason.contains("rulesets/empty.xml"))
        assertEquals(1, result.line)
        assertEquals(19, result.column)
        assertEquals(39, result.endColumn)
    }

    @Test
    fun `caps size and nesting`() {
        assertTrue(
            rejected(
                "ruleset { description '" + "x".repeat(64 * 1024) + "' }",
            ).contains("larger"),
        )
        val deep = "ruleset { " + "x { ".repeat(5_000) + "}".repeat(5_000) + " }"
        assertTrue(rejected(deep).contains("nested"))
        val many = "ruleset {\n" + "TrailingWhitespace\n".repeat(2_500) + "}"
        assertTrue(rejected(many).contains("statements"))
    }

    @Test
    fun `configuration notice survives an analyzer failure`() = runBlocking {
        Files.writeString(tempDir.resolve(".codenarc"), "ruleset { ruleset('rulesets/empty.xml') }")
        val analyzer = mockk<CodeAnalyzer>()
        every { analyzer.analyze(any(), any(), any(), any()) } throws IllegalStateException("analyzer failed")
        val provider = CodeNarcDiagnosticProvider(
            object : WorkspaceContext {
                override val root: Path = tempDir
                override fun getConfiguration() = object : DiagnosticConfiguration {
                    override val isEnabled = true
                    override val propertiesFile: String? = null
                    override val autoDetectConfig = true
                }
            },
            codeAnalyzer = analyzer,
        )
        val emitted = mutableListOf<org.eclipse.lsp4j.Diagnostic>()
        assertFailsWith<IllegalStateException> {
            provider.analyzeStreaming("class A {}", tempDir.resolve("A.groovy").toUri()) { emitted.add(it) }
        }
        assertEquals("gvy.providerFailure", emitted.single().code.left)
    }

    @Test
    fun `invalid packaged ruleset is linked from affected source and clears after correction`() = runBlocking {
        val config = tempDir.resolve(".codenarc")
        Files.writeString(config, "ruleset { ruleset('rulesets/empty.xml') }")
        val provider = CodeNarcDiagnosticProvider(
            object : WorkspaceContext {
                override val root: Path = tempDir
                override fun getConfiguration() = object : DiagnosticConfiguration {
                    override val isEnabled = true
                    override val propertiesFile: String? = null
                    override val autoDetectConfig = true
                }
            },
        )
        val uri = tempDir.resolve("Example.groovy").toUri()
        val broken = provider.analyze("class Example {}", uri)
            .single { it.code?.left == "gvy.providerFailure" }
        val link = broken.relatedInformation.single().location
        assertEquals(config.toUri().toString(), link.uri)
        assertEquals(18, link.range.start.character)
        assertEquals(38, link.range.end.character)

        Files.writeString(config, "ruleset { ruleset('rulesets/basic.xml') }")
        val corrected = provider.analyze("class Example {}", uri)
        assertTrue(corrected.none { it.code?.left == "gvy.providerFailure" })
    }

    @Test
    fun `a declarative workspace ruleset is honoured end to end`() = runBlocking {
        Files.writeString(tempDir.resolve("codenarc.groovy"), "ruleset { TrailingWhitespace }")
        val file = tempDir.resolve("A.groovy")
        val source = "class A {   \n}\n"
        Files.writeString(file, source)
        val provider = CodeNarcDiagnosticProvider(
            object : WorkspaceContext {
                override val root: Path = tempDir
                override fun getConfiguration() = object : DiagnosticConfiguration {
                    override val isEnabled = true
                    override val propertiesFile: String? = null
                    override val autoDetectConfig = true
                }
            },
        )

        val diagnostics = provider.analyze(source, file.toUri())

        assertEquals(1, diagnostics.count { "TrailingWhitespace" in (it.code?.left ?: "") }, diagnostics.toString())
    }

    @Test
    fun `rejects regular-expression properties that could ReDoS the analysis`() {
        for (property in listOf("regex", "classNameRegex", "doNotApplyToFilesMatching", "applyToFilesMatching")) {
            val ruleset = "ruleset { IllegalRegex { $property = '(a+)+$' } }"
            assertTrue(rejected(ruleset).isNotBlank(), "$property must be rejected")
        }
    }

    @Test
    fun `an oversized ruleset file is rejected without being read as text`() {
        val file = tempDir.resolve("ws3").also { Files.createDirectories(it) }.resolve("codenarc.groovy")
        Files.write(file, ByteArray((WorkspaceRulesetInterpreter.MAX_FILE_BYTES + 1).toInt()) { 'a'.code.toByte() })
        val resolved = HierarchicalRulesetResolver().resolve(
            object : WorkspaceContext {
                override val root: Path = file.parent
                override fun getConfiguration() = object : DiagnosticConfiguration {
                    override val isEnabled = true
                    override val propertiesFile: String? = null
                    override val autoDetectConfig = true
                }
            },
        )

        assertEquals(listOf("codenarc.groovy"), resolved.ignoredWorkspaceRulesets)
    }

    @Test
    fun `rejects string property values whose wildcards compile to catastrophic regexes`() {
        val payload = "*a".repeat(25) + "b"
        for (property in listOf(
            "doNotApplyToClassNames",
            "applyToClassNames",
            "ignoreMethodNames",
            "ignoreFieldNames",
        )) {
            val ruleset = "ruleset { TrailingWhitespace { $property = '$payload' } }"
            assertTrue(rejected(ruleset).contains("property"), "$property with a wildcard string must be rejected")
        }
    }

    @Test
    fun `accepts numeric and boolean property values`() {
        val xml = accepted("ruleset { LineLength { length = 140\n priority = 2\n enabled = false\n ratio = 1.5 } }")

        assertTrue(xml.contains("<property name=\"enabled\" value=\"false\"/>"), xml)
        assertTrue(xml.contains("<property name=\"ratio\" value=\"1.5\"/>"), xml)
    }
}
