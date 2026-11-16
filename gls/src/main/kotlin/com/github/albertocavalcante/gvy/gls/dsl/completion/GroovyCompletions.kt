// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.dsl.completion

import org.eclipse.lsp4j.CompletionItem

/**
 * Utility functions for common Groovy completions.
 */
object GroovyCompletions {
    /**
     * Get basic Groovy language completions.
     */
    fun basic(): List<CompletionItem> = completions {
        keyword("def", "def \${1:name} = \${2:value}", "Define a variable", "def name = value")
        keyword("class", "class \${1:Name} {\n    \$0\n}", "Define a class", "class Name {\n    \n}")
        keyword("interface", "interface \${1:Name} {\n    \$0\n}", "Define an interface", "interface Name {\n    \n}")
        keyword("enum", "enum \${1:Name} {\n    \$0\n}", "Define an enum", "enum Name {\n    \n}")
        keyword("if", "if (\${1:condition}) {\n    \$0\n}", "Conditional statement", "if (condition) {\n    \n}")
        keyword(
            "for",
            "for (\${1:item} in \${2:collection}) {\n    \$0\n}",
            "For loop",
            "for (item in collection) {\n    \n}",
        )
        keyword("while", "while (\${1:condition}) {\n    \$0\n}", "While loop", "while (condition) {\n    \n}")

        method("println", "void", listOf("Object"), "Print a line to console")
        method("print", "void", listOf("Object"), "Print to console")
    }
}
