package com.github.albertocavalcante.groovylsp.documentation

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.groovydoc.GroovyClassDoc
import org.codehaus.groovy.groovydoc.GroovyProgramElementDoc
import org.codehaus.groovy.tools.groovydoc.LinkArgument
import org.codehaus.groovy.tools.groovydoc.antlr4.GroovyDocParser
import org.slf4j.LoggerFactory
import java.util.Properties

/**
 * Extracts documentation from groovydoc/javadoc comments using GroovyDocParser.
 */
object DocExtractor {
    private val logger = LoggerFactory.getLogger(DocExtractor::class.java)

    /**
     * Extract documentation for a specific AST node from the source text.
     *
     * @param sourceText The complete source code
     * @param node The AST node to find documentation for
     * @return Extracted documentation or empty Documentation if none found
     */
    fun extractDocumentation(sourceText: String, node: ASTNode): Documentation {
        try {
            // GroovyDocParser requires a package path, file name, and source.
            // We use dummy values for package/file as we are parsing a single source string.
            // The parser will extract the actual package from the source if present.
            val parser = GroovyDocParser(emptyList<LinkArgument>(), Properties())
            val classDocs = parser.getClassDocsFromSingleSource(".", "Script.groovy", sourceText)

            logger.warn("DEBUG: Extracted ${classDocs.size} class docs from source")

            return findDocForNode(classDocs, node)
        } catch (e: Exception) {
            logger.warn("Failed to parse groovydoc", e)
            return Documentation.EMPTY
        }
    }

    private fun findDocForNode(classDocs: Map<String, GroovyClassDoc>, node: ASTNode): Documentation {
        // Find the class doc that contains the node
        val classDoc = findClassDoc(classDocs, node)

        if (classDoc == null) {
            logger.warn("DEBUG: Could not find ClassDoc for node ${node.text}")
            return Documentation.EMPTY
        }

        val elementDoc: GroovyProgramElementDoc? = when (node) {
            is ClassNode -> classDoc
            is MethodNode -> findMethodDoc(classDoc, node)
            is FieldNode -> findFieldDoc(classDoc, node)
            is PropertyNode -> findPropertyDoc(classDoc, node)
            else -> null
        }

        if (elementDoc == null) {
            logger.warn("DEBUG: Could not find ElementDoc for node type ${node.javaClass.simpleName}")
            return Documentation.EMPTY
        }

        return parseGroovyDoc(elementDoc)
    }

    private fun findClassDoc(classDocs: Map<String, GroovyClassDoc>, node: ASTNode): GroovyClassDoc? {
        val className = when (node) {
            is ClassNode -> node.name
            is MethodNode -> node.declaringClass.name
            is FieldNode -> node.declaringClass.name
            is PropertyNode -> node.declaringClass.name
            else -> return null
        }

        // Try exact match first
        classDocs[className]?.let { return it }

        // Try matching simple name if fully qualified name fails (e.g. if package matches)
        val simpleName = className.substringAfterLast('.')
        return classDocs.values.find { it.name() == simpleName || it.qualifiedName() == className }
    }

    private fun findMethodDoc(classDoc: GroovyClassDoc, node: MethodNode): GroovyProgramElementDoc? {
        // Match method by name and parameters
        return classDoc.methods().find { methodDoc ->
            methodDoc.name() == node.name &&
                methodDoc.parameters().size == node.parameters.size
            // TODO: check parameter types if needed for overloading
        } ?: classDoc.constructors().find { constructorDoc ->
            constructorDoc.name() == classDoc.name() && // Constructors usually have class name
                constructorDoc.parameters().size == node.parameters.size
        }
    }

    private fun findFieldDoc(classDoc: GroovyClassDoc, node: FieldNode): GroovyProgramElementDoc? =
        classDoc.fields().find {
            it.name() == node.name
        }

    private fun findPropertyDoc(classDoc: GroovyClassDoc, node: PropertyNode): GroovyProgramElementDoc? =
        classDoc.properties().find {
            it.name() == node.name
        }

    private fun parseGroovyDoc(doc: GroovyProgramElementDoc): Documentation {
        val rawComment = doc.getRawCommentText()
        logger.warn(
            "DEBUG: Raw comment text found: ${if (rawComment.isNullOrBlank()) "EMPTY" else "PRESENT (${rawComment.length} chars)"}",
        )

        if (rawComment.isNullOrBlank()) return Documentation.EMPTY

        return parseDocComment(rawComment)
    }

    // Reuse the existing parsing logic for the raw comment content,
    // as it already handles @param, @return, etc. nicely.
    // We just use GroovyDocParser to FIND the comment.

    /**
     * Parse a doc comment string into a Documentation object.
     */
    private fun parseDocComment(docComment: String): Documentation {
        logger.warn("DEBUG: Parsing comment:\n$docComment")

        // Remove comment delimiters and asterisks
        val cleanedComment = docComment
            .replace(Regex("""/\*\*"""), "")
            .replace(Regex("""\*/"""), "")
            .lines()
            .joinToString("\n") { line ->
                line.trim().removePrefix("*").trim()
            }
            .trim()

        logger.warn("DEBUG: Cleaned comment:\n$cleanedComment")

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

        logger.warn("DEBUG: Extracted summary: '$summary'")

        // Extract description (everything before first @ tag)
        val descParts = cleanedComment.split(Regex("""(?=@\w+)"""))
        val description = descParts.firstOrNull()?.trim()?.let {
            if (it.startsWith("@")) "" else it
        } ?: ""

        logger.warn("DEBUG: Extracted description: '$description'")

        // Extract @param tags
        val params = paramRegex.findAll(cleanedComment).associate { match ->
            val paramName = match.groupValues[1]
            val paramDesc = match.groupValues[2].trim().replace(whitespaceRegex, " ")
            paramName to paramDesc
        }
        logger.warn("DEBUG: Extracted ${params.size} params: $params")

        // Extract @return tag
        val returnDoc = returnRegex.find(cleanedComment)?.groupValues?.get(1)?.trim()?.replace(whitespaceRegex, " ")
            ?: ""
        logger.warn("DEBUG: Extracted return: '$returnDoc'")

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
