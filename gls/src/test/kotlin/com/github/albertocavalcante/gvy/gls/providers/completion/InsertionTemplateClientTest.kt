// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.dsl.completion.GroovyCompletions
import com.github.albertocavalcante.gvy.gls.dsl.completion.InsertionTemplate
import com.github.albertocavalcante.gvy.gls.dsl.completion.MethodCompletionBuilder
import com.github.albertocavalcante.gvy.gls.dsl.completion.MethodSignatureKey
import com.github.albertocavalcante.gvy.gls.dsl.completion.TemplateCompletionItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.lsp4j.InsertReplaceEdit
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class InsertionTemplateClientTest {
    private val range = Range(Position(0, 1), Position(0, 3))

    @Test
    fun `plain client receives alternative in text edit and preserves method identity`() {
        val signature = MethodSignatureKey("example")
        val item = TemplateCompletionItem(InsertionTemplate("name(\$1)", "name()")).apply {
            label = "name"
            data = signature
            insertText = "name(\$1)"
            insertTextFormat = InsertTextFormat.Snippet
            textEdit = Either.forLeft(TextEdit(range, insertText))
        }

        listOf(item).forClient(labelDetailsSupport = true, snippetSupport = false)

        assertEquals("name()", item.insertText)
        assertEquals("name()", item.textEdit.left.newText)
        assertEquals(range, item.textEdit.left.range)
        assertEquals(signature, item.data)
        assertEquals(InsertTextFormat.PlainText, item.insertTextFormat)
    }

    @Test
    fun `wire response preserves completion data without exposing the template`() {
        val item = TemplateCompletionItem(InsertionTemplate("name(\$1)", "name()")).apply {
            label = "name"
            data = MethodSignatureKey("example")
            insertText = "name(\$1)"
            insertTextFormat = InsertTextFormat.Snippet
        }
        val response = ResponseMessage().apply {
            id = "1"
            result = listOf(item)
        }

        val json = Json.parseToJsonElement(MessageJsonHandler(emptyMap()).serialize(response)).jsonObject
        val serialized = json.getValue("result").jsonArray.single().jsonObject
        assertEquals("name", serialized.getValue("label").jsonPrimitive.content)
        assertEquals("name(\$1)", serialized.getValue("insertText").jsonPrimitive.content)
        assertEquals("example", serialized.getValue("data").jsonObject.getValue("signature").jsonPrimitive.content)
        assertFalse("insertionTemplate" in serialized)
    }

    @Test
    fun `plain client receives alternative in insert replace edit`() {
        val item = TemplateCompletionItem(InsertionTemplate("name(\$1)", "name()")).apply {
            label = "name"
            insertTextFormat = InsertTextFormat.Snippet
            textEdit = Either.forRight(InsertReplaceEdit("name(\$1)", range, range))
        }

        listOf(item).forClient(labelDetailsSupport = true, snippetSupport = false)

        assertEquals("name()", item.textEdit.right.newText)
        assertEquals(range, item.textEdit.right.replace)
    }

    @Test
    fun `groovy keyword and method templates have plain alternatives`() {
        val items = GroovyCompletions.basic().forClient(labelDetailsSupport = true, snippetSupport = false)

        assertEquals("if (condition) {\n    \n}", items.first { it.label == "if" }.insertText)
        assertEquals("println()", items.first { it.label == "println" }.insertText)
        assertEquals(emptyList(), items.filter { it.insertTextFormat == InsertTextFormat.Snippet })
    }

    @Test
    fun `plain method insertion preserves literal dollar in name`() {
        val item = MethodCompletionBuilder().build("method\$1", "void", emptyList())

        listOf(item).forClient(labelDetailsSupport = true, snippetSupport = false)

        assertEquals("method\$1()", item.insertText)
        assertEquals(InsertTextFormat.PlainText, item.insertTextFormat)
    }

    @Test
    fun `snippet client retains the original insertion`() {
        val item = TemplateCompletionItem(InsertionTemplate("name(\$1)", "name()")).apply {
            label = "name"
            insertText = "name(\$1)"
            insertTextFormat = InsertTextFormat.Snippet
        }

        listOf(item).forClient(labelDetailsSupport = true, snippetSupport = true)

        assertEquals("name(\$1)", item.insertText)
        assertEquals(InsertTextFormat.Snippet, item.insertTextFormat)
    }
}
