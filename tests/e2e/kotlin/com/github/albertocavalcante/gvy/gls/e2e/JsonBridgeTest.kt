package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test

class JsonBridgeTest {
    @Test
    fun `null remains JSON null`() {
        assertThat(JsonBridge.wrapJavaObject(null)).isEqualTo(JsonNull)
    }

    @Test
    fun `JSON-native values keep their structure and types`() {
        val value = mapOf(
            "name" to "demo",
            "enabled" to true,
            "count" to 1,
            "items" to listOf(null, mapOf("name" to "nested")),
        )

        assertThat(JsonBridge.wrapJavaObject(value)).isEqualTo(
            Json.parseToJsonElement(
                """{"name":"demo","enabled":true,"count":1,"items":[null,{"name":"nested"}]}""",
            ),
        )
    }

    @Test
    fun `LSP locations remain structured JSON objects`() {
        val location = Location("file:///Fixture.groovy", Range(Position(0, 1), Position(1, 2)))

        assertThat(JsonBridge.wrapJavaObject(location)).isEqualTo(
            Json.parseToJsonElement(
                """
                {
                  "uri": "file:///Fixture.groovy",
                  "range": {
                    "start": {"line": 0, "character": 1},
                    "end": {"line": 1, "character": 2}
                  }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `unsupported objects preserve serialization failures`() {
        assertThatThrownBy { JsonBridge.wrapJavaObject(String::class.java) }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("java.lang.Class")
    }

    @Test
    fun `unsupported nested values fail the whole conversion`() {
        val value = mapOf("items" to listOf(String::class.java))

        assertThatThrownBy { JsonBridge.wrapJavaObject(value) }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("java.lang.Class")
    }
}
