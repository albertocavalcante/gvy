// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.google.gson.JsonParser
import com.jayway.jsonpath.Configuration
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class SdkResponseProjectionTest {
    @TempDir
    lateinit var root: Path

    @TestFactory
    fun `arbitrary response keys are data rather than SDK union tags`(): List<DynamicTest> = listOf(
        """{"left":"authored data"}""",
        """{"right":{"nested":null,"future":true}}""",
        """{"left":[null,{"right":42}]}""",
        """{"right":[],"left":null}""",
        """{"left":null}""",
        """{"left":null,"right":null}""",
        """{"left":1,"other":2}""",
    ).map { payload ->
        dynamicTest(payload) {
            val context = execute(JsonParser.parseString(payload))
            val expected = Json.parseToJsonElement(payload)

            assertThat(context.lastResult).isEqualTo(expected)
            assertThat(context.savedResults["response"]).isEqualTo(expected)
            assertThat(context.variables["response"]).isEqualTo(expected)
        }
    }

    @Test
    fun `actual SDK left array keeps its existing convenience view`() {
        val response = Either.forLeft<List<String>, List<String>>(listOf("item"))
        val context = execute(response)

        assertThat(context.lastResult)
            .isEqualTo(Json.parseToJsonElement("""{"dataOrigin":"left","items":["item"]}"""))
    }

    @Test
    fun `actual SDK right array keeps its existing convenience view`() {
        val response = Either.forRight<List<String>, List<String>>(listOf("item"))
        val context = execute(response)

        assertThat(context.lastResult)
            .isEqualTo(Json.parseToJsonElement("""{"dataOrigin":"right","items":["item"]}"""))
    }

    @TestFactory
    fun `actual SDK non-array branches retain their payloads`(): List<DynamicTest> {
        val values: List<Pair<Any, String>> = listOf(
            "scalar" to "\"scalar\"",
            mapOf("left" to "literal data", "right" to mapOf("nested" to true)) to
                """{"left":"literal data","right":{"nested":true}}""",
        )
        return values.flatMap { (value, expected) ->
            listOf(
                "left" to Either.forLeft<Any, Any>(value),
                "right" to Either.forRight<Any, Any>(value),
            ).map { (branch, response) ->
                dynamicTest("$branch: $expected") {
                    assertThat(execute(response).lastResult).isEqualTo(Json.parseToJsonElement(expected))
                }
            }
        }
    }

    private fun execute(response: Any): ScenarioContext {
        val session = mockk<LanguageServerSession>(relaxed = true)
        every { session.endpoint.request("experimental/arbitrary-json", any()) } returns
            CompletableFuture.completedFuture(response)
        val context = ScenarioContext(
            definition = ScenarioDefinition(Scenario(name = "response-projection", steps = emptyList())),
            session = session,
            workspace = ScenarioWorkspace(root),
            jsonPathConfig = Configuration.defaultConfiguration(),
        )

        SendRequestStepExecutor().execute(
            ScenarioStep.SendRequest(method = "experimental/arbitrary-json", saveAs = "response"),
            context,
        )
        return context
    }
}
