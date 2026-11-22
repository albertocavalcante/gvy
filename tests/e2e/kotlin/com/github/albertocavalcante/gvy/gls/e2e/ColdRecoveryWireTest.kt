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

class ColdRecoveryWireTest {
    private val source = ColdRecoverySource("file:///Probe.groovy", 7, "class Before {}\n")
    private val params = json("""{"textDocument":{"uri":"file:///Probe.groovy"}}""").jsonObject
    private val result = json("""{"items":[],"version":7,"source":"provider"}""")
    private val request = ColdRecoveryRequest("textDocument/hover", params, result)
    private val open = """{"jsonrpc":"2.0","method":"textDocument/didOpen",""" +
        """"params":{"textDocument":{""" +
        """"uri":"file:///Probe.groovy","languageId":"groovy","version":7,"text":"class Before {}\n"}}}"""
    private val outgoing = """{"jsonrpc":"2.0","id":11,"method":"textDocument/hover","params":$params}"""
    private val response = """{"jsonrpc":"2.0","id":11,"result":$result}"""

    @Test
    fun `binds actual IDs and preserves source and response metadata`() {
        val evidence = ColdRecoveryWire.bind(
            snapshot(listOf(open, outgoing), listOf(response)),
            source,
            listOf(request),
        )
        val binding = evidence.getValue("bindings").jsonArray.single().jsonObject
        assertThat(binding.getValue("requestId").jsonPrimitive.content).isEqualTo("11")
        assertThat(binding.getValue("result")).isEqualTo(result)
        assertThat(binding.getValue("clientVersion").jsonPrimitive.content).isEqualTo("7")
        assertThat(evidence.getValue("source").jsonObject.getValue("sha256").jsonPrimitive.content).hasSize(64)
    }

    @Test
    fun `rejects mismatched source association and changes to the cold document`() {
        val changes = listOf(
            open.replace("Probe.groovy", "Other.groovy"),
            open.replace("Before", "Stale"),
            open.replace("\"version\":7", "\"version\":8"),
            frame(open) + frame(open),
            frame(open) + frame(
                """{"jsonrpc":"2.0","method":"textDocument/didChange",""" +
                    """"params":{"textDocument":{"uri":"file:///Probe.groovy","version":8},"contentChanges":[]}}""",
            ),
        )
        changes.forEach { changedOpen ->
            val sent = if (changedOpen.startsWith("Content-Length:")) changedOpen else frame(changedOpen)
            assertThatThrownBy {
                ColdRecoveryWire.bind(snapshotRaw(sent + frame(outgoing), frame(response)), source, listOf(request))
            }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `rejects request order and missing or mutated response metadata`() {
        val other = ColdRecoveryRequest("textDocument/documentSymbol", params, json("null"))
        val otherRequest = """{"jsonrpc":"2.0","id":12,"method":"textDocument/documentSymbol","params":$params}"""
        val otherResponse = """{"jsonrpc":"2.0","id":12,"result":null}"""
        assertThatThrownBy {
            val evidence = snapshot(listOf(open, otherRequest, outgoing), listOf(response, otherResponse))
            ColdRecoveryWire.bind(evidence, source, listOf(request, other))
        }.isInstanceOf(IllegalArgumentException::class.java)
        listOf(
            response.replace("\"id\":11", "\"id\":12"),
            response.replace("\"version\":7", "\"version\":8"),
            """{"jsonrpc":"2.0","id":11,"error":{"code":-1,"message":"failure"}}""",
            frame(response) + frame(response),
        ).forEach { changed ->
            val received = if (changed.startsWith("Content-Length:")) changed else frame(changed)
            assertThatThrownBy {
                ColdRecoveryWire.bind(snapshotRaw(frame(open) + frame(outgoing), received), source, listOf(request))
            }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `rejects requests consistently targeting a different source or version`() {
        val wrongParams = json("""{"textDocument":{"uri":"file:///Other.groovy"}}""").jsonObject
        val wrongRequest = request.copy(params = wrongParams)
        val wrongWire = outgoing.replace(params.toString(), wrongParams.toString())
        assertThatThrownBy {
            ColdRecoveryWire.bind(snapshot(listOf(open, wrongWire), listOf(response)), source, listOf(wrongRequest))
        }.isInstanceOf(IllegalArgumentException::class.java)

        val terminalParams = json("""{"uri":"file:///Probe.groovy","version":8}""").jsonObject
        val terminal = ColdRecoveryRequest("groovy/awaitDiagnostics", terminalParams, result)
        val terminalWire = """{"jsonrpc":"2.0","id":11,"method":"groovy/awaitDiagnostics","params":$terminalParams}"""
        assertThatThrownBy {
            ColdRecoveryWire.bind(snapshot(listOf(open, terminalWire), listOf(response)), source, listOf(terminal))
        }.isInstanceOf(IllegalArgumentException::class.java)

        val correctTerminalParams = json("""{"uri":"file:///Probe.groovy","version":7}""").jsonObject
        val correctTerminal = terminal.copy(params = correctTerminalParams)
        val correctWire = terminalWire.replace(terminalParams.toString(), correctTerminalParams.toString())
        ColdRecoveryWire.bind(snapshot(listOf(open, correctWire), listOf(response)), source, listOf(correctTerminal))
        val wrongUriParams = json("""{"uri":"file:///Other.groovy","version":7}""").jsonObject
        val wrongUriWire = terminalWire.replace(terminalParams.toString(), wrongUriParams.toString())
        assertThatThrownBy {
            ColdRecoveryWire.bind(
                snapshot(listOf(open, wrongUriWire), listOf(response)),
                source,
                listOf(terminal.copy(params = wrongUriParams)),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        val unknown = request.copy(method = "test/unknown")
        val unknownWire = outgoing.replace("textDocument/hover", "test/unknown")
        assertThatThrownBy {
            ColdRecoveryWire.bind(snapshot(listOf(open, unknownWire), listOf(response)), source, listOf(unknown))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ColdRecoveryWire.bind(snapshot(listOf(open), emptyList()), source, emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a closed document cannot certify a later feature request`() {
        val close = """{"jsonrpc":"2.0","method":"textDocument/didClose","params":$params}"""
        assertThatThrownBy {
            ColdRecoveryWire.bind(snapshot(listOf(open, close, outgoing), listOf(response)), source, listOf(request))
        }.isInstanceOf(IllegalArgumentException::class.java)
        ColdRecoveryWire.bind(snapshot(listOf(open, outgoing, close), listOf(response)), source, listOf(request))
    }

    @Test
    fun `rejects incomplete frames gaps and unconfirmed EOF`() {
        val valid = snapshot(listOf(open, outgoing), listOf(response))
        val broken = snapshotRaw(frame(open) + frame(outgoing), frame(response).dropLast(1))
        val gap = WireEvidenceSnapshot(
            valid.received,
            stream(frame(open) + frame(outgoing), gaps = setOf(WireEvidenceGap.BYTE_LIMIT)),
        )
        val noEof = WireEvidenceSnapshot(stream(frame(response), eof = false), valid.transmitted)
        listOf(broken, gap, noEof).forEach { evidence ->
            assertThatThrownBy { ColdRecoveryWire.bind(evidence, source, listOf(request)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

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
    private fun json(value: String) = Json.parseToJsonElement(value)
}
