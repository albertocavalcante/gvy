package com.github.albertocavalcante.gvy.diagnostics.sarif

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SarifLocationTest {
    @Test
    fun `text result declares UTF16 columns and retains positions after emoji across CRLF`() {
        val source = "first\r\n😀x\r\n"
        val start = source.lines()[1].indexOf('x')
        val writer = SarifWriter()
        writer.addDiagnostic(
            "src/Test.groovy",
            Diagnostic(Range(Position(1, start), Position(1, start + 1)), "message"),
        )
        val run = Json.parseToJsonElement(writer.toJson()).jsonObject["runs"]!!.jsonArray[0].jsonObject
        assertEquals("utf16CodeUnits", run["columnKind"]?.jsonPrimitive?.content)
        val region = run["results"]!!.jsonArray[0].jsonObject["locations"]!!.jsonArray[0]
            .jsonObject["physicalLocation"]!!.jsonObject["region"]!!.jsonObject
        assertEquals("2", region["startLine"]!!.jsonPrimitive.content)
        assertEquals("3", region["startColumn"]!!.jsonPrimitive.content)
        assertEquals("2", region["endLine"]!!.jsonPrimitive.content)
        assertEquals("4", region["endColumn"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty writer run declares UTF16 columns consistently`() {
        val run = Json.parseToJsonElement(SarifWriter().toJson()).jsonObject["runs"]!!.jsonArray[0].jsonObject
        assertEquals("utf16CodeUnits", run["columnKind"]?.jsonPrimitive?.content)
        assertEquals(0, run["results"]!!.jsonArray.size)
    }

    @Test
    fun `generic run can omit column kind for non text artifacts`() {
        val run = SarifRun(tool = SarifTool(driver = SarifDriver(name = "test")))
        val json = Json.parseToJsonElement(Json.encodeToString(run)).jsonObject
        assertFalse("columnKind" in json)
    }
}
