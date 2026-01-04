package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolCompletionContext
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression

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
        context: SymbolCompletionContext,
    ): CompletionProvider.ContextType? {
        val node = nodeAtCursor ?: return null
        val parent = astModel.getParent(node)

        return when (node) {
            is PropertyExpression -> memberAccessFromExpression(node.objectExpression, context)
            is VariableExpression -> completionFromVariableExpression(node, parent, context)
            is ConstantExpression -> completionFromConstantExpression(parent, context)
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
            val isInCommentOrString = tokenIndex?.isInCommentOrString(offset) == true

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

private fun resolveVariableType(variableName: String, context: SymbolCompletionContext): String? {
    val inferredVar = context.variables.find { it.name == variableName }
    return inferredVar?.type
}

private fun resolveQualifier(objectExpr: Expression, context: SymbolCompletionContext): Pair<String, String?>? {
    var qualifierType = objectExpr.type?.name
    var qualifierName: String? = null

    if (objectExpr is VariableExpression) {
        qualifierName = objectExpr.name
        val inferredType = resolveVariableType(objectExpr.name, context)
        if (inferredType != null) {
            qualifierType = inferredType
        }
    }

    return qualifierType?.let { it to qualifierName }
}

private fun memberAccessFromExpression(
    expression: Expression,
    context: SymbolCompletionContext,
): CompletionProvider.ContextType.MemberAccess? = resolveQualifier(expression, context)?.let { (type, name) ->
    CompletionProvider.ContextType.MemberAccess(type, name)
}

private fun completionFromVariableExpression(
    expression: VariableExpression,
    parent: ASTNode?,
    context: SymbolCompletionContext,
): CompletionProvider.ContextType? {
    if (parent is PropertyExpression) {
        var qualifierType = expression.type?.name
        val qualifierName = expression.name
        val inferredType = resolveVariableType(expression.name, context)
        if (inferredType != null) {
            qualifierType = inferredType
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
    context: SymbolCompletionContext,
): CompletionProvider.ContextType.MemberAccess? {
    if (parent !is PropertyExpression) {
        return null
    }
    val objectExpr = parent.objectExpression
    return memberAccessFromExpression(objectExpr, context)
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
