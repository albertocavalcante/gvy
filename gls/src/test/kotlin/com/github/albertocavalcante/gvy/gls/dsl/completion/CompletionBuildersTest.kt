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
}
