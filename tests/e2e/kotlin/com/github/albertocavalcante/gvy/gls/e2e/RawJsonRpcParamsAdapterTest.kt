package com.github.albertocavalcante.gvy.gls.e2e

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.JsonWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.IOException
import java.io.StringWriter
import java.io.Writer

class RawJsonRpcParamsAdapterTest {
    private val adapter = GsonBuilder().registerRawJsonRpcParams().create().getAdapter(RawJsonRpcParams::class.java)

    @TestFactory
    fun `encoding retains explicit null and restores the caller's writer policy`(): List<DynamicTest> =
        listOf(false, true).map { originalPolicy ->
            dynamicTest("serializeNulls=$originalPolicy") {
                val output = StringWriter()
                val writer = JsonWriter(output).apply { serializeNulls = originalPolicy }
                val payload = Json.parseToJsonElement("""{"nil":null,"numeric":1e2}""")

                adapter.write(writer, RawJsonRpcParams(payload))

                assertThat(output.toString()).isEqualTo(payload.toString())
                assertThat(writer.serializeNulls).isEqualTo(originalPolicy)
            }
        }

    @Test
    fun `a failed writer retains its prior null policy and original failure`() {
        val failure = IOException("writer unavailable")
        val output = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int): Unit = throw failure
            override fun flush() = Unit
            override fun close() = Unit
        }
        val writer = JsonWriter(output).apply { serializeNulls = false }

        assertThatThrownBy { adapter.write(writer, RawJsonRpcParams(JsonPrimitive("value"))) }.isSameAs(failure)

        assertThat(writer.serializeNulls).isFalse()
    }

    @Test
    fun `invalid JSON numbers fail preflight without touching the writer`() {
        val output = StringWriter()
        val writer = JsonWriter(output).apply { serializeNulls = false }

        assertThatThrownBy { adapter.write(writer, RawJsonRpcParams(JsonPrimitive(Double.NaN))) }
            .isInstanceOf(JsonSyntaxException::class.java)

        assertThat(output.toString()).isEmpty()
        assertThat(writer.serializeNulls).isFalse()
    }

    @Test
    fun `the carrier snapshots validated text before mutable tree backing changes`() {
        val entries = mutableMapOf("explicit" to JsonNull)
        val params = RawJsonRpcParams(JsonObject(entries))
        entries.clear()

        val output = StringWriter()
        adapter.write(JsonWriter(output), params)

        assertThat(output.toString()).isEqualTo("""{"explicit":null}""")
    }
}
