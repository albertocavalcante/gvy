// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.gls.documentation.Documentation
import com.github.javaparser.JavaParser
import com.github.javaparser.ParseStart
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Providers
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.comments.JavadocComment
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Service to inspect Java source files using JavaParser.
 *
 * Extracts class definitions, line numbers, and Javadoc documentation from .java files.
 * Uses JavaParser for supported Java syntax, without executing or loading the inspected source.
 */
class JavaSourceInspector internal constructor(private val parserFactory: () -> JavaParser) {
    constructor() : this(::newJavaSourceParser)

    private companion object {
        private const val PROBLEM_PREVIEW_LIMIT = 3
        private const val DOC_FALLBACK_LENGTH = 200
        private const val MAX_PARSE_CHARACTERS = 1024 * 1024
        private const val MAX_PARSE_BYTES = 4 * MAX_PARSE_CHARACTERS
    }

    private val logger = KotlinLogging.logger {}

    // Factories return an independent mutable parser for each call.
    private fun newParser(): JavaParser = parserFactory()

    private fun parseContent(content: String, checkCancelled: () -> Unit) =
        CancellableSourceReader(StringReader(content), checkCancelled).use { reader ->
            newParser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(reader))
        }

    private fun readSource(path: Path): String? {
        val resolved = path.toRealPath()
        if (
            !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(resolved) > MAX_PARSE_BYTES
        ) {
            return null
        }
        val bytes = Files.newInputStream(resolved, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_PARSE_BYTES + 1) }
        if (bytes.size > MAX_PARSE_BYTES) return null
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    }

    /**
     * Result of inspecting a Java class in a source file.
     */
    data class InspectionResult(
        val lineNumber: Int,
        val documentation: Documentation,
        val definition: SourceDefinition? = null,
    )

    /**
     * Inspect a Java source file to find the definition of the given class.
     *
     * @param sourcePath Path to the .java file
     * @param className Fully qualified class name (e.g., "java.util.Date")
     * @return InspectionResult containing line number and documentation, or null if not found
     */
    fun inspectClass(sourcePath: Path, className: String): InspectionResult? {
        if (!Files.exists(sourcePath)) {
            logger.debug { "Source file does not exist: $sourcePath" }
            return null
        }

        return try {
            val content = readSource(sourcePath) ?: return null
            inspectClassFromContent(content, className, sourcePath.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.warn(e) { "Failed to inspect Java source: $sourcePath" }
            null
        }
    }

    /**
     * Inspect a Java source file to find the definition of a specific method within a class.
     *
     * @param sourcePath Path to the .java file
     * @param className Fully qualified class name (e.g., "java.util.Date")
     * @param methodName Simple method name (e.g., "getTime")
     * @return InspectionResult containing line number and documentation, or null if not found
     */
    // A method name is insufficient to select an overload. Ambiguous names deliberately return no declaration.
    fun inspectMethod(sourcePath: Path, className: String, methodName: String): InspectionResult? {
        if (!Files.exists(sourcePath)) {
            logger.debug { "Source file does not exist: $sourcePath" }
            return null
        }

        return try {
            val content = readSource(sourcePath) ?: return null
            inspectMethodFromContent(content, className, methodName, sourcePath.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.warn(e) { "Failed to inspect method in Java source: $sourcePath" }
            null
        }
    }

    /**
     * Inspect Java source content directly (useful for testing).
     */
    fun inspectClassFromContent(
        content: String,
        className: String,
        sourceName: String = "<unknown>",
    ): InspectionResult? {
        val definition = inspectDefinitionFromContent(content, className, sourceName) ?: return null
        return InspectionResult(definition.nameRange.startLine + 1, definition.documentation, definition)
    }

    /** Exact syntactic declaration, including annotation, enum and record declarations. */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun inspectDefinitionFromContent(
        content: String,
        className: String,
        sourceName: String = "<unknown>",
        checkCancelled: () -> Unit = {},
    ): SourceDefinition? {
        checkCancelled()
        if (content.length > MAX_PARSE_CHARACTERS) return null
        return try {
            val unit = parseUnit(content, sourceName, checkCancelled) ?: return null
            val declaration = findTypeDeclaration(unit, className, checkCancelled) ?: return null
            val declarationRange = declaration.range.orElse(null)?.toSourceSpan() ?: return null
            val nameRange = declaration.name.range.orElse(null)?.toSourceSpan() ?: return null
            SourceDefinition(declarationRange, nameRange, extractJavadoc(declaration))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.warn(e) { "Failed to inspect Java declaration for $className" }
            null
        }
    }

    private fun parseUnit(content: String, sourceName: String, checkCancelled: () -> Unit): CompilationUnit? {
        val parsed = parseContent(content, checkCancelled)
        checkCancelled()
        if (!parsed.isSuccessful) {
            logger.debug {
                "Failed to parse Java source $sourceName: " +
                    parsed.problems.take(PROBLEM_PREVIEW_LIMIT).joinToString("; ") { it.message }
            }
            return null
        }
        return parsed.result.orElse(null)
    }

    private fun findTypeDeclaration(
        unit: CompilationUnit,
        binaryName: String,
        checkCancelled: () -> Unit,
    ): TypeDeclaration<*>? = unit.findAll(TypeDeclaration::class.java).singleOrNull { declaration ->
        checkCancelled()
        val owners = binaryTypeOwners(declaration, checkCancelled) ?: return@singleOrNull false
        val packageName = unit.packageDeclaration.map { it.nameAsString }.orElse("")
        val declaredName = if (packageName.isEmpty()) owners else "$packageName.$owners"
        declaredName == binaryName
    }

    /** Local and anonymous classes have compiler-assigned binary names and cannot be selected by source name alone. */
    private fun binaryTypeOwners(declaration: TypeDeclaration<*>, checkCancelled: () -> Unit): String? {
        val owners = mutableListOf<String>()
        var current = declaration
        while (true) {
            checkCancelled()
            owners.add(current.nameAsString)
            when (val parent = current.parentNode.orElse(null)) {
                is TypeDeclaration<*> -> current = parent
                is CompilationUnit -> break
                else -> return null
            }
        }
        return owners.asReversed().joinToString("$")
    }

    private fun com.github.javaparser.Range.toSourceSpan(): SourceSpan = SourceSpan(
        begin.line - 1,
        begin.column - 1,
        end.line - 1,
        end.column,
    )

    /**
     * Inspect Java source content to find a specific method within a class.
     */
    @Suppress("ReturnCount") // Multiple parsing validation checks require early returns
    fun inspectMethodFromContent(
        content: String,
        className: String,
        methodName: String,
        sourceName: String = "<unknown>",
        checkCancelled: () -> Unit = {},
    ): InspectionResult? {
        checkCancelled()
        if (content.length > MAX_PARSE_CHARACTERS) return null
        return try {
            val parseResult = parseContent(content, checkCancelled)
            checkCancelled()

            if (!parseResult.isSuccessful) {
                logger.debug {
                    "Failed to parse Java source $sourceName: " +
                        parseResult.problems.take(PROBLEM_PREVIEW_LIMIT).joinToString("; ") { it.message }
                }
                return null
            }

            val compilationUnit = parseResult.result.orElse(null) ?: return null

            // Match the complete package and lexical binary name, including nested owners.
            val classDecl = findTypeDeclaration(compilationUnit, className, checkCancelled)

            if (classDecl == null) {
                logger.debug { "Could not find class $className in $sourceName" }
                return null
            }

            // Find the method declaration within the class
            // Handle method overloading: filter all candidates and warn if ambiguous
            val methodCandidates = classDecl.members.filterIsInstance<MethodDeclaration>()
                .filter { method ->
                    checkCancelled()
                    method.nameAsString == methodName
                }
            val methodDecl = when (methodCandidates.size) {
                0 -> null
                1 -> methodCandidates[0]
                else -> {
                    logger.debug {
                        "Ambiguous method $className.$methodName (${methodCandidates.size} overloads) in $sourceName"
                    }
                    null
                }
            }

            if (methodDecl == null) {
                logger.debug { "Could not find method $methodName in class $className in $sourceName" }
                return null
            }

            val lineNumber = methodDecl.name.begin.map { it.line }.orElse(0)
            if (lineNumber <= 0) {
                logger.debug {
                    "Method $methodName in class $className found but has invalid line number in $sourceName"
                }
                return null
            }

            // Extract Javadoc from method
            val documentation = extractMethodJavadoc(methodDecl)

            logger.debug { "Found method $className.$methodName at line $lineNumber in $sourceName" }
            val declarationRange = methodDecl.range.orElse(null)?.toSourceSpan()
            val nameRange = methodDecl.name.range.orElse(null)?.toSourceSpan()
            val definition = if (declarationRange != null && nameRange != null) {
                SourceDefinition(declarationRange, nameRange, documentation)
            } else {
                null
            }
            InspectionResult(lineNumber, documentation, definition)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.warn(e) { "Failed to inspect method $methodName in $className" }
            null
        }
    }

    /**
     * Extract Javadoc documentation from a class declaration.
     *
     * Parses the Javadoc comment and converts it to our Documentation model,
     * similar to how IntelliJ renders hover documentation.
     */
    private fun extractJavadoc(classDecl: TypeDeclaration<*>): Documentation {
        val javadocComment = classDecl.comment.orElse(null) as? JavadocComment
            ?: return Documentation()

        return parseJavadoc(javadocComment)
    }

    /**
     * Extract Javadoc documentation from a method declaration.
     */
    private fun extractMethodJavadoc(methodDecl: com.github.javaparser.ast.body.MethodDeclaration): Documentation {
        val javadocComment = methodDecl.javadocComment.orElse(null)
            ?: return Documentation()

        return parseJavadoc(javadocComment)
    }

    /**
     * Parse a Javadoc comment into our Documentation model.
     *
     * Handles:
     * - Summary (first sentence/paragraph)
     * - Description (full body)
     * - @param, @return, @throws tags
     * - @see, @since, @author tags
     * - Code examples in {@code} and <pre> blocks
     */
    private fun parseJavadoc(javadocComment: JavadocComment): Documentation = try {
        val javadoc = javadocComment.parse()

        // Get the description (main body of the Javadoc)
        val description = javadoc.description.toText().trim()

        // Extract the first sentence as summary
        val summary = extractFirstSentence(description)

        // Build parameters map from @param tags
        // JavaParser's BlockTag.name is Optional<String>
        val params = javadoc.blockTags
            .filter { it.tagName == "param" }
            .filter { it.name.isPresent }
            .associate { tag ->
                tag.name.get() to tag.content.toText().trim()
            }

        // Extract @return tag
        val returnDoc = javadoc.blockTags
            .firstOrNull { it.tagName == "return" }
            ?.content?.toText()?.trim()

        // Extract @throws/@exception tags
        val throwsDocs = javadoc.blockTags
            .filter { it.tagName == "throws" || it.tagName == "exception" }
            .filter { it.name.isPresent }
            .associate { tag ->
                tag.name.get() to tag.content.toText().trim()
            }

        // Extract @since tag
        val since = javadoc.blockTags
            .firstOrNull { it.tagName == "since" }
            ?.content?.toText()?.trim()

        // Extract @see tags
        val seeAlso = javadoc.blockTags
            .filter { it.tagName == "see" }
            .map { it.content.toText().trim() }

        // Extract @author tags (join multiple authors)
        val author = javadoc.blockTags
            .filter { it.tagName == "author" }
            .joinToString(", ") { it.content.toText().trim() }

        // Extract @deprecated tag
        val deprecated = javadoc.blockTags
            .firstOrNull { it.tagName == "deprecated" }
            ?.content?.toText()?.trim() ?: ""

        Documentation(
            summary = summary,
            description = description,
            params = params,
            returnDoc = returnDoc ?: "",
            throws = throwsDocs,
            since = since ?: "",
            author = author,
            deprecated = deprecated,
            see = seeAlso,
        )
    } catch (e: Exception) {
        logger.debug { "Failed to parse Javadoc: ${e.message}" }
        // Fallback: use raw comment text
        val rawText = javadocComment.content.trim()
        Documentation(
            summary = extractFirstSentence(rawText),
            description = rawText,
        )
    }

    /**
     * Extract all method parameters from a Java source file for a specific class.
     *
     * This is useful for indexing all GDK methods at once instead of inspecting them one by one.
     *
     * @param sourcePath Path to the .java file
     * @param className Fully qualified class name (e.g., "org.codehaus.groovy.runtime.DefaultGroovyMethods")
     * @return Map of method signature to parameter names, or empty map if parsing fails
     *         Key format: "methodName(ParamType1,ParamType2,...)"
     *         Example: "each(Closure)" -> ["closure"]
     */
    fun extractAllMethodParameters(sourcePath: Path, className: String): Map<String, List<String>> {
        if (!Files.exists(sourcePath)) {
            logger.debug { "Source file does not exist: $sourcePath" }
            return emptyMap()
        }

        return try {
            val content = readSource(sourcePath) ?: return emptyMap()
            extractAllMethodParametersFromContent(content, className, sourcePath.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract method parameters from Java source: $sourcePath" }
            emptyMap()
        }
    }

    /**
     * Extract all method parameters from Java source content directly (useful for testing).
     */
    @Suppress("ReturnCount") // Multiple parsing validation checks require early returns
    fun extractAllMethodParametersFromContent(
        content: String,
        className: String,
        sourceName: String = "<unknown>",
        checkCancelled: () -> Unit = {},
    ): Map<String, List<String>> {
        checkCancelled()
        if (content.length > MAX_PARSE_CHARACTERS) return emptyMap()
        return try {
            val parseResult = parseContent(content, checkCancelled)
            checkCancelled()

            if (!parseResult.isSuccessful) {
                logger.debug {
                    "Failed to parse Java source $sourceName: " +
                        parseResult.problems.take(PROBLEM_PREVIEW_LIMIT).joinToString("; ") { it.message }
                }
                return emptyMap()
            }

            val compilationUnit = parseResult.result.orElse(null) ?: return emptyMap()

            // Match the complete package and lexical binary name.
            val classDecl = findTypeDeclaration(compilationUnit, className, checkCancelled)

            if (classDecl == null) {
                logger.debug { "Could not find class $className in $sourceName" }
                return emptyMap()
            }

            // Extract all methods with their parameter names
            val result = mutableMapOf<String, List<String>>()

            classDecl.members.filterIsInstance<MethodDeclaration>().forEach { method ->
                checkCancelled()
                val methodName = method.nameAsString
                val paramTypes = method.parameters.map { param ->
                    // Get simple type name (e.g., "Closure" instead of "groovy.lang.Closure")
                    // Strip generic type parameters (e.g., "List<String>" becomes "List")
                    val baseType = param.typeAsString
                        .substringBefore('<') // Strip generic type parameters
                        .substringAfterLast('.')
                    // Normalize varargs to array notation to match reflection (Object... -> Object[])
                    if (param.isVarArgs) "$baseType[]" else baseType
                }
                val paramNames = method.parameters.map { it.nameAsString }

                if (paramNames.isNotEmpty()) {
                    val signature = "$methodName(${paramTypes.joinToString(",")})"
                    result[signature] = paramNames
                }
            }

            logger.debug { "Extracted ${result.size} methods from class $className in $sourceName" }
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract method parameters from $className" }
            emptyMap()
        }
    }

    /**
     * Extract the first sentence from a documentation string.
     *
     * A sentence ends at:
     * - Period followed by whitespace or end of string
     * - First paragraph break (<p> or blank line)
     */
    private fun extractFirstSentence(text: String): String {
        if (text.isBlank()) return ""

        // Remove HTML tags for cleaner extraction
        val cleanText = stripDocumentationMarkup(text)
            .replace(Regex("\\s+"), " ")
            .trim()

        // Find first sentence end
        val sentenceEnd = cleanText.indexOfFirst { it == '.' || it == '!' || it == '?' }
        return if (sentenceEnd > 0 && sentenceEnd < cleanText.length - 1) {
            cleanText.substring(0, sentenceEnd + 1).trim()
        } else {
            // Take first 200 chars as fallback
            cleanText.take(DOC_FALLBACK_LENGTH).trim()
        }
    }
}

private fun newJavaSourceParser(): JavaParser = JavaParser(
    ParserConfiguration().apply {
        setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
        setAttributeComments(true)
        setTabSize(1)
    },
)

/** Unclosed markup is literal text; do not rescan its entire remaining suffix for each opening character. */
private fun stripDocumentationMarkup(text: String): String = buildString(text.length) {
    var offset = 0
    var htmlCanClose = true
    var inlineCanClose = true
    while (offset < text.length) {
        if (htmlCanClose && text[offset] == '<') {
            val end = text.indexOf('>', offset + 1)
            if (end > offset + 1) {
                append(' ')
                offset = end + 1
                continue
            }
            if (end < 0) htmlCanClose = false
        }
        if (inlineCanClose && text.startsWith("{@", offset)) {
            val end = text.indexOf('}', offset + 2)
            if (end > offset + 2) {
                offset = end + 1
                continue
            }
            if (end < 0) inlineCanClose = false
        }
        append(text[offset++])
    }
}
