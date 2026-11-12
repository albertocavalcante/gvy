package com.github.albertocavalcante.gvy.gls.providers.testing

import com.github.albertocavalcante.reports.coverage.parsers.JacocoParser
import com.google.gson.JsonParser
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverageWireTest {
    @Test
    fun `JaCoCo instruction counts are not serialized as execution counts`(@TempDir workspace: Path) {
        val source = workspace.resolve("src/main/groovy/example/Example.groovy").toFile()
        source.parentFile.mkdirs()
        source.writeText("// source")
        val report = workspace.resolve("target/site/jacoco/jacoco.xml").toFile()
        report.parentFile.mkdirs()
        report.writeText(
            """
            <report name="test"><package name="example"><sourcefile name="Example.groovy">
              <line nr="1" mi="0" ci="7" mb="1" cb="1"/>
              <line nr="2" mi="4" ci="0" mb="0" cb="0"/>
            </sourcefile></package></report>
            """.trimIndent(),
        )
        val response = ResponseMessage().apply {
            id = "1"
            result = JacocoParser.parseWorkspace(workspace.toFile())
        }
        val json = JsonParser.parseString(MessageJsonHandler(emptyMap()).serialize(response)).asJsonObject
        val file = json["result"].asJsonObject["files"].asJsonArray[0].asJsonObject
        assertEquals(source.toURI().toString(), file["uri"].asString)
        val lines = file["lines"].asJsonArray
        assertEquals(2, lines.size())
        assertTrue(lines[0].asJsonObject["covered"].asBoolean)
        assertFalse(lines[1].asJsonObject["covered"].asBoolean)
        assertFalse(lines[0].asJsonObject.has("hitCount"))
        assertFalse(lines[1].asJsonObject.has("hitCount"))
    }
}
