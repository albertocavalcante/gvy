// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.e2e.JsonBridge.toKotlinxJsonElement
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.InitializeParams
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class RawJsonRpcBridgeTest {
    @TestFactory
    fun `received JSON trees retain missing fields and explicit nested nulls`(): List<DynamicTest> = listOf(
        "{}",
        """{"explicit":null,"future":{"nil":null,"flag":false}}""",
        "[null,{\"explicit\":null}]",
    ).map { payload ->
        dynamicTest(payload) {
            val received = JsonParser.parseString(payload)
            val expected = Json.parseToJsonElement(payload)

            assertThat(JsonBridge.wrapJavaObject(received)).isEqualTo(expected)
            assertThat(JsonBridge.toJsonElement(received)).isEqualTo(expected)
        }
    }

    @Test
    fun `typed SDK objects retain their existing serialization policy`() {
        val typed = InitializeParams()
        val ordinarySdkView = Gson().toJsonTree(typed).toKotlinxJsonElement()

        assertThat(JsonBridge.toJsonElement(typed)).isEqualTo(ordinarySdkView)
    }
}
