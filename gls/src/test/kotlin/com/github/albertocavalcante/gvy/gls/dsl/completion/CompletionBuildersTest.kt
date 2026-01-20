package com.github.albertocavalcante.gvy.gls.dsl.completion

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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
}
