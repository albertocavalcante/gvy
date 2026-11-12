// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.testing

import com.github.albertocavalcante.reports.coverage.parsers.JacocoParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        source.writeText("// covered\n// uncovered\n")
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
        val json = Json.parseToJsonElement(MessageJsonHandler(emptyMap()).serialize(response)).jsonObject
        val file = json.getValue("result").jsonObject.getValue("files").jsonArray.single().jsonObject
        assertEquals(source.toURI().toString(), file.getValue("uri").jsonPrimitive.content)
        val lines = file.getValue("lines").jsonArray
        assertEquals(2, lines.size)
        val covered = lines[0].jsonObject.getValue("covered").jsonPrimitive
        val uncovered = lines[1].jsonObject.getValue("covered").jsonPrimitive
        assertFalse(covered.isString)
        assertFalse(uncovered.isString)
        assertTrue(covered.boolean)
        assertFalse(uncovered.boolean)
        assertFalse("hitCount" in lines[0].jsonObject)
        assertFalse("hitCount" in lines[1].jsonObject)
    }
}
