// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class WarmRecoveryWireTest {
    private val uri = "file:///Probe.groovy"
    private val params = json("""{"textDocument":{"uri":"$uri"},"position":{"line":0,"character":0}}""").jsonObject
    private val result = json("""{"items":[]}""")
    private val sources = listOf(
        ColdRecoverySource(uri, 1, "class Before {}\n"),
        ColdRecoverySource(uri, 2, "class Before { x. }\n"),
        ColdRecoverySource(uri, 3, "class Before { x.me }\n"),
        ColdRecoverySource(uri, 4, "class After {}\n"),
    )
    private val requests = sources.map { WarmRecoveryRequest(it, "textDocument/hover", params, result) }

    @Test
    fun `binds repeated requests to the active full text revision for LF and CRLF`() {
        listOf("\n", "\r\n").forEach { newline ->
            val states = sources.map { it.copy(text = it.text.replace("\n", newline)) }
            val expected = requests.mapIndexed { index, request -> request.copy(source = states[index]) }
            val sent = history(states)
            val bound = WarmRecoveryWire.bind(snapshot(sent, responses()), states, expected)
            val bindings = bound.getValue("bindings").jsonArray.map { it.jsonObject }
            assertThat(bindings.map { it.getValue("requestId").jsonPrimitive.content })
                .containsExactly("1", "2", "3", "4")
            assertThat(bindings.map { it.getValue("clientVersion").jsonPrimitive.content })
                .containsExactly("1", "2", "3", "4")
            assertThat(bindings.map { it.getValue("sourceHash").jsonPrimitive.content }.distinct()).hasSize(4)
        }
    }

    @Test
    fun `rejects unlisted or mismatched source transitions and incremental changes`() {
        val valid = history(sources)
        val changes = listOf(
            valid.toMutableList().apply { this[0] = open(sources[0].copy(text = "stale")) },
            valid.toMutableList().apply { this[2] = change(sources[1].copy(version = 9)) },
            valid.toMutableList().apply { this[2] = change(sources[1].copy(text = "stale")) },
            valid.toMutableList().apply { this[2] = change(sources[1]).replace(uri, "file:///Other.groovy") },
            valid.toMutableList().apply {
                this[2] = change(sources[1]).replace("]}}", ", {\"text\":\"second\"}]}}")
            },
            valid.toMutableList().apply { this[2] = change(sources[1]).replace("\"text\":", "\"range\":{},\"text\":") },
            valid.toMutableList().apply {
                this[2] = change(sources[1]).replace("\"text\":", "\"rangeLength\":0,\"text\":")
            },
            valid.toMutableList().apply { add(2, change(sources[1].copy(version = 5))) },
            valid.toMutableList().apply { removeAt(2) },
            valid.toMutableList().apply { add(0, change(sources[1])) },
        )
        changes.forEach { sent -> assertRejected(sent, responses()) }
        assertRejected(
            valid,
            responses(),
            requests.mapIndexed { index, request ->
                if (index == 1) request.copy(source = sources[0]) else request
            },
        )
    }

    @Test
    fun `rejects invalid lifecycle and reused version reopen`() {
        val valid = history(sources)
        val close = """{"method":"textDocument/didClose","params":$params}"""
        assertRejected(valid.toMutableList().apply { add(2, close) }, responses())
        assertRejected(
            valid.toMutableList().apply {
                add(2, close)
                add(3, open(sources[0]))
            },
            responses(),
        )
        assertRejected(valid.toMutableList().apply { this[2] = change(sources[1].copy(version = 1)) }, responses())
        assertRejected(valid, responses(), requests.map { it.copy(incarnation = 1) })
    }

    @Test
    fun `rejects duplicate identifiers response mutations and wrong request association`() {
        val valid = history(sources)
        assertRejected(valid.map { it.replace("\"id\":2", "\"id\":1") }, responses())
        assertRejected(valid, responses() + responses().first())
        assertRejected(valid, responses().dropLast(1))
        assertRejected(valid, responses().map { it.replace("\"result\":", "\"error\":") })
        assertRejected(valid, responses(), requests.reversed())
        assertRejected(valid, responses().map { it.replace("\"items\":[]", "\"items\":[1]") })
        val wrong = json("""{"textDocument":{"uri":"file:///Other.groovy"}}""").jsonObject
        assertRejected(valid, responses(), requests.map { it.copy(params = wrong) })
        val diagnostics = requests[0].copy(
            method = "groovy/awaitDiagnostics",
            params = json("""{"uri":"$uri","version":2}""").jsonObject,
        )
        val sent = valid.toMutableList().apply { this[1] = outgoing(1, diagnostics) }
        assertRejected(sent, responses(), listOf(diagnostics) + requests.drop(1))
    }

    @Test
    fun `rejects unlisted feature requests while allowing setup and client responses`() {
        val valid = history(sources)
        val definition = """{"id":99,"method":"textDocument/definition","params":$params}"""
        assertRejected(valid.toMutableList().apply { add(2, definition) }, responses())

        val setup = listOf(
            """{"id":90,"method":"initialize","params":{}}""",
            """{"id":91,"method":"groovy/runtimeFacts","params":{}}""",
            """{"method":"initialized","params":{}}""",
            """{"id":92,"result":null}""",
        )
        val shutdown = """{"id":93,"method":"shutdown"}"""
        WarmRecoveryWire.bind(snapshot(setup + valid + shutdown, responses()), sources, requests)
    }

    @Test
    fun `rejects missing malformed and out of bounds request coordinates`() {
        listOf(
            null,
            """{"line":-1,"character":0}""",
            """{"line":0,"character":999}""",
            """{"line":0.5,"character":0}""",
            """{"line":0}""",
        ).forEach { position ->
            val positionField = if (position == null) "" else ",\"position\":$position"
            val badParams = json("""{"textDocument":{"uri":"$uri"}$positionField}""").jsonObject
            val bad = requests.first().copy(params = badParams)
            val sent = history(sources).toMutableList().apply { this[1] = outgoing(1, bad) }
            assertRejected(sent, responses(), listOf(bad) + requests.drop(1))
        }
    }

    @Test
    fun `checks positions in UTF-16 and excludes CRLF terminators`() {
        val source = sources.first().copy(text = "x😀y\r\nz")
        fun bindAt(line: Int, character: Int) {
            val positioned = json(
                """{"textDocument":{"uri":"$uri"},"position":{"line":$line,"character":$character}}""",
            ).jsonObject
            val request = requests.first().copy(source = source, params = positioned)
            WarmRecoveryWire.bind(
                snapshot(listOf(open(source), outgoing(1, request)), responses().take(1)),
                listOf(source),
                listOf(request),
            )
        }
        bindAt(0, 3)
        bindAt(1, 1)
        assertThatThrownBy { bindAt(0, 2) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { bindAt(0, 5) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `binds an executed prefix without requiring later planned states`() {
        val prefix = sources.take(2)
        val evidence = WarmRecoveryWire.bind(snapshot(history(prefix), responses().take(2)), prefix, requests.take(2))
        assertThat(evidence.getValue("bindings").jsonArray).hasSize(2)
    }

    @Test
    fun `rejects truncated gapped and unfinished wire evidence`() {
        val sent = history(sources).joinToString("") { frame(it) }
        val received = responses().joinToString("") { frame(it) }
        val invalid = listOf(
            snapshotRaw(sent, received.dropLast(1)),
            WireEvidenceSnapshot(stream(received, eof = false), stream(sent, eof = false)),
            WireEvidenceSnapshot(stream(received), stream(sent, eof = false, gaps = setOf(WireEvidenceGap.BYTE_LIMIT))),
        )
        invalid.forEach { evidence ->
            assertThatThrownBy { WarmRecoveryWire.bind(evidence, sources, requests) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun assertRejected(
        sent: List<String>,
        received: List<String>,
        expected: List<WarmRecoveryRequest> = requests,
    ) {
        assertThatThrownBy { WarmRecoveryWire.bind(snapshot(sent, received), sources, expected) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun history(states: List<ColdRecoverySource>): List<String> = buildList {
        states.forEachIndexed { index, source ->
            add(if (index == 0) open(source) else change(source))
            add(outgoing(index + 1, requests[index]))
        }
    }

    private fun open(source: ColdRecoverySource) = """{"method":"textDocument/didOpen","params":{"textDocument":{""" +
        """"uri":"$uri","version":${source.version},"text":${quote(source.text)}}}}"""

    private fun change(source: ColdRecoverySource) =
        """{"method":"textDocument/didChange","params":{"textDocument":{""" +
            """"uri":"$uri","version":${source.version}},"contentChanges":[{"text":${quote(source.text)}}]}}"""

    private fun outgoing(id: Int, request: WarmRecoveryRequest) =
        """{"id":$id,"method":"${request.method}","params":${request.params}}"""

    private fun responses() = sources.indices.map { """{"id":${it + 1},"result":$result}""" }
    private fun quote(value: String) = kotlinx.serialization.json.JsonPrimitive(value).toString()
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun snapshot(sent: List<String>, received: List<String>) =
        snapshotRaw(sent.joinToString("") { frame(it) }, received.joinToString("") { frame(it) })
    private fun snapshotRaw(sent: String, received: String) =
        WireEvidenceSnapshot(stream(received), stream(sent, eof = false))
    private fun stream(
        value: String,
        eof: Boolean = true,
        gaps: Set<WireEvidenceGap> = emptySet(),
    ): WireStreamEvidence {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return WireStreamEvidence(
            Base64.getEncoder().encodeToString(bytes),
            bytes.size,
            bytes.size.toLong(),
            eof,
            0,
            gaps,
        )
    }
    private fun frame(body: String) = "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n$body"
}
