package com.github.albertocavalcante.groovylsp.dsl.completion

import com.github.albertocavalcante.groovylsp.dsl.LspBuilder
import com.github.albertocavalcante.groovylsp.dsl.LspDslMarker
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Top-level function to create a single completion item.
 */
fun completion(init: CompletionBuilder.() -> Unit): CompletionItem = CompletionBuilder().apply(init).build()

/**
 * Top-level function to create a list of completion items.
 */
fun completions(init: CompletionsBuilder.() -> Unit): List<CompletionItem> = CompletionsBuilder().apply(init).build()

/**
 * Builder for a single completion item with sensible defaults.
 */
@LspDslMarker
class CompletionBuilder : LspBuilder<CompletionItem> {
    private var label: String = ""
    private var kind: CompletionItemKind = CompletionItemKind.Text
    private var detail: String? = null
    private var documentation: Either<String, MarkupContent>? = null
    private var insertText: String? = null
    private var insertTextFormat: InsertTextFormat = InsertTextFormat.PlainText
    private var sortText: String? = null
    private var filterText: String? = null

    /**
     * Set the label (required).
     */
    fun label(label: String) {
        this.label = label
    }

    /**
     * Set the kind of completion item.
     */
    fun kind(kind: CompletionItemKind) {
        this.kind = kind
    }

    /**
     * Set detailed information about the completion item.
     */
    fun detail(detail: String) {
        this.detail = detail
    }

    /**
     * Set plain text documentation.
     */
    fun documentation(doc: String) {
        this.documentation = Either.forLeft(doc)
    }

    /**
     * Set markdown documentation.
     */
    fun markdownDocumentation(markdown: String) {
        this.documentation = Either.forRight(
            MarkupContent().apply {
                kind = MarkupKind.MARKDOWN
                value = markdown
            },
        )
    }

    /**
     * Set the insert text (defaults to label if not set).
     */
    fun insertText(text: String) {
        this.insertText = text
        this.insertTextFormat = InsertTextFormat.PlainText
    }

    /**
     * Set snippet insert text with placeholders.
     */
    fun snippet(snippet: String) {
        this.insertText = snippet
        this.insertTextFormat = InsertTextFormat.Snippet
    }

    /**
     * Set sort text for ordering.
     */
    fun sortText(text: String) {
        this.sortText = text
    }

    /**
     * Set filter text for filtering.
     */
    fun filterText(text: String) {
        this.filterText = text
    }

    // Delegate convenience methods to semantic builders for backward compatibility

    /**
     * Create a method completion with parameters.
     */
    fun method(name: String, returnType: String, parameters: List<String>, doc: String? = null) {
        val methodItem = MethodCompletionBuilder().build(name, returnType, parameters, doc)
        this.label = methodItem.label
        this.kind = methodItem.kind
        this.detail = methodItem.detail
        this.documentation = methodItem.documentation
        this.insertText = methodItem.insertText
        this.insertTextFormat = methodItem.insertTextFormat
    }

    /**
     * Create a class completion.
     */
    fun clazz(name: String, packageName: String? = null, doc: String? = null) {
        val classItem = ClassCompletionBuilder().build(name, packageName, doc)
        this.label = classItem.label
        this.kind = classItem.kind
        this.detail = classItem.detail
        this.documentation = classItem.documentation
        this.insertText = classItem.insertText
    }

    /**
     * Create a field completion.
     */
    fun field(name: String, type: String, doc: String? = null) {
        val fieldItem = FieldCompletionBuilder().build(name, type, doc)
        this.label = fieldItem.label
        this.kind = fieldItem.kind
        this.detail = fieldItem.detail
        this.documentation = fieldItem.documentation
        this.insertText = fieldItem.insertText
    }

    /**
     * Create a keyword completion.
     */
    fun keyword(keyword: String, snippet: String? = null, doc: String? = null) {
        val keywordItem = KeywordCompletionBuilder().build(keyword, snippet, doc)
        this.label = keywordItem.label
        this.kind = keywordItem.kind
        this.detail = keywordItem.detail
        this.documentation = keywordItem.documentation
        this.insertText = keywordItem.insertText
        this.insertTextFormat = keywordItem.insertTextFormat
    }

    /**
     * Create a variable completion.
     */
    fun variable(name: String, type: String, doc: String? = null) {
        val variableItem = VariableCompletionBuilder().build(name, type, doc)
        this.label = variableItem.label
        this.kind = variableItem.kind
        this.detail = variableItem.detail
        this.documentation = variableItem.documentation
        this.insertText = variableItem.insertText
    }

    /**
     * Create a property completion (Groovy-style).
     */
    fun property(name: String, type: String, doc: String? = null) {
        val propertyItem = PropertyCompletionBuilder().build(name, type, doc)
        this.label = propertyItem.label
        this.kind = propertyItem.kind
        this.detail = propertyItem.detail
        this.documentation = propertyItem.documentation
        this.insertText = propertyItem.insertText
    }

    override fun build(): CompletionItem = CompletionItem().apply {
        this.label = this@CompletionBuilder.label
        this.kind = this@CompletionBuilder.kind
        this.detail = this@CompletionBuilder.detail
        this.documentation = this@CompletionBuilder.documentation
        this.insertText = this@CompletionBuilder.insertText ?: this@CompletionBuilder.label
        this.insertTextFormat = this@CompletionBuilder.insertTextFormat
        this.sortText = this@CompletionBuilder.sortText
        this.filterText = this@CompletionBuilder.filterText
    }
}

/**
 * Semantic builders for specific completion types.
 */

/**
 * Builder for method completions.
 */
@LspDslMarker
class MethodCompletionBuilder {
    fun build(name: String, returnType: String, parameters: List<String>, doc: String? = null): CompletionItem =
        completion {
            label(name)
            kind(CompletionItemKind.Method)
            detail("$returnType $name(${parameters.joinToString(", ")})")

            val paramSnippet = parameters.mapIndexed { index, param ->
                $$"${$${index + 1}:$$param}"
            }.joinToString(", ")
            snippet("$name($paramSnippet)")

            doc?.let { documentation(it) }
        }
}

/**
 * Builder for class completions.
 */
@LspDslMarker
class ClassCompletionBuilder {
    fun build(name: String, packageName: String? = null, doc: String? = null): CompletionItem = completion {
        label(name)
        kind(CompletionItemKind.Class)
        detail(if (packageName != null) "$packageName.$name" else name)
        insertText(name)
        doc?.let { documentation(it) }
    }
}

/**
 * Builder for field completions.
 */
@LspDslMarker
class FieldCompletionBuilder {
    fun build(name: String, type: String, doc: String? = null): CompletionItem = completion {
        label(name)
        kind(CompletionItemKind.Field)
        detail("$type $name")
        insertText(name)
        doc?.let { documentation(it) }
    }
}

/**
 * Builder for keyword completions.
 */
@LspDslMarker
class KeywordCompletionBuilder {
    fun build(keyword: String, snippetText: String? = null, doc: String? = null): CompletionItem = completion {
        label(keyword)
        kind(CompletionItemKind.Keyword)
        detail("Groovy keyword: $keyword")

        if (snippetText != null) {
            snippet(snippetText)
        } else {
            insertText(keyword)
        }

        doc?.let { documentation(it) }
    }
}

/**
 * Builder for variable completions.
 */
@LspDslMarker
class VariableCompletionBuilder {
    fun build(name: String, type: String, doc: String? = null): CompletionItem = completion {
        label(name)
        kind(CompletionItemKind.Variable)
        detail("$type $name")
        insertText(name)
        doc?.let { documentation(it) }
    }
}

/**
 * Builder for property completions.
 */
@LspDslMarker
class PropertyCompletionBuilder {
    fun build(name: String, type: String, doc: String? = null): CompletionItem = completion {
        label(name)
        kind(CompletionItemKind.Property)
        detail("$type $name")
        insertText(name)
        doc?.let { documentation(it) }
    }
}

/**
 * Builder for function/callable completions (e.g., Jenkins vars).
 * Inserts the call with parentheses: `name()` with cursor inside.
 */
@LspDslMarker
class FunctionCompletionBuilder {
    fun build(name: String, returnType: String = "void", doc: String? = null): CompletionItem = completion {
        label(name)
        kind(CompletionItemKind.Function)
        detail("$returnType $name()")
        snippet("$name($1)")
        doc?.let { documentation(it) }
    }
}

/**
 * Builder for multiple completion items.
 */
@LspDslMarker
class CompletionsBuilder : LspBuilder<List<CompletionItem>> {
    private val completions = mutableListOf<CompletionItem>()

    /**
     * Add a completion using the completion DSL.
     */
    fun completion(init: CompletionBuilder.() -> Unit) {
        completions.add(CompletionBuilder().apply(init).build())
    }

    /**
     * Add a pre-built completion item.
     */
    fun add(completion: CompletionItem) {
        completions.add(completion)
    }

    /**
     * Add multiple pre-built completion items.
     */
    fun addAll(completions: List<CompletionItem>) {
        this.completions.addAll(completions)
    }

    /**
     * Convenience method for method completions.
     */
    fun method(name: String, returnType: String, parameters: List<String>, doc: String? = null) {
        completions.add(MethodCompletionBuilder().build(name, returnType, parameters, doc))
    }

    /**
     * Convenience method for class completions.
     */
    fun clazz(name: String, packageName: String? = null, doc: String? = null) {
        completions.add(ClassCompletionBuilder().build(name, packageName, doc))
    }

    /**
     * Convenience method for field completions.
     */
    fun field(name: String, type: String, doc: String? = null) {
        completions.add(FieldCompletionBuilder().build(name, type, doc))
    }

    /**
     * Convenience method for keyword completions.
     */
    fun keyword(keyword: String, snippet: String? = null, doc: String? = null) {
        completions.add(KeywordCompletionBuilder().build(keyword, snippet, doc))
    }

    /**
     * Convenience method for variable completions.
     */
    fun variable(name: String, type: String, doc: String? = null) {
        completions.add(VariableCompletionBuilder().build(name, type, doc))
    }

    /**
     * Convenience method for property completions.
     */
    fun property(name: String, type: String, doc: String? = null) {
        completions.add(PropertyCompletionBuilder().build(name, type, doc))
    }

    /**
     * Convenience method for function/callable completions (e.g., Jenkins vars).
     * Inserts the name with parentheses: `name()`.
     */
    fun function(name: String, returnType: String = "void", doc: String? = null) {
        completions.add(FunctionCompletionBuilder().build(name, returnType, doc))
    }

    override fun build(): List<CompletionItem> = completions.toList()
}
