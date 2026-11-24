// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

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
     * in the compiled document being renamed, read from the text their positions refer to. A class declared elsewhere
     * is declared in a file that mentions its name, so the cross-file guard refuses that rename, and its positions say
     * nothing about this text. A constructor whose name cannot be located exactly throws [RenameRefusedException]:
     * renaming the class without it would not compile.
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
        val located = if (URI.create(declaration.uri) == documentUri) {
            ConstructorNameReader.locate(type, name, TextIndex(compiled.sourceText))
        } else {
            ConstructorNames.Unlocatable(type.lineNumber)
        }
        return when (located) {
            is ConstructorNames.Located -> located.ranges.map { Location(declaration.uri, it) }
            is ConstructorNames.Unlocatable -> throw RenameRefusedException(
                ResponseError(
                    ResponseErrorCode.RequestFailed,
                    "Cannot rename '$name': the name of its constructor at line ${located.line} " +
                        "cannot be located exactly in the source",
                    null,
                ),
            )
        }
    }

    /**
     * The type name a rename at [node] edits when it is a `new T(...)` call (the `new` keyword and the space around
     * the type included, as find-references answers for the class there): one of [locations], inside the call's type.
     * `this(...)` and `super(...)` are keywords naming no type in source, so they have none.
     */
    fun constructedTypeName(node: ASTNode, documentUri: URI, sourceText: String, locations: List<Location>): Location? {
        val call = (node as? ConstructorCallExpression)?.takeUnless { it.isSpecialCall }
        val typeRange = call?.type?.toLspRange()?.let { TextIndex(sourceText).utf16Range(it) } ?: return null
        return locations.singleOrNull {
            URI.create(it.uri) == documentUri &&
                positionOrder.compare(typeRange.start, it.range.start) <= 0 &&
                positionOrder.compare(it.range.end, typeRange.end) <= 0
        }
    }

    private val positionOrder = compareBy<Position>({ it.line }, { it.character })
}

/** Where the constructors a class's source declares spell the class name, which a class rename must edit too. */
internal sealed interface ConstructorNames {
    /** The name token of every constructor with a source position, in declaration order. */
    data class Located(val ranges: List<Range>) : ConstructorNames

    /** A constructor, at 1-based [line], whose name cannot be located exactly; renaming would leave it stale. */
    data class Unlocatable(val line: Int) : ConstructorNames
}

/**
 * Reads constructor headers in held source text. A constructor's AST name is `<init>` and Groovy records no position
 * for its name token, so the token is found by reading the header the compiler bounds: annotations (with their
 * arguments), comments, modifier keywords and type parameters are skipped, and the first other identifier must be
 * the class name followed by `(`. Anything else is [ConstructorNames.Unlocatable], never a guess: a text search could
 * land on a parameter type, an annotation argument or a type-parameter bound spelled like the class.
 *
 * Constructors without a source position (the default constructor, ones `@TupleConstructor`, `@InheritConstructors`
 * and similar transforms add) have no text to edit and are skipped. A record's compact constructor (`Name { ... }`)
 * has no node at all: the parser folds its body into a `@TupleConstructor(pre = ...)` closure and drops the name, so
 * a record whose body spells `Name {` is unlocatable at that line.
 */
internal object ConstructorNameReader {
    fun locate(type: ClassNode, name: String, source: TextIndex): ConstructorNames {
        val primary = type.redirect()
        val compact = compactConstructorLine(primary, name, source)
        if (compact != null) return ConstructorNames.Unlocatable(compact)
        val positioned = primary.declaredConstructors.filter { it.lineNumber > 0 }
        val tokens = positioned.map { constructor ->
            constructor.toLspRange()?.let(source::utf16Range)?.let { SourceCursor(source, it).constructorName(name) }
        }
        val missing = tokens.indexOfFirst { it == null }
        return if (missing < 0) {
            ConstructorNames.Located(tokens.filterNotNull())
        } else {
            ConstructorNames.Unlocatable(positioned[missing].lineNumber)
        }
    }

    private fun compactConstructorLine(type: ClassNode, name: String, source: TextIndex): Int? {
        if (type.annotations.none { it.classNode.nameWithoutPackage in RECORD_ANNOTATIONS }) return null
        val body = type.toLspRange()?.let(source::utf16Range) ?: return type.lineNumber
        return SourceCursor(source, body).compactConstructor(name)
    }
}

/**
 * The parser marks every `record` declaration `@RecordType`, an annotation collector that semantic analysis replaces
 * with `@RecordBase` and the others it collects; either one marks a record.
 */
private val RECORD_ANNOTATIONS = setOf("RecordType", "RecordBase")

/** Words that may precede a constructor's name in its header; none can be a class name. */
private val MODIFIERS = setOf(
    "public", "protected", "private", "static", "final", "abstract", "synchronized", "native", "strictfp",
    "transient", "volatile", "default", "def",
)

/** What a constructor header reader found next: a part it skipped, the name's position, or text it cannot read. */
private enum class HeaderPart { SKIPPED, NAME, UNREADABLE }

/** A forward reader over one compiler-bounded range of source text. */
private class SourceCursor(private val index: TextIndex, range: Range) {
    private val text = index.text
    private val end = index.offsetOf(range.end)
    private var at = index.offsetOf(range.start)

    /** The range of the name in a constructor header, if the header reads `annotations modifiers <T> name (`. */
    fun constructorName(name: String): Range? {
        val start = if (skipToName()) at else return null
        val word = identifier()
        val tokenEnd = at
        skipTrivia()
        val opensParameters = at < end && text[at] == '('
        return Range(index.positionOf(start), index.positionOf(tokenEnd)).takeIf { word == name && opensParameters }
    }

    /** The 1-based line of the first [name] followed by `{` outside strings and comments, a compact constructor. */
    fun compactConstructor(name: String): Int? {
        var line: Int? = null
        while (line == null && at < end) {
            val past = skipLiteralOrComment(at)
            if (past > at) {
                at = past
            } else if (!startsWord()) {
                at++
            } else {
                val start = at
                if (identifier() == name && opensBlock()) line = index.positionOf(start).line + 1
            }
        }
        return line
    }

    /** Past annotations, modifiers and type parameters; false when one of them cannot be read to its end. */
    private fun skipToName(): Boolean {
        var state = HeaderPart.SKIPPED
        while (state == HeaderPart.SKIPPED) {
            skipTrivia()
            state = when {
                at >= end -> HeaderPart.UNREADABLE
                text[at] == '@' -> if (skipAnnotation()) HeaderPart.SKIPPED else HeaderPart.UNREADABLE
                text[at] == '<' -> if (skipGroup('<', '>')) HeaderPart.SKIPPED else HeaderPart.UNREADABLE
                skipModifier() -> HeaderPart.SKIPPED
                else -> HeaderPart.NAME
            }
        }
        return state == HeaderPart.NAME
    }

    /** Consumes a modifier keyword; leaves anything else unread. */
    private fun skipModifier(): Boolean {
        val start = at
        val modifier = identifier() in MODIFIERS
        if (!modifier) at = start
        return modifier
    }

    private fun startsWord(): Boolean = Character.isJavaIdentifierStart(text.codePointAt(at)) &&
        (at == 0 || !Character.isJavaIdentifierPart(text.codePointBefore(at)))

    private fun opensBlock(): Boolean {
        skipTrivia()
        return at < end && text[at] == '{'
    }

    /** `@a.b.Name` and an optional argument list. */
    private fun skipAnnotation(): Boolean {
        at++
        skipTrivia()
        var named = identifier() != null
        while (named) {
            skipTrivia()
            if (at >= end || text[at] != '.') break
            at++
            skipTrivia()
            named = identifier() != null
        }
        return named && (at >= end || text[at] != '(' || skipGroup('(', ')'))
    }

    /** Past the group opening at [at], skipping strings and comments; inside `<…>`, parenthesized text too. */
    private fun skipGroup(open: Char, close: Char): Boolean {
        var depth = 0
        var parentheses = 0
        var closed = false
        while (!closed && at < end) {
            val past = skipLiteralOrComment(at)
            if (past > at) {
                at = past
            } else {
                val c = text[at++]
                when {
                    open != '(' && c == '(' -> parentheses++
                    open != '(' && c == ')' -> parentheses--
                    parentheses > 0 -> Unit
                    c == open -> depth++
                    c == close -> closed = --depth == 0
                }
            }
        }
        return closed
    }

    private fun identifier(): String? {
        if (at >= end || !Character.isJavaIdentifierStart(text.codePointAt(at))) return null
        val start = at
        at += Character.charCount(text.codePointAt(at))
        while (at < end && Character.isJavaIdentifierPart(text.codePointAt(at))) {
            at += Character.charCount(text.codePointAt(at))
        }
        return text.substring(start, at)
    }

    private fun skipTrivia() {
        var moved = true
        while (moved && at < end) {
            val past = if (text[at].isWhitespace()) at + 1 else skipComment(at)
            moved = past > at
            at = past
        }
    }

    private fun skipComment(from: Int): Int = when {
        text.startsWith("/*", from) -> text.indexOf("*/", from + 2).let { if (it < 0) end else it + 2 }
        text.startsWith("//", from) -> text.indexOf('\n', from + 2).let { if (it < 0) end else it + 1 }
        else -> from
    }

    private fun skipLiteralOrComment(from: Int): Int {
        val quote = text[from]
        if (quote != '"' && quote != '\'') return skipComment(from)
        val closer = if (text.startsWith("$quote$quote$quote", from)) "$quote$quote$quote" else quote.toString()
        var i = from + closer.length
        while (i < end && !text.startsWith(closer, i)) {
            i += if (text[i] == '\\') 2 else 1
        }
        return minOf(i + closer.length, end)
    }
}
