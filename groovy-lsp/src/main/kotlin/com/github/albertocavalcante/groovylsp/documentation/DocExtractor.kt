package com.github.albertocavalcante.groovylsp.documentation

import com.github.albertocavalcante.groovyparser.ast.groovydoc.Groovydoc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.groovydoc.GroovyClassDoc
import org.codehaus.groovy.groovydoc.GroovyProgramElementDoc
import org.codehaus.groovy.tools.groovydoc.LinkArgument
import org.codehaus.groovy.tools.groovydoc.antlr4.GroovyDocParser
import java.util.Properties

/**
 * Extracts documentation from groovydoc/javadoc comments.
 *
 * Implementation note:
 * - We avoid relying on GroovyDocParser for all sources because real-world Groovy sources
 *   (especially Jenkins Pipeline Unit tests and scripts) frequently contain syntax that GroovyDocParser
 *   fails to parse, which would degrade hover/definition UX.
 * - Instead, we locate the closest preceding `/** ... */` block above the node declaration (skipping
 *   annotations and blank lines) and parse the raw comment contents.
 * - When a node does not have usable position metadata (rare in production; common in tests), we fall back
 *   to best-effort GroovyDocParser lookup.
 */
object DocExtractor {
    private val logger = KotlinLogging.logger {}

    /**
     * Extract documentation for a specific AST node from the source text.
     *
     * @param sourceText The complete source code
     * @param node The AST node to find documentation for
     * @return Extracted documentation or empty Documentation if none found
     */
    fun extractDocumentation(sourceText: String, node: ASTNode): Documentation {
        return runCatching {
            val rawComment =
                findRawDocComment(sourceText, node)
                    ?: findRawDocCommentWithGroovyDocParser(sourceText, node)
                    ?: return Documentation.EMPTY
            parseDocComment(rawComment)
        }.onFailure { e ->
            logger.debug(e) { "Failed to extract documentation" }
        }.getOrElse { Documentation.EMPTY }
    }

    private fun findRawDocComment(sourceText: String, node: ASTNode): String? {
        val lines = sourceText.lines()
        if (lines.isEmpty()) return null

        val lineIndex = declarationLineIndex(lines, node) ?: return null
        return findDocCommentBeforeLine(lines, lineIndex)
    }

    private fun declarationLineIndex(lines: List<String>, node: ASTNode): Int? {
        if (node is AnnotatedNode && node.lineNumber > 0) {
            val idx = node.lineNumber - 1
            if (idx in lines.indices) return idx
        }
        return null
    }

    private fun findDocCommentBeforeLine(lines: List<String>, lineIndex: Int): String? =
        locateDocComment(lines, lineIndex)

    private fun findRawDocCommentWithGroovyDocParser(sourceText: String, node: ASTNode): String? = runCatching {
        val parser = GroovyDocParser(emptyList<LinkArgument>(), Properties())
        val classDocs = parser.getClassDocsFromSingleSource(".", "Script.groovy", sourceText)
        findDocForNode(classDocs, node)?.rawCommentText
    }.onFailure { e ->
        logger.debug(e) { "GroovyDocParser failed; falling back to comment scan" }
    }.getOrNull()
        ?.takeUnless { it.isBlank() }

    private fun findDocForNode(classDocs: Map<String, GroovyClassDoc>, node: ASTNode): GroovyProgramElementDoc? {
        val classDoc = findClassDoc(classDocs, node) ?: return null
        return when (node) {
            is ClassNode -> classDoc
            is MethodNode -> findMethodDoc(classDoc, node)
            is FieldNode -> findFieldDoc(classDoc, node)
            is PropertyNode -> findPropertyDoc(classDoc, node)
            else -> null
        }
    }

    private fun findClassDoc(classDocs: Map<String, GroovyClassDoc>, node: ASTNode): GroovyClassDoc? {
        val className = when (node) {
            is ClassNode -> node.name
            is MethodNode -> node.declaringClass.name
            is FieldNode -> node.declaringClass.name
            is PropertyNode -> node.declaringClass.name
            else -> return null
        }

        classDocs[className]?.let { return it }

        val simpleName = className.substringAfterLast('.')
        return classDocs.values.find { it.name() == simpleName || it.qualifiedName() == className }
    }

    private fun findMethodDoc(classDoc: GroovyClassDoc, node: MethodNode): GroovyProgramElementDoc? =
        classDoc.methods().find { methodDoc ->
            methodDoc.name() == node.name &&
                methodDoc.parameters().size == node.parameters.size
        } ?: classDoc.constructors().find { constructorDoc ->
            constructorDoc.name() == classDoc.name() &&
                constructorDoc.parameters().size == node.parameters.size
        }

    private fun findFieldDoc(classDoc: GroovyClassDoc, node: FieldNode): GroovyProgramElementDoc? =
        classDoc.fields().find { it.name() == node.name }

    private fun findPropertyDoc(classDoc: GroovyClassDoc, node: PropertyNode): GroovyProgramElementDoc? =
        classDoc.properties().find { it.name() == node.name }

    /**
     * Parse a doc comment string into a Documentation object.
     *
     * Uses the new GroovyDoc parser as primary method, with regex fallback
     * for cases where the parser fails (e.g., malformed comments).
     */
    private fun parseDocComment(docComment: String): Documentation {
        // Try new GroovyDoc parser first
        return runCatching {
            val cleanedForParser = cleanDocCommentForParser(docComment)
            val groovydoc = Groovydoc.parse(cleanedForParser)
            GroovyDocAdapter.toDocumentation(groovydoc)
        }.onFailure { e ->
            logger.debug(e) { "GroovyDoc parser failed; falling back to regex parsing" }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: parseDocCommentWithRegex(docComment)
    }

    /**
     * Clean a doc comment for parsing by removing delimiters and asterisks.
     */
    private fun cleanDocCommentForParser(docComment: String): String = docComment
        .lines()
        .joinToString("\n") { line ->
            line.trim()
                .replaceFirst(Regex("""^\s*/\*+"""), "")
                .replaceFirst(Regex("""\*/\s*$"""), "")
                .removePrefix("*")
                .trim()
        }
        .trim()

    /**
     * Parse a doc comment using regex (legacy fallback method).
     */
    private fun parseDocCommentWithRegex(docComment: String): Documentation {
        // Remove comment delimiters and asterisks
        val cleanedComment = cleanDocCommentForParser(docComment)

        // Regex for whitespace normalization
        val whitespaceRegex = Regex("""\s+""")

        // Regex to extract @param tags
        val paramRegex = Regex("""@param\s+(\w+)\s+(.+?)(?=@\w+|$)""", RegexOption.DOT_MATCHES_ALL)
        // Regex to extract @return tag
        val returnRegex = Regex("""@return\s+(.+?)(?=@\w+|$)""", RegexOption.DOT_MATCHES_ALL)
        // Regex to extract @throws/@exception tags
        val throwsRegex = Regex("""@(?:throws|exception)\s+(\w+)\s+(.+?)(?=@\w+|$)""", RegexOption.DOT_MATCHES_ALL)
        // Regex to extract @since tag
        val sinceRegex = Regex("""@since\s+(.+?)(?=@\w+|$)""", RegexOption.DOT_MATCHES_ALL)
        // Regex to extract @author tag
        val authorRegex = Regex("""@author\s+(.+?)(?=@\w+|$)""", RegexOption.DOT_MATCHES_ALL)
        // Regex to extract @deprecated tag
        val deprecatedRegex = Regex("""@deprecated\s+(.+?)(?=@\w+|$)""", RegexOption.DOT_MATCHES_ALL)
        // Regex to extract @see tag
        val seeRegex = Regex("""@see\s+(.+?)(?=@\w+|$)""", RegexOption.DOT_MATCHES_ALL)

        // Extract summary (first sentence or first paragraph)
        val summaryMatch = cleanedComment.split(Regex("""[.?!]\s+|\n\n""")).firstOrNull()?.trim() ?: ""
        val summary = if (summaryMatch.startsWith("@")) "" else summaryMatch

        // Extract description (everything before first @ tag)
        val descParts = cleanedComment.split(Regex("""(?=@\w+)"""))
        val description = descParts.firstOrNull()?.trim()?.let {
            if (it.startsWith("@")) "" else it
        } ?: ""

        // Extract @param tags
        val params = paramRegex.findAll(cleanedComment).associate { match ->
            val paramName = match.groupValues[1]
            val paramDesc = match.groupValues[2].trim().replace(whitespaceRegex, " ")
            paramName to paramDesc
        }

        // Extract @return tag
        val returnDoc = returnRegex.find(cleanedComment)?.groupValues?.get(1)?.trim()?.replace(whitespaceRegex, " ")
            ?: ""

        // Extract @throws tags
        val throws = throwsRegex.findAll(cleanedComment).associate { match ->
            val exceptionType = match.groupValues[1]
            val exceptionDesc = match.groupValues[2].trim().replace(whitespaceRegex, " ")
            exceptionType to exceptionDesc
        }

        // Extract @since tag
        val since = sinceRegex.find(cleanedComment)?.groupValues?.get(1)?.trim()?.replace(whitespaceRegex, " ") ?: ""

        // Extract @author tag
        val author = authorRegex.find(cleanedComment)?.groupValues?.get(1)?.trim()?.replace(whitespaceRegex, " ")
            ?: ""

        // Extract @deprecated tag
        val deprecated =
            deprecatedRegex.find(cleanedComment)?.groupValues?.get(1)?.trim()?.replace(whitespaceRegex, " ") ?: ""

        // Extract @see tags
        val see = seeRegex.findAll(cleanedComment).map { match ->
            match.groupValues[1].trim().replace(whitespaceRegex, " ")
        }.toList()

        return Documentation(
            summary = summary,
            description = description,
            params = params,
            returnDoc = returnDoc,
            throws = throws,
            since = since,
            author = author,
            deprecated = deprecated,
            see = see,
        )
    }
}

private fun locateDocComment(lines: List<String>, lineIndex: Int): String? {
    if (lines.isEmpty()) return null
    val commentEndIndex = findCommentEndIndex(lines, lineIndex) ?: return null
    if (!lines[commentEndIndex].contains("*/")) return null

    val startIndex = findCommentStartIndex(lines, commentEndIndex) ?: return null
    return lines.subList(startIndex, commentEndIndex + 1).joinToString("\n")
}

private fun findCommentEndIndex(lines: List<String>, lineIndex: Int): Int? {
    var i = (lineIndex - 1).coerceAtMost(lines.lastIndex)
    if (i < 0) return null

    while (i >= 0) {
        val trimmed = lines[i].trim()
        val isAnnotationLine = trimmed.startsWith("@") &&
            trimmed.drop(1).firstOrNull()?.isJavaIdentifierStart() == true

        // NOTE: Heuristic / tradeoff:
        // When scanning upward for a preceding doc comment, we skip annotations and blank lines.
        // This assumes conventional formatting where annotations immediately precede declarations.
        if (trimmed.isBlank() || isAnnotationLine) {
            i--
            continue
        }
        return i
    }
    return null
}

private fun findCommentStartIndex(lines: List<String>, commentEndIndex: Int): Int? {
    for (j in commentEndIndex downTo 0) {
        val line = lines[j]
        if (line.contains("/**")) {
            return j
        }
        if (line.contains("/*") && !line.contains("/**")) {
            // Non-doc block comment; do not treat it as documentation.
            return null
        }
    }
    return null
}
