package com.github.albertocavalcante.gvy.diagnostics.sarif

import kotlinx.serialization.json.Json
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SarifWriterTest {

    @Test
    fun `should create valid SARIF output with single diagnostic`() {
        val writer = SarifWriter(toolName = "test-tool", toolVersion = "1.0.0")

        val diagnostic = Diagnostic(
            Range(Position(0, 0), Position(0, 10)),
            "Test message",
        ).apply {
            severity = DiagnosticSeverity.Warning
            source = "codenarc"
            code = Either.forLeft("UnusedVariable")
        }

        writer.addDiagnostic("src/main/groovy/Test.groovy", diagnostic)

        val output = writer.build()

        assertEquals(SarifOutput.SARIF_VERSION, output.version)
        assertEquals(SarifOutput.SARIF_SCHEMA, output.schema)
        assertEquals(1, output.runs.size)

        val run = output.runs.first()
        assertEquals("test-tool", run.tool.driver.name)
        assertEquals("1.0.0", run.tool.driver.version)
        assertEquals(1, run.results.size)

        val result = run.results.first()
        assertEquals("UnusedVariable", result.ruleId)
        assertEquals(SarifLevel.WARNING, result.level)
        assertEquals("Test message", result.message.text)

        val location = result.locations?.firstOrNull()
        assertNotNull(location)
        assertEquals("src/main/groovy/Test.groovy", location.physicalLocation?.artifactLocation?.uri)
        assertEquals(1, location.physicalLocation?.region?.startLine) // 0-based to 1-based
        assertEquals(1, location.physicalLocation?.region?.startColumn)
    }

    @Test
    fun `should map diagnostic severities correctly`() {
        assertEquals(SarifLevel.ERROR, DiagnosticSeverity.Error.toSarifLevel())
        assertEquals(SarifLevel.WARNING, DiagnosticSeverity.Warning.toSarifLevel())
        assertEquals(SarifLevel.NOTE, DiagnosticSeverity.Information.toSarifLevel())
        assertEquals(SarifLevel.NOTE, DiagnosticSeverity.Hint.toSarifLevel())
    }

    @Test
    fun `should produce valid JSON output`() {
        val writer = SarifWriter()

        val diagnostic = Diagnostic(
            Range(Position(5, 10), Position(5, 20)),
            "Empty class detected",
        ).apply {
            severity = DiagnosticSeverity.Information
            source = "codenarc"
            code = Either.forLeft("EmptyClass")
        }

        writer.addDiagnostic("Test.groovy", diagnostic)

        val json = writer.toJson(prettyPrint = false)

        // Verify it's valid JSON by parsing it
        val parsed = Json.parseToJsonElement(json)
        assertNotNull(parsed)

        // Verify key fields are present
        assertTrue(json.contains("\"version\":\"2.1.0\""), "Expected version. JSON: $json")
        assertTrue(json.contains("\"ruleId\":\"EmptyClass\""), "Expected ruleId. JSON: $json")
        // SARIF level should be "note" for Information severity
        assertTrue(
            json.contains("\"level\":\"note\""),
            "Expected level 'note' for Information severity. JSON: $json",
        )
    }

    @Test
    fun `should handle multiple diagnostics across files`() {
        val writer = SarifWriter()

        val diag1 = Diagnostic(
            Range(Position(0, 0), Position(0, 5)),
            "Warning 1",
        ).apply {
            severity = DiagnosticSeverity.Warning
            code = Either.forLeft("Rule1")
        }

        val diag2 = Diagnostic(
            Range(Position(10, 0), Position(10, 5)),
            "Error 1",
        ).apply {
            severity = DiagnosticSeverity.Error
            code = Either.forLeft("Rule2")
        }

        writer.addDiagnostic("file1.groovy", diag1)
        writer.addDiagnostic("file2.groovy", diag2)

        val output = writer.build()

        assertEquals(2, output.runs.first().results.size)

        val results = output.runs.first().results
        assertTrue(results.any { it.ruleId == "Rule1" && it.level == SarifLevel.WARNING })
        assertTrue(results.any { it.ruleId == "Rule2" && it.level == SarifLevel.ERROR })
    }

    @Test
    fun `should register custom rules`() {
        val writer = SarifWriter()

        val rule = SarifRule(
            id = "CustomRule",
            name = "Custom Rule Name",
            shortDescription = SarifMessage(text = "A custom rule for testing"),
            helpUri = "https://example.com/custom-rule",
            defaultConfiguration = SarifRuleConfiguration(level = SarifLevel.WARNING),
        )

        writer.registerRule(rule)

        val diagnostic = Diagnostic(
            Range(Position(0, 0), Position(0, 5)),
            "Custom rule violation",
        ).apply {
            severity = DiagnosticSeverity.Warning
            code = Either.forLeft("CustomRule")
        }

        writer.addDiagnostic("test.groovy", diagnostic)

        val output = writer.build()
        val rules = output.runs.first().tool.driver.rules

        assertNotNull(rules)
        val customRule = rules.find { it.id == "CustomRule" }
        assertNotNull(customRule)
        assertEquals("Custom Rule Name", customRule.name)
        assertEquals("https://example.com/custom-rule", customRule.helpUri)
    }

    @Test
    fun `should extract rule ID from different diagnostic formats`() {
        val writer = SarifWriter()

        // Diagnostic with code as String
        val diagWithCode = Diagnostic(
            Range(Position(0, 0), Position(0, 5)),
            "Message",
        ).apply {
            severity = DiagnosticSeverity.Warning
            code = Either.forLeft("SpecificRule")
        }

        // Diagnostic without code but with source
        val diagWithoutCode = Diagnostic(
            Range(Position(0, 0), Position(0, 5)),
            "Message",
        ).apply {
            severity = DiagnosticSeverity.Error
            source = "groovy-compiler"
        }

        writer.addDiagnostic("file.groovy", diagWithCode)
        writer.addDiagnostic("file.groovy", diagWithoutCode)

        val results = writer.build().runs.first().results

        assertEquals("SpecificRule", results[0].ruleId)
        assertEquals("CompilationError", results[1].ruleId)
    }
}
