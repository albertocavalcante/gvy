package com.github.albertocavalcante.groovylsp.dsl

import com.github.albertocavalcante.groovylsp.dsl.completion.CompletionBuilder
import com.github.albertocavalcante.groovylsp.dsl.completion.GroovyCompletions
import com.github.albertocavalcante.groovylsp.dsl.completion.completion
import com.github.albertocavalcante.groovylsp.dsl.completion.completions
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.MarkupKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for the Completion DSL.
 */
class CompletionDslTest {

    @Test
    fun `basic completion with label and kind`() {
        val completion = completion {
            label("myVariable")
            kind(CompletionItemKind.Variable)
            detail("String myVariable")
        }

        assertEquals("myVariable", completion.label)
        assertEquals(CompletionItemKind.Variable, completion.kind)
        assertEquals("String myVariable", completion.detail)
        assertEquals("myVariable", completion.insertText) // Should default to label
        assertEquals(InsertTextFormat.PlainText, completion.insertTextFormat)
    }

    @Test
    fun `completion with plain text documentation`() {
        val completion = completion {
            label("testMethod")
            kind(CompletionItemKind.Method)
            documentation("This is a test method")
        }

        assertTrue(completion.documentation!!.isLeft)
        assertEquals("This is a test method", completion.documentation!!.left)
    }

    @Test
    fun `completion with markdown documentation`() {
        val completion = completion {
            label("testClass")
            kind(CompletionItemKind.Class)
            markdownDocumentation("# Test Class\nA **test** class")
        }

        assertTrue(completion.documentation!!.isRight)
        val markdown = completion.documentation!!.right
        assertEquals(MarkupKind.MARKDOWN, markdown.kind)
        assertEquals("# Test Class\nA **test** class", markdown.value)
    }

    @Test
    fun `completion with custom insert text`() {
        val completion = completion {
            label("customInsert")
            insertText("custom_insert_text")
        }

        assertEquals("customInsert", completion.label)
        assertEquals("custom_insert_text", completion.insertText)
        assertEquals(InsertTextFormat.PlainText, completion.insertTextFormat)
    }

    @Test
    fun `completion with snippet`() {
        val completion = completion {
            label("forLoop")
            snippet("for (\${1:item} in \${2:collection}) {\n    \$0\n}")
        }

        assertEquals("forLoop", completion.label)
        assertEquals("for (\${1:item} in \${2:collection}) {\n    \$0\n}", completion.insertText)
        assertEquals(InsertTextFormat.Snippet, completion.insertTextFormat)
    }

    @Test
    fun `completion with sort and filter text`() {
        val completion = completion {
            label("zzz_lastItem")
            sortText("aaa") // Should appear first despite label
            filterText("first") // Should match "first" in search
        }

        assertEquals("zzz_lastItem", completion.label)
        assertEquals("aaa", completion.sortText)
        assertEquals("first", completion.filterText)
    }

    @Test
    fun `method completion shorthand`() {
        val completion = completion {
            method("calculate", "double", listOf("int x", "int y"), "Calculates something")
        }

        assertEquals("calculate", completion.label)
        assertEquals(CompletionItemKind.Method, completion.kind)
        assertEquals("double calculate(int x, int y)", completion.detail)
        assertEquals("calculate(\${1:int x}, \${2:int y})", completion.insertText)
        assertEquals(InsertTextFormat.Snippet, completion.insertTextFormat)
        assertTrue(completion.documentation!!.isLeft)
        assertEquals("Calculates something", completion.documentation!!.left)
    }

    @Test
    fun `class completion shorthand`() {
        val completion = completion {
            clazz("Calculator", "com.example", "A calculator class")
        }

        assertEquals("Calculator", completion.label)
        assertEquals(CompletionItemKind.Class, completion.kind)
        assertEquals("com.example.Calculator", completion.detail)
        assertEquals("Calculator", completion.insertText)
        assertEquals("A calculator class", completion.documentation!!.left)
    }

    @Test
    fun `class completion without package`() {
        val completion = completion {
            clazz("SimpleClass")
        }

        assertEquals("SimpleClass", completion.label)
        assertEquals("SimpleClass", completion.detail)
    }

    @Test
    fun `field completion shorthand`() {
        val completion = completion {
            field("name", "String", "The name field")
        }

        assertEquals("name", completion.label)
        assertEquals(CompletionItemKind.Field, completion.kind)
        assertEquals("String name", completion.detail)
        assertEquals("name", completion.insertText)
        assertEquals("The name field", completion.documentation!!.left)
    }

    @Test
    fun `keyword completion shorthand`() {
        val completion = completion {
            keyword("if", "if (\${1:condition}) {\n    \$0\n}", "Conditional statement")
        }

        assertEquals("if", completion.label)
        assertEquals(CompletionItemKind.Keyword, completion.kind)
        assertEquals("Groovy keyword: if", completion.detail)
        assertEquals("if (\${1:condition}) {\n    \$0\n}", completion.insertText)
        assertEquals(InsertTextFormat.Snippet, completion.insertTextFormat)
        assertEquals("Conditional statement", completion.documentation!!.left)
    }

    @Test
    fun `keyword completion without snippet`() {
        val completion = completion {
            keyword("def", doc = "Define a variable")
        }

        assertEquals("def", completion.label)
        assertEquals("def", completion.insertText)
        assertEquals(InsertTextFormat.PlainText, completion.insertTextFormat)
    }

    @Test
    fun `variable completion shorthand`() {
        val completion = completion {
            variable("count", "int", "A counter variable")
        }

        assertEquals("count", completion.label)
        assertEquals(CompletionItemKind.Variable, completion.kind)
        assertEquals("int count", completion.detail)
        assertEquals("count", completion.insertText)
        assertEquals("A counter variable", completion.documentation!!.left)
    }

    @Test
    fun `property completion shorthand`() {
        val completion = completion {
            property("isActive", "boolean", "Active status property")
        }

        assertEquals("isActive", completion.label)
        assertEquals(CompletionItemKind.Property, completion.kind)
        assertEquals("boolean isActive", completion.detail)
        assertEquals("isActive", completion.insertText)
        assertEquals("Active status property", completion.documentation!!.left)
    }

    @Test
    fun `multiple completions with completions DSL`() {
        val completionList = completions {
            completion {
                label("first")
                kind(CompletionItemKind.Variable)
            }

            method("calculate", "double", listOf("int x"))
            clazz("Calculator", "com.example")
            field("name", "String")
            keyword("if", "if (\${1:condition}) {\n    \$0\n}")
            variable("count", "int")
            property("isActive", "boolean")
        }

        assertEquals(7, completionList.size)

        assertEquals("first", completionList[0].label)
        assertEquals("calculate", completionList[1].label)
        assertEquals("Calculator", completionList[2].label)
        assertEquals("name", completionList[3].label)
        assertEquals("if", completionList[4].label)
        assertEquals("count", completionList[5].label)
        assertEquals("isActive", completionList[6].label)

        // Verify types
        assertEquals(CompletionItemKind.Variable, completionList[0].kind)
        assertEquals(CompletionItemKind.Method, completionList[1].kind)
        assertEquals(CompletionItemKind.Class, completionList[2].kind)
        assertEquals(CompletionItemKind.Field, completionList[3].kind)
        assertEquals(CompletionItemKind.Keyword, completionList[4].kind)
        assertEquals(CompletionItemKind.Variable, completionList[5].kind)
        assertEquals(CompletionItemKind.Property, completionList[6].kind)
    }

    @Test
    fun `completions builder with add methods`() {
        val existingCompletion = completion {
            label("existing")
            kind(CompletionItemKind.Text)
        }

        val moreCompletions = listOf(
            completion {
                label("more1")
                kind(CompletionItemKind.Text)
            },
            completion {
                label("more2")
                kind(CompletionItemKind.Text)
            },
        )

        val completionList = completions {
            add(existingCompletion)
            addAll(moreCompletions)

            completion {
                label("new")
                kind(CompletionItemKind.Text)
            }
        }

        assertEquals(4, completionList.size)
        assertEquals("existing", completionList[0].label)
        assertEquals("more1", completionList[1].label)
        assertEquals("more2", completionList[2].label)
        assertEquals("new", completionList[3].label)
    }

    @Test
    fun `GroovyCompletions basic set`() {
        val basicCompletions = GroovyCompletions.basic()

        assertTrue(basicCompletions.isNotEmpty())

        val defCompletion = basicCompletions.find { it.label == "def" }
        assertNotNull(defCompletion)
        assertEquals(CompletionItemKind.Keyword, defCompletion.kind)
        assertEquals(InsertTextFormat.Snippet, defCompletion.insertTextFormat)

        val printlnCompletion = basicCompletions.find { it.label == "println" }
        assertNotNull(printlnCompletion)
        assertEquals(CompletionItemKind.Method, printlnCompletion.kind)
    }

    @Test
    fun `DSL is immutable - builder reuse doesn't affect previous results`() {
        val builder = CompletionBuilder()

        builder.label("first")
        builder.kind(CompletionItemKind.Variable)
        val first = builder.build()

        builder.label("second")
        builder.kind(CompletionItemKind.Method)
        val second = builder.build()

        // First completion should be unchanged
        assertEquals("first", first.label)
        assertEquals(CompletionItemKind.Variable, first.kind)

        // Second completion should have new values
        assertEquals("second", second.label)
        assertEquals(CompletionItemKind.Method, second.kind)
    }
}
