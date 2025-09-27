package com.github.albertocavalcante.groovylsp.dsl.completion

import org.eclipse.lsp4j.CompletionItem

/**
 * Utility functions for common Groovy completions.
 */
object GroovyCompletions {
    /**
     * Get basic Groovy language completions.
     */
    fun basic(): List<CompletionItem> = completions {
        keyword("def", "def \${1:name} = \${2:value}", "Define a variable")
        keyword("class", "class \${1:Name} {\n    \$0\n}", "Define a class")
        keyword("interface", "interface \${1:Name} {\n    \$0\n}", "Define an interface")
        keyword("enum", "enum \${1:Name} {\n    \$0\n}", "Define an enum")
        keyword("if", "if (\${1:condition}) {\n    \$0\n}", "Conditional statement")
        keyword("for", "for (\${1:item} in \${2:collection}) {\n    \$0\n}", "For loop")
        keyword("while", "while (\${1:condition}) {\n    \$0\n}", "While loop")

        method("println", "void", listOf("Object"), "Print a line to console")
        method("print", "void", listOf("Object"), "Print to console")
    }
}
