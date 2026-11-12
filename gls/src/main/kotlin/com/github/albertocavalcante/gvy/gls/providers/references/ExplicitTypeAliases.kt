// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.converters.toLspRange
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import groovyjarjarantlr4.v4.runtime.CharStreams
import groovyjarjarantlr4.v4.runtime.Token
import org.apache.groovy.parser.antlr4.GroovyLangLexer
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode

/** Strict rename cannot rewrite import aliases, including explicit aliases with the canonical spelling. */
internal fun ModuleNode.hasExplicitTypeAlias(target: ClassNode, source: TextIndex): Boolean = imports.any {
    it.type?.redirect()?.name == target.redirect().name && it.hasAliasClause(source)
}

private fun ImportNode.hasAliasClause(source: TextIndex): Boolean {
    // Groovy gives an ordinary import and `import Foo as Foo` the same alias metadata.
    // Inspect the actual tokens in this held import's source span to distinguish their bindings.
    val range = checkNotNull(source.utf16Range(checkNotNull(toLspRange()))) { "Unverifiable import alias source" }
    val text = source.text.substring(source.offsetOf(range.start), source.offsetOf(range.end))
    val lexer = GroovyLangLexer(CharStreams.fromString(text))
    val tokens = generateSequence { lexer.nextToken().takeUnless { it.type == Token.EOF } }
        .filter { it.channel == Token.DEFAULT_CHANNEL }
        .map { it.text }
        .filterNot { it.isBlank() || it == ";" || it.startsWith("//") || it.startsWith("/*") }
        .toList()
    return tokens.takeLast(2) == listOf("as", alias)
}
