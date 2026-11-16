// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.dsl.completion

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompletionBuildersTest {

    @Test
    fun `dedupe trims detail for fields`() {
        val item1 = CompletionItem().apply {
            label = "name"
            kind = CompletionItemKind.Field
            detail = "String name"
        }
        val item2 = CompletionItem().apply {
            label = "name"
            kind = CompletionItemKind.Field
            detail = "String name "
        }

        val result = completions {
            add(item1)
            add(item2)
        }

        assertEquals(1, result.size)
    }

    @Test
    fun `dedupe ignores parentheses in return type`() {
        val item1 = CompletionItem().apply {
            label = "map"
            kind = CompletionItemKind.Method
            detail = "Function<(String) -> Unit> map(String arg)"
        }
        val item2 = CompletionItem().apply {
            label = "map"
            kind = CompletionItemKind.Method
            detail = "Function<(Int) -> Unit> map(String arg)"
        }

        val result = completions {
            add(item1)
            add(item2)
        }

        assertEquals(1, result.size)
    }

    @Test
    fun `method snippets escape snippet syntax in names and placeholders`() {
        // `$` would start a tabstop, `}` close the placeholder, `\` an escape (#1407).
        val item = MethodCompletionBuilder().build("\$run", "void", listOf("Outer\$1 x", "Weird}\\ y"))

        assertEquals("\\\$run(\${1:Outer\\\$1 x}, \${2:Weird\\}\\\\ y})", item.insertText)
        assertEquals("void \$run(Outer\$1 x, Weird}\\ y)", item.detail)
    }

    @Test
    fun `function snippets escape snippet syntax in the name`() {
        val item = FunctionCompletionBuilder().build("my\$fn")

        assertEquals("my\\\$fn(\$1)", item.insertText)
        assertEquals("void my\$fn()", item.detail)
    }

    @Test
    fun `snippet plain text defaults to the final label`() {
        val builder = CompletionBuilder()
        builder.snippet("item(\$1)")
        builder.label("finalLabel")

        val item = builder.build() as TemplateCompletionItem

        assertEquals("finalLabel", item.insertionTemplate?.plainText)
    }

    @Test
    fun `snippet preserves an explicitly empty plain alternative`() {
        val item = completion {
            snippet("item(\$1)", plainText = "")
            label("finalLabel")
        } as TemplateCompletionItem

        assertEquals("", item.insertionTemplate?.plainText)
    }

    @Test
    fun `non-snippet convenience builders clear prior snippet state`() {
        val replacements: List<Pair<String, CompletionBuilder.() -> Unit>> = listOf(
            "Example" to { clazz("Example") },
            "value" to { field("value", "String") },
            "value" to { variable("value", "String") },
            "value" to { property("value", "String") },
        )

        replacements.forEach { (expectedText, replacement) ->
            val builder = CompletionBuilder()
            builder.snippet("old(\$1)")
            builder.label("old")
            builder.replacement()

            val item = builder.build() as TemplateCompletionItem

            assertEquals(expectedText, item.insertText)
            assertEquals(InsertTextFormat.PlainText, item.insertTextFormat)
            assertNull(item.insertionTemplate)
        }
    }
}
