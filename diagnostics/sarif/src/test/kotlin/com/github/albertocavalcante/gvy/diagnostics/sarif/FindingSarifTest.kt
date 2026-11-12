package com.github.albertocavalcante.gvy.diagnostics.sarif

import com.github.albertocavalcante.gvy.findings.Finding
import com.github.albertocavalcante.gvy.findings.FindingCode
import com.github.albertocavalcante.gvy.findings.FindingSeverity
import com.github.albertocavalcante.gvy.source.SourceLocation
import com.github.albertocavalcante.gvy.source.Utf16Position
import com.github.albertocavalcante.gvy.source.Utf16Range
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FindingSarifTest {
    @Test
    fun `finding renders exact URI and exclusive UTF16 range in serialized SARIF`() {
        val range = Utf16Range(Utf16Position(1, 2), Utf16Position(2, 0))
        val finding = Finding(
            location = SourceLocation("file:///work/A%20B.groovy", range),
            message = "found after emoji",
            severity = FindingSeverity.ERROR,
            source = "producer",
            code = FindingCode.Text("Rule"),
        )
        val writer = SarifWriter()
        writer.addFinding(finding)
        val run = Json.parseToJsonElement(writer.toJson()).jsonObject["runs"]!!.jsonArray[0].jsonObject
        assertEquals("utf16CodeUnits", run["columnKind"]!!.jsonPrimitive.content)
        val result = run["results"]!!.jsonArray[0].jsonObject
        assertEquals("Rule", result["ruleId"]!!.jsonPrimitive.content)
        assertEquals("error", result["level"]!!.jsonPrimitive.content)
        val physical = result["locations"]!!.jsonArray[0].jsonObject["physicalLocation"]!!.jsonObject
        assertEquals(
            "file:///work/A%20B.groovy",
            physical["artifactLocation"]!!.jsonObject["uri"]!!.jsonPrimitive.content,
        )
        val region = physical["region"]!!.jsonObject
        assertEquals("2", region["startLine"]!!.jsonPrimitive.content)
        assertEquals("3", region["startColumn"]!!.jsonPrimitive.content)
        assertEquals("3", region["endLine"]!!.jsonPrimitive.content)
        assertEquals("1", region["endColumn"]!!.jsonPrimitive.content)
        assertFalse("fixes" in result)
        assertFalse("fingerprints" in result)
    }

    @Test
    fun `severity and numeric code use explicit SARIF projection policy`() {
        val cases = listOf(
            FindingSeverity.ERROR to SarifLevel.ERROR,
            FindingSeverity.WARNING to SarifLevel.WARNING,
            FindingSeverity.INFORMATION to SarifLevel.NOTE,
            FindingSeverity.HINT to SarifLevel.NOTE,
            null to SarifLevel.WARNING,
        )
        val writer = SarifWriter()
        for ((severity, _) in cases) {
            writer.addFinding(Finding(null, "message", severity, "groovy-compiler", FindingCode.Number(42)))
        }
        val results = writer.build().runs.single().results
        assertEquals(cases.map { it.second }, results.map { it.level })
        assertEquals(List(cases.size) { "42" }, results.map { it.ruleId })
        assertNull(results.first().locations)
    }

    @Test
    fun `unknown range does not invent a region`() {
        val writer = SarifWriter()
        writer.addFinding(Finding(SourceLocation("file:///A.groovy", null), "message", null, null, null))
        val result = writer.build().runs.single().results.single()
        assertEquals("UnknownRule", result.ruleId)
        assertNull(result.locations!!.single().physicalLocation!!.region)
    }

    @Test
    fun `maximum positions remain positive one based numeric values`() {
        val writer = SarifWriter()
        writer.addFinding(
            Finding(
                SourceLocation(
                    "file:///A.groovy",
                    Utf16Range(
                        Utf16Position(Int.MAX_VALUE - 1, Int.MAX_VALUE - 1),
                        Utf16Position(Int.MAX_VALUE, Int.MAX_VALUE),
                    ),
                ),
                "message",
                null,
                null,
                null,
            ),
        )
        val json = Json.parseToJsonElement(writer.toJson()).jsonObject
        val region = json["runs"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject["locations"]!!
            .jsonArray[0].jsonObject["physicalLocation"]!!.jsonObject["region"]!!.jsonObject
        assertEquals("2147483647", region["startLine"]!!.jsonPrimitive.content)
        assertEquals("2147483647", region["startColumn"]!!.jsonPrimitive.content)
        assertEquals("2147483648", region["endLine"]!!.jsonPrimitive.content)
        assertEquals("2147483648", region["endColumn"]!!.jsonPrimitive.content)
        assertFalse(region["endLine"]!!.jsonPrimitive.isString)
    }

    @Test
    fun `valid URI references retain spelling and Unicode is encoded as ASCII`() {
        val cases = mapOf(
            "" to "",
            "src/A%2fb.groovy" to "src/A%2fb.groovy",
            "relative/a?query=x#part" to "relative/a?query=x#part",
            "file:///work/é.groovy" to "file:///work/%C3%A9.groovy",
            "file:///work/e\u0301.groovy" to "file:///work/e%CC%81.groovy",
            "src/%2f-😀-e\u0301.groovy" to "src/%2f-%F0%9F%98%80-e%CC%81.groovy",
        )
        for ((reference, expected) in cases) {
            val writer = SarifWriter()
            writer.addFinding(Finding(SourceLocation(reference, null), "message", null, null, null))
            val actual = writer.build().runs.single().results.single().locations!!.single()
                .physicalLocation!!.artifactLocation.uri
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `invalid URI does not add a result or register a rule`() {
        val writer = SarifWriter()
        for (invalid in listOf("bad%xy", "file:///bad path", "file:///\uD800", "file:///\uDC00")) {
            assertFailsWith<IllegalArgumentException> {
                writer.addFinding(
                    Finding(SourceLocation(invalid, null), "message", null, null, FindingCode.Text("bad")),
                )
            }
        }
        assertEquals(emptyList(), writer.build().runs.single().results)
        assertNull(writer.build().runs.single().tool.driver.rules)
        writer.addFinding(Finding(null, "valid", null, null, FindingCode.Text("good")))
        assertEquals(listOf("good"), writer.build().runs.single().tool.driver.rules!!.map { it.id })
    }

    @Test
    fun `numeric and textual code project to same string rule identifier`() {
        val writer = SarifWriter()
        writer.addFinding(Finding(null, "numeric", null, null, FindingCode.Number(42)))
        writer.addFinding(Finding(null, "text", null, null, FindingCode.Text("42")))
        assertEquals(listOf("42", "42"), writer.build().runs.single().results.map { it.ruleId })
    }

    @Test
    fun `legacy diagnostic widens maximum coordinates and retains numeric code fallback`() {
        val writer = SarifWriter()
        val diagnostic = Diagnostic(
            Range(Position(Int.MAX_VALUE - 1, Int.MAX_VALUE - 1), Position(Int.MAX_VALUE, Int.MAX_VALUE)),
            "message",
        ).apply {
            source = "groovy-compiler"
            code = Either.forRight(42)
        }
        writer.addDiagnostic("legacy path.groovy", diagnostic)
        val json = Json.parseToJsonElement(writer.toJson()).jsonObject
        val result = json["runs"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject
        assertEquals("CompilationError", result["ruleId"]!!.jsonPrimitive.content)
        val physical = result["locations"]!!.jsonArray[0].jsonObject["physicalLocation"]!!.jsonObject
        assertEquals(
            "legacy path.groovy",
            physical["artifactLocation"]!!.jsonObject["uri"]!!.jsonPrimitive.content,
        )
        val region = physical["region"]!!.jsonObject
        assertEquals("2147483647", region["startLine"]!!.jsonPrimitive.content)
        assertEquals("2147483647", region["startColumn"]!!.jsonPrimitive.content)
        assertEquals("2147483648", region["endLine"]!!.jsonPrimitive.content)
        assertEquals("2147483648", region["endColumn"]!!.jsonPrimitive.content)
    }
}
