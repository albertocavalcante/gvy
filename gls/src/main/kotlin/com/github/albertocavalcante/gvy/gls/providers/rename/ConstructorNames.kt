// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.converters.toLspRange
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI

/** How a class rename treats the constructors of the class and the `new` calls that name it. */
internal object ClassRenameConstructors {
    /**
     * The declaration a rename resolved to [definition] renames. A constructor is named for its class, so renaming it
     * renames the class, as Java editors do; null for a constructor without a class, which no source declares.
     */
    fun renamedDeclaration(definition: ASTNode): ASTNode? =
        if (definition is ConstructorNode) definition.declaringClass else definition

    /**
     * The name of each constructor of [definition] (a class) that the rename must edit with the class: a constructor
     * resolves to itself, so the reference scan never reports it. Constructors are renamed when the class is declared
     * in the compiled document being renamed, located in the text and tokens their positions refer to. A class
     * declared elsewhere is declared in a file that mentions its name, so the cross-file guard refuses that rename.
     * A constructor whose name cannot be located exactly throws [RenameRefusedException]: renaming the class without
     * it would not compile.
     */
    fun nameLocations(
        definition: ASTNode,
        compiled: CompiledDocumentSnapshot,
        declaration: Location,
        documentUri: URI,
        name: String,
    ): List<Location> {
        val type = (definition as? ClassNode)?.redirect()
        val module = compiled.parseResult.ast
        if (type == null || module == null || module.classes.none { it === type }) return emptyList()
        if (URI.create(declaration.uri) != documentUri) {
            refuse("Cannot rename '$name': its declaration is not located in the document being renamed")
        }
        val source = TextIndex(compiled.sourceText)
        return when (val located = ConstructorNameReader.locate(type, name, source, compiled.parseResult.tokenIndex)) {
            is ConstructorNames.Located -> located.ranges.map { Location(declaration.uri, it) }
            is ConstructorNames.Unlocatable -> refuse(
                "Cannot rename '$name': the name of its constructor at line ${located.line} " +
                    "cannot be located exactly in the source",
            )
        }
    }

    /** Whether a rename at [node] must start on a name the rename edits: a constructor or a `new T(...)` call. */
    fun startsOnName(node: ASTNode): Boolean =
        node is ConstructorNode || node is ConstructorCallExpression && !node.isSpecialCall

    /**
     * The edited name under [cursor] in the document, as preparation offers it. At a `new T(...)` call that is not on
     * a name (the `new` keyword, the space around the type), it is the type name the rename edits there, as
     * find-references answers for the class; but only when that name is on the cursor's line, as clients reject a
     * prepared range on other lines. `this(...)` and `super(...)` are keywords naming no type in source.
     */
    fun nameAtCursor(
        node: ASTNode,
        documentUri: URI,
        sourceText: String,
        locations: List<Location>,
        cursor: Position,
    ): Location? {
        val inDocument = locations.filter { URI.create(it.uri) == documentUri }
        return inDocument.firstOrNull { before(it.range.start, cursor, orAt = true) && before(cursor, it.range.end) }
            ?: constructedTypeName(node, sourceText, inDocument, cursor)
    }

    private fun constructedTypeName(
        node: ASTNode,
        sourceText: String,
        locations: List<Location>,
        cursor: Position,
    ): Location? {
        val call = (node as? ConstructorCallExpression)?.takeUnless { it.isSpecialCall }
        val typeRange = call?.type?.toLspRange()?.let { TextIndex(sourceText).utf16Range(it) }
        if (typeRange == null || typeRange.start.line != cursor.line) return null
        return locations.singleOrNull {
            before(typeRange.start, it.range.start, orAt = true) && before(it.range.end, typeRange.end, orAt = true)
        }
    }

    private fun before(a: Position, b: Position, orAt: Boolean = false): Boolean {
        val order = compareValuesBy(a, b, Position::getLine, Position::getCharacter)
        return order < 0 || orAt && order == 0
    }

    private fun refuse(message: String): Nothing =
        throw RenameRefusedException(ResponseError(ResponseErrorCode.RequestFailed, message, null))
}

/** Where the constructors a class's source declares spell the class name, which a class rename must edit too. */
internal sealed interface ConstructorNames {
    /** The name token of every constructor with a source position, in declaration order. */
    data class Located(val ranges: List<Range>) : ConstructorNames

    /** A constructor, at 1-based [line], whose name cannot be located exactly; renaming would leave it stale. */
    data class Unlocatable(val line: Int) : ConstructorNames
}

/**
 * Locates constructor name tokens from compiler facts. A constructor's AST name is `<init>` and Groovy records no
 * position for its name token, so the name is the one candidate that satisfies all of:
 * - it lies after the end of the constructor's last annotation (annotation nodes span their arguments) and before
 *   its first parameter, or before its code block when it has none;
 * - the lexer classifies its first and last characters as code (never a string of any kind or a comment);
 * - its text is exactly the class name, as a whole identifier, and the next code token is `(`.
 * No candidate, more than one, a missing position or an incomplete lexer pass is [ConstructorNames.Unlocatable].
 *
 * Constructors without a source position (the default constructor, ones `@TupleConstructor`, `@InheritConstructors`
 * and similar transforms add) have no text to edit and are skipped. A record's compact constructor (`Name { ... }`)
 * has no node at all: the parser folds its body into a `pre` closure of `@TupleConstructor` and drops the name, and
 * the transform keeps that member (emptied), so a record whose `@TupleConstructor` has `pre` is unlocatable.
 */
internal object ConstructorNameReader {
    fun locate(type: ClassNode, name: String, source: TextIndex, tokens: GroovyTokenIndex?): ConstructorNames {
        val primary = type.redirect()
        val positioned = primary.declaredConstructors.filter { it.lineNumber > 0 }
        val code = tokens?.takeIf { it.hasCompleteLexicalCoverage }?.let { CodeText(source, it) }
        return when {
            hasCompactConstructor(primary) ->
                ConstructorNames.Unlocatable(code?.compactConstructorLine(primary, name) ?: primary.lineNumber)
            positioned.isEmpty() -> ConstructorNames.Located(emptyList())
            code == null -> ConstructorNames.Unlocatable(positioned.first().lineNumber)
            else -> {
                val names = positioned.map { code.constructorName(it, name) }
                val missing = names.indexOfFirst { it == null }
                if (missing < 0) {
                    ConstructorNames.Located(names.filterNotNull())
                } else {
                    ConstructorNames.Unlocatable(positioned[missing].lineNumber)
                }
            }
        }
    }

    private fun hasCompactConstructor(type: ClassNode): Boolean {
        val simpleNames = type.annotations.associateBy { it.classNode.nameWithoutPackage }
        val record = RECORD_ANNOTATIONS.any { it in simpleNames }
        return record && simpleNames["TupleConstructor"]?.getMember("pre") != null
    }
}

/**
 * The parser marks every `record` declaration `@RecordType`, an annotation collector that semantic analysis replaces
 * with `@RecordBase` and the others it collects; either one marks a record.
 */
private val RECORD_ANNOTATIONS = setOf("RecordType", "RecordBase")

/** Source text read through the lexer's classification of it; offsets are UTF-16. */
private class CodeText(private val index: TextIndex, private val tokens: GroovyTokenIndex) {
    private val text = index.text

    fun constructorName(constructor: ConstructorNode, name: String): Range? {
        val start = offset(constructor, end = false) ?: return null
        val annotationsEnd = constructor.annotations.mapNotNull { offset(it, end = true) }.maxOrNull() ?: start
        val parameters = constructor.parameters.filter { it.lineNumber > 0 }
            .flatMap { listOf(it) + it.annotations }
            .mapNotNull { offset(it, end = false) }
        val until = parameters.minOrNull() ?: constructor.code?.let { offset(it, end = false) } ?: return null
        val candidates = names(name, maxOf(start, annotationsEnd), until)
            .filter { followedBy(it + name.length, '(', until) }
        return candidates.singleOrNull()?.let { Range(index.positionOf(it), index.positionOf(it + name.length)) }
    }

    /** The 1-based line of a code `name {` inside [type], for the message only; null when none is found. */
    fun compactConstructorLine(type: ClassNode, name: String): Int? {
        val from = offset(type, end = false) ?: return null
        val until = offset(type, end = true) ?: return null
        return names(name, from, until).firstOrNull { followedBy(it + name.length, '{', until) }
            ?.let { index.positionOf(it).line + 1 }
    }

    private fun offset(node: ASTNode, end: Boolean): Int? =
        node.toLspRange()?.let(index::utf16Range)?.let { index.offsetOf(if (end) it.end else it.start) }

    /** Offsets in `[from, until)` where [name] is a whole identifier whose first and last characters are code. */
    private fun names(name: String, from: Int, until: Int): List<Int> =
        generateSequence(text.indexOf(name, from).takeIf { it >= 0 }) { at ->
            text.indexOf(name, at + 1).takeIf { it >= 0 }
        }
            .takeWhile { it + name.length <= until }
            .filter { at ->
                val before = at == 0 || !Character.isJavaIdentifierPart(text.codePointBefore(at))
                val after = at + name.length >= text.length ||
                    !Character.isJavaIdentifierPart(text.codePointAt(at + name.length))
                before && after && tokens.isCodeAt(at) && tokens.isCodeAt(at + name.length - 1)
            }
            .toList()

    /** Whether the next character past whitespace and comments, before [until], is a code [char]. */
    private fun followedBy(from: Int, char: Char, until: Int): Boolean {
        var at = from
        while (at < until && (text[at].isWhitespace() || tokens.isInComment(at))) at++
        return at < until && text[at] == char && tokens.isCodeAt(at)
    }
}
