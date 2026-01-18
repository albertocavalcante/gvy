package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.gvy.semantics.SemanticType
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement

private const val IMPORT_KEYWORD = "import"
private const val STATIC_KEYWORD = "static"
private const val DUMMY_IDENTIFIER = CompletionProvider.DUMMY_IDENTIFIER

internal object CompletionContextDetector {

    fun isCleanInsertion(content: String, line: Int, character: Int): Boolean {
        val lines = content.lines()
        if (line < 0 || line >= lines.size) return true

        val targetLine = lines[line]
        val safeChar = character.coerceIn(0, targetLine.length)

        val charBefore = if (safeChar > 0) targetLine[safeChar - 1] else ' '
        val charAfter = if (safeChar < targetLine.length) targetLine[safeChar] else ' '

        return !Character.isJavaIdentifierPart(charBefore) && !Character.isJavaIdentifierPart(charAfter)
    }

    fun insertDummyIdentifier(content: String, line: Int, character: Int, withDef: Boolean): String {
        val lines = content.lines().toMutableList()
        if (line < 0 || line >= lines.size) return content

        if (line > 0) {
            val prevLineIdx = line - 1
            if (lines[prevLineIdx].trim().endsWith("=")) {
                lines[prevLineIdx] = lines[prevLineIdx] + " null;"
            }
        }

        val targetLine = lines[line]
        val safeChar = character.coerceIn(0, targetLine.length)

        val insertion = if (withDef) "def $DUMMY_IDENTIFIER" else DUMMY_IDENTIFIER
        val modifiedLine = targetLine.substring(0, safeChar) + insertion + targetLine.substring(safeChar)
        lines[line] = modifiedLine

        return lines.joinToString("\n")
    }

    fun findEnclosingMethodCall(node: ASTNode?, astModel: GroovyAstModel): MethodCallExpression? {
        var current: ASTNode? = node
        while (current != null) {
            if (current is MethodCallExpression) {
                return current
            }
            current = astModel.getParent(current)
        }
        return null
    }

    fun findNodeAtOrBefore(
        astModel: GroovyAstModel,
        uri: java.net.URI,
        content: String,
        line: Int,
        character: Int,
    ): ASTNode? {
        val lines = content.split('\n')
        if (lines.isEmpty()) {
            return null
        }

        val clampedLine = line.coerceIn(0, lines.lastIndex)
        val clampedChar = character.coerceAtLeast(0)

        var found: ASTNode? = astModel.getNodeAt(uri, clampedLine, clampedChar)
        if (found != null) {
            return found
        }

        var lineIndex = clampedLine
        var charIndex = clampedChar - 1
        while (lineIndex >= 0 && found == null) {
            val lineText = lines.getOrNull(lineIndex).orEmpty()
            if (charIndex > lineText.lastIndex) {
                charIndex = lineText.lastIndex
            }
            while (charIndex >= 0 && lineText[charIndex].isWhitespace()) {
                charIndex--
            }
            if (charIndex >= 0) {
                found = astModel.getNodeAt(uri, lineIndex, charIndex)
            }
            lineIndex--
            charIndex = Int.MAX_VALUE
        }

        return found
    }

    fun detectCompletionContext(
        nodeAtCursor: ASTNode?,
        astModel: GroovyAstModel,
        semanticResolver: SemanticTypeResolver,
        moduleNode: ModuleNode?,
    ): CompletionProvider.ContextType? {
        val node = nodeAtCursor ?: return null
        val parent = astModel.getParent(node)

        return when (node) {
            is PropertyExpression -> memberAccessFromExpression(node.objectExpression, semanticResolver, moduleNode)
            is VariableExpression -> completionFromVariableExpression(node, parent, semanticResolver, moduleNode)
            is ConstantExpression -> completionFromConstantExpression(parent, semanticResolver, moduleNode)
            is ClassExpression -> completionFromClassExpression(node)
            is MethodCallExpression -> null
            else -> null
        }
    }

    fun detectImportCompletionContext(
        content: String,
        line: Int,
        character: Int,
        tokenIndex: GroovyTokenIndex?,
    ): CompletionProvider.ImportCompletionContext? = content.split('\n').let { lines ->
        if (line !in lines.indices) {
            null
        } else {
            val lineText = lines[line]
            val safeChar = character.coerceIn(0, lineText.length)
            val beforeCursor = lineText.substring(0, safeChar)
            val importColumn = lineText.indexOf(IMPORT_KEYWORD)
            val isImportLine = isImportLine(beforeCursor, importColumn, safeChar)
            val offset = offsetAt(content, lines, line, character)

            // Use token index if available, otherwise fallback to text-based heuristic
            val isInCommentOrString = if (tokenIndex != null) {
                tokenIndex.isInCommentOrString(offset)
            } else {
                isInCommentOrStringHeuristic(content, offset)
            }

            if (!isImportLine || isInCommentOrString) {
                null
            } else {
                parseImportCompletionContext(
                    line = line,
                    lineText = lineText,
                    safeChar = safeChar,
                    importColumn = importColumn,
                )
            }
        }
    }

    /**
     * Text-based heuristic to detect if offset is inside a comment or string literal.
     * Used when tokenIndex is unavailable (before compilation).
     */
    private fun isInCommentOrStringHeuristic(content: String, offset: Int): Boolean {
        if (content.isEmpty()) return false

        val safeOffset = offset.coerceIn(0, content.length)
        val beforeOffset = content.substring(0, safeOffset)

        // Block comment detection: check if the last /* is after the last */
        val lastOpenComment = beforeOffset.lastIndexOf("/*")
        val lastCloseComment = beforeOffset.lastIndexOf("*/")
        val inBlockComment = lastOpenComment != -1 && lastOpenComment > lastCloseComment

        // Line comment detection: look for // on the current line before the offset
        val lineStart = content.lastIndexOf('\n', (safeOffset - 1).coerceAtLeast(0)).let {
            if (it == -1) 0 else it + 1
        }
        val lineBeforeOffset = content.substring(lineStart, safeOffset)

        val lastLineComment = lineBeforeOffset.lastIndexOf("//")
        // Treat as line comment only if the // itself is not inside a string literal
        val inLineComment =
            lastLineComment != -1 && !isInStringLiteral(lineBeforeOffset, lastLineComment)

        // String literal detection: check if we're currently inside a quoted string on this line
        val inString = isInStringLiteral(lineBeforeOffset, lineBeforeOffset.length)

        return inBlockComment || inLineComment || inString
    }

    /**
     * Heuristic to determine if a given position in the text is inside a string literal.
     * Scans from the start of the text up to position, toggling state on unescaped quotes.
     */
    private fun isInStringLiteral(text: String, position: Int): Boolean {
        if (text.isEmpty() || position <= 0) return false

        var inSingle = false
        var inDouble = false
        var i = 0
        val end = position.coerceIn(0, text.length)

        while (i < end) {
            val c = text[i]
            val prevIsEscape = i > 0 && text[i - 1] == '\\'
            when (c) {
                '\'' -> if (!inDouble && !prevIsEscape) {
                    inSingle = !inSingle
                }

                '"' -> if (!inSingle && !prevIsEscape) {
                    inDouble = !inDouble
                }
            }
            i++
        }

        return inSingle || inDouble
    }

    fun isCommandExpression(content: String, line: Int, character: Int, methodName: String): Boolean {
        val lines = content.lines()
        val currentLine = lines.getOrNull(line) ?: return false
        val safeChar = character.coerceIn(0, currentLine.length)
        val prefix = currentLine.substring(0, safeChar)
        val trimmed = prefix.trimStart()
        val pattern =
            Regex("""\\b${Regex.escape(methodName)}\\s+['\"][^'\"]*$""")
        return pattern.containsMatchIn(trimmed)
    }
}

private fun resolveQualifier(
    objectExpr: Expression,
    semanticResolver: SemanticTypeResolver,
    moduleNode: ModuleNode?,
): Pair<String, String?>? {
    val qualifierName = if (objectExpr is VariableExpression) objectExpr.name else null

    // Resolve type using semantic resolver (with safe fallback)
    val resolvedType = runCatching { semanticResolver.resolveType(objectExpr, moduleNode) }
        .getOrNull()

    val qualifierType = when (resolvedType) {
        is SemanticType.Known -> resolvedType.fqn
        // Fallback to AST type info if semantic resolution fails or returns unknown
        else -> objectExpr.type?.name
    }

    return qualifierType?.let { it to qualifierName }
}

private fun memberAccessFromExpression(
    expression: Expression,
    semanticResolver: SemanticTypeResolver,
    moduleNode: ModuleNode?,
): CompletionProvider.ContextType.MemberAccess? =
    resolveQualifier(expression, semanticResolver, moduleNode)?.let { (type, name) ->
        CompletionProvider.ContextType.MemberAccess(type, name)
    }

@Suppress("ReturnCount") // Multiple validation checks require early returns
private fun completionFromVariableExpression(
    expression: VariableExpression,
    parent: ASTNode?,
    semanticResolver: SemanticTypeResolver,
    moduleNode: ModuleNode?,
): CompletionProvider.ContextType? {
    if (parent is PropertyExpression) {
        // If it's an implicit 'this' (e.g. just a variable name), we want standard completion (locals + members),
        // not strict member completion that filters out locals.
        if (parent.isImplicitThis) {
            return null
        }
        val qualifierName = expression.name

        // Resolve type using semantic resolver (with safe fallback)
        val resolvedType = runCatching { semanticResolver.resolveType(expression, moduleNode) }
            .getOrNull()

        val qualifierType = when (resolvedType) {
            is SemanticType.Known -> resolvedType.fqn
            else -> expression.type?.name
        }

        return qualifierType?.let { CompletionProvider.ContextType.MemberAccess(it, qualifierName) }
    }

    if (
        parent is BinaryExpression &&
        parent.operation.text == "<" &&
        expression.name.contains(DUMMY_IDENTIFIER)
    ) {
        val prefix = expression.name.substringBefore(DUMMY_IDENTIFIER)
        return CompletionProvider.ContextType.TypeParameter(prefix)
    }

    return null
}

private fun completionFromConstantExpression(
    parent: ASTNode?,
    semanticResolver: SemanticTypeResolver,
    moduleNode: ModuleNode?,
): CompletionProvider.ContextType.MemberAccess? {
    if (parent !is PropertyExpression) {
        return null
    }

    // If it's an implicit 'this' (e.g. just a variable name), we want standard completion (locals + members),
    // not strict member completion that filters out locals.
    if (parent.isImplicitThis) {
        return null
    }
    // Double check for explicit 'this' if implicit flag is missing but name implies it
    if (parent.objectExpression is VariableExpression &&
        (parent.objectExpression as VariableExpression).name == "this"
    ) {
        return null
    }

    // TODO(#657): Refactor to use robust AST-based detection instead of text/regex heuristics.
    // Special handling for Jenkins 'script' blocks:
    // The parser may treat the block content as a property access on the 'script' method call result.
    // In this case, we want standard scope completion, not member completion on the script return value.
    if (parent.objectExpression is MethodCallExpression &&
        (parent.objectExpression as MethodCallExpression).methodAsString == "script"
    ) {
        return null
    }
    val objectExpr = parent.objectExpression
    return memberAccessFromExpression(objectExpr, semanticResolver, moduleNode)
}

private fun completionFromClassExpression(expression: ClassExpression): CompletionProvider.ContextType.TypeParameter? {
    val generics = expression.type.genericsTypes ?: return null
    val dummyGeneric = generics.find { it.name.contains(DUMMY_IDENTIFIER) } ?: return null
    val prefix = dummyGeneric.name.substringBefore(DUMMY_IDENTIFIER)
    return CompletionProvider.ContextType.TypeParameter(prefix)
}

private fun parseImportCompletionContext(
    line: Int,
    lineText: String,
    safeChar: Int,
    importColumn: Int,
): CompletionProvider.ImportCompletionContext? {
    if (importColumn == -1 || importColumn + IMPORT_KEYWORD.length > safeChar) {
        return null
    }

    val afterImportSlice = lineText.substring(importColumn + IMPORT_KEYWORD.length, safeChar)
    val afterImportTrimStart = afterImportSlice.indexOfFirst { !it.isWhitespace() }
    if (afterImportTrimStart == -1) {
        return CompletionProvider.ImportCompletionContext(
            prefix = "",
            isStatic = false,
            canSuggestStatic = true,
            line = line,
            replaceStartCharacter = safeChar,
            replaceEndCharacter = safeChar,
        )
    }

    val afterImportTrimmed = afterImportSlice.substring(afterImportTrimStart)
    val afterImportStart = importColumn + IMPORT_KEYWORD.length + afterImportTrimStart

    return if (hasCompleteStaticKeyword(afterImportTrimmed)) {
        parseStaticImportPrefix(
            line = line,
            lineText = lineText,
            safeChar = safeChar,
            afterImportStart = afterImportStart,
        )
    } else {
        parseNonStaticImportPrefix(
            line = line,
            safeChar = safeChar,
            afterImportTrimmed = afterImportTrimmed,
            afterImportStart = afterImportStart,
        )
    }
}

private fun parseStaticImportPrefix(
    line: Int,
    lineText: String,
    safeChar: Int,
    afterImportStart: Int,
): CompletionProvider.ImportCompletionContext {
    val afterStaticIndex = afterImportStart + STATIC_KEYWORD.length
    val afterStaticSlice = lineText.substring(afterStaticIndex, safeChar)
    val afterStaticTrimStart = afterStaticSlice.indexOfFirst { !it.isWhitespace() }
    if (afterStaticTrimStart == -1) {
        return CompletionProvider.ImportCompletionContext(
            prefix = "",
            isStatic = true,
            canSuggestStatic = false,
            line = line,
            replaceStartCharacter = safeChar,
            replaceEndCharacter = safeChar,
        )
    }

    val prefix = afterStaticSlice.substring(afterStaticTrimStart)
    val prefixStart = afterStaticIndex + afterStaticTrimStart
    return CompletionProvider.ImportCompletionContext(
        prefix = prefix,
        isStatic = true,
        canSuggestStatic = false,
        line = line,
        replaceStartCharacter = prefixStart,
        replaceEndCharacter = safeChar,
    )
}

private fun parseNonStaticImportPrefix(
    line: Int,
    safeChar: Int,
    afterImportTrimmed: String,
    afterImportStart: Int,
): CompletionProvider.ImportCompletionContext {
    val isTypingStatic = isTypingStaticKeyword(afterImportTrimmed)
    val prefix = if (isTypingStatic) "" else afterImportTrimmed
    val replaceStart = if (isTypingStatic) safeChar else afterImportStart
    return CompletionProvider.ImportCompletionContext(
        prefix = prefix,
        isStatic = false,
        canSuggestStatic = true,
        line = line,
        replaceStartCharacter = replaceStart,
        replaceEndCharacter = safeChar,
    )
}

private fun isImportLine(beforeCursor: String, importColumn: Int, safeChar: Int): Boolean {
    if (importColumn == -1 || importColumn + IMPORT_KEYWORD.length > safeChar) {
        return false
    }

    val trimmed = beforeCursor.trimStart()
    if (!trimmed.startsWith(IMPORT_KEYWORD)) {
        return false
    }

    val isImportKeywordBoundary =
        trimmed.length <= IMPORT_KEYWORD.length ||
            !Character.isJavaIdentifierPart(trimmed[IMPORT_KEYWORD.length])
    return isImportKeywordBoundary
}

private fun hasCompleteStaticKeyword(afterImportTrimmed: String): Boolean =
    afterImportTrimmed.startsWith(STATIC_KEYWORD) &&
        (
            afterImportTrimmed.length == STATIC_KEYWORD.length ||
                afterImportTrimmed[STATIC_KEYWORD.length].isWhitespace()
            )

private fun isTypingStaticKeyword(afterImportTrimmed: String): Boolean =
    !afterImportTrimmed.any { it.isWhitespace() } && STATIC_KEYWORD.startsWith(afterImportTrimmed)

private fun offsetAt(content: String, lines: List<String>, line: Int, character: Int): Int {
    if (lines.isEmpty()) return 0
    val safeLine = line.coerceIn(0, lines.lastIndex)
    val safeChar = character.coerceAtLeast(0)

    var offset = 0
    for (i in 0 until safeLine) {
        offset += lines[i].length + 1
    }

    val lineText = lines[safeLine]
    offset += safeChar.coerceIn(0, lineText.length)
    return offset.coerceIn(0, content.length)
}

/**
 * Represents the cursor position context for completion filtering.
 */
sealed class CursorPositionContext {
    /** Cursor is at block level (not inside any method call arguments) */
    object BlockLevel : CursorPositionContext()

    /** Cursor is inside a method call's argument list */
    data class InsideMethodCall(val methodName: String) : CursorPositionContext()
}

/**
 * Detect if the cursor is at block level or inside a method call's argument list.
 * This is used to filter completions appropriately.
 *
 * Block level means: cursor is inside a closure body or at the top level.
 * Inside method call means: cursor is in the argument list (map arguments, positional arguments),
 * but NOT inside a closure that's passed as an argument.
 *
 * Strategy: Walk up the AST. The FIRST context we encounter determines the result:
 * - If we hit a closure's code block first: Block Level
 * - If we hit method call arguments first (before any closure): Inside Method Call
 */
@Suppress("ReturnCount", "NestedBlockDepth") // AST traversal requires nested checks for different node types
fun detectCursorPositionContext(nodeAtCursor: ASTNode?, astModel: GroovyAstModel): CursorPositionContext {
    var current: ASTNode? = nodeAtCursor

    while (current != null) {
        val parent = astModel.getParent(current)

        // Check if current node is a BlockStatement inside a ClosureExpression
        if (current is BlockStatement) {
            val blockParent = parent
            if (blockParent is ClosureExpression) {
                // We're inside the code block of a closure - this is block level
                return CursorPositionContext.BlockLevel
            }
        }

        // Check if we're inside a closure expression at all
        if (current is ClosureExpression) {
            // We're at or inside a closure - block level
            return CursorPositionContext.BlockLevel
        }

        // Check if parent is a MethodCallExpression and current is in its arguments
        if (parent is MethodCallExpression) {
            val arguments = parent.arguments

            // Check various ways we might be in the arguments
            if (arguments === current) {
                // Don't treat closure arguments as "inside method call"
                if (current is ClosureExpression) {
                    return CursorPositionContext.BlockLevel
                }
                return CursorPositionContext.InsideMethodCall(parent.methodAsString ?: "")
            }

            // Check if we're inside a tuple/map/named argument list
            if (arguments is TupleExpression) {
                val isInNonClosureArg = arguments.expressions.any { expr ->
                    if (expr is ClosureExpression) {
                        false // Skip closures
                    } else {
                        isNodeOrDescendant(current, expr, astModel)
                    }
                }
                if (isInNonClosureArg) {
                    return CursorPositionContext.InsideMethodCall(parent.methodAsString ?: "")
                }
            }
        }

        current = parent
    }

    return CursorPositionContext.BlockLevel
}

/**
 * Check if targetNode is the same as or a descendant of potentialAncestor.
 */
private fun isNodeOrDescendant(targetNode: ASTNode?, potentialAncestor: ASTNode?, astModel: GroovyAstModel): Boolean {
    if (targetNode == null || potentialAncestor == null) return false
    if (targetNode === potentialAncestor) return true

    var current: ASTNode? = targetNode
    while (current != null) {
        if (current === potentialAncestor) return true
        current = astModel.getParent(current)
    }
    return false
}
