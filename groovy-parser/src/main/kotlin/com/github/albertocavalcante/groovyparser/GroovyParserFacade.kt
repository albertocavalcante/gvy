package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParseResult
import com.github.albertocavalcante.groovyparser.api.ParserSeverity
import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import com.github.albertocavalcante.groovyparser.internal.ParserDiagnosticConverter
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Lightweight, LSP-free parser facade that produces Groovy ASTs and diagnostics.
 */
class GroovyParserFacade(private val parentClassLoader: ClassLoader = ClassLoader.getPlatformClassLoader()) {

    companion object {
        /**
         * Path to the service provider configuration file for AST transformations.
         * This file is located in META-INF/services/ and lists all global AST transformation classes.
         */
        private const val AST_TRANSFORMATION_SERVICE_FILE =
            "META-INF/services/org.codehaus.groovy.transform.ASTTransformation"

        /**
         * Comment character for Java ServiceLoader configuration files.
         *
         * Per [java.util.ServiceLoader] specification:
         * "Blank lines and comments beginning with '#' are ignored. Comments extend to the end of a line."
         *
         * Note: This is NOT related to Groovy syntax (which uses // for comments).
         * This is the Java SPI (Service Provider Interface) format.
         *
         * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html">ServiceLoader</a>
         */
        private const val SERVICE_FILE_COMMENT_CHAR = '#'
    }

    private val logger = LoggerFactory.getLogger(GroovyParserFacade::class.java)

    /**
     * Parse a Groovy source file.
     *
     * If compilation fails at a phase later than CONVERSION and produces a "Script fallback"
     * (where the class extends groovy.lang.Script due to unresolved superclass), this method
     * automatically retries at CONVERSION phase to preserve the original class structure.
     */
    fun parse(request: ParseRequest): ParseResult {
        val result = parseInternal(request)

        // Check if we should retry at an earlier phase
        if (shouldRetryAtConversion(request, result)) {
            logger.info("Detected Script fallback for ${request.uri}, retrying at CONVERSION phase")
            return parseInternal(request.copy(compilePhase = org.codehaus.groovy.control.Phases.CONVERSION))
        }

        return result
    }

    /**
     * Determines if we should retry parsing at CONVERSION phase.
     *
     * This handles the case where Groovy's compiler converts a class to a Script when
     * it cannot resolve the superclass (e.g., `extends spock.lang.Specification` when
     * Spock is not on the classpath).
     */
    private fun shouldRetryAtConversion(request: ParseRequest, result: ParseResult): Boolean {
        // Only retry if we compiled past CONVERSION phase
        if (request.compilePhase <= org.codehaus.groovy.control.Phases.CONVERSION) {
            logger.debug(
                "shouldRetryAtConversion: false - already at CONVERSION or earlier (phase=${request.compilePhase})",
            )
            return false
        }

        // NOTE: We do NOT check result.isSuccessful here.
        // Groovy often successfully compiles a broken class as a Script, so we must check the AST structure
        // regardless of compilation status.

        // Check for Script fallback pattern: single class extending groovy.lang.Script
        val ast = result.ast
        if (ast == null) {
            logger.debug("shouldRetryAtConversion: false - AST is null")
            return false
        }

        val classes = ast.classes
        if (classes.isEmpty()) {
            logger.debug("shouldRetryAtConversion: false - no classes in AST")
            return false
        }

        // Log class details for debugging - use safe call for superClass (null for interfaces)
        val classInfo = classes.map { "${it.name} (super=${it.superClass?.name ?: "null"})" }
        logger.info("shouldRetryAtConversion: checking classes: $classInfo")

        if (classes.size != 1) {
            logger.debug("shouldRetryAtConversion: false - ${classes.size} classes (expected 1)")
            return false
        }

        val cls = classes[0]
        // Use safe call for superClass - it's null for interfaces
        val isScript = cls.superClass?.name == "groovy.lang.Script"
        if (!isScript) {
            logger.info("shouldRetryAtConversion: false - not a Script for ${cls.name}")
            return false
        }

        // Check if the source code actually contains a class declaration.
        // If it doesn't, this is an intentional script (not a class that got converted to Script).
        // We only want to retry for classes that got incorrectly converted to Script due to
        // unresolved superclass (e.g., "extends spock.lang.Specification" when Spock isn't on classpath).
        val hasClassKeyword = request.content.contains(Regex("""\bclass\s+\w+"""))
        if (!hasClassKeyword) {
            logger.debug("shouldRetryAtConversion: false - intentional script (no class keyword)")
            return false
        }

        logger.info("shouldRetryAtConversion: true - class ${cls.name} was converted to Script")
        return true
    }

    private fun parseInternal(request: ParseRequest): ParseResult {
        // Collect all classpath entries for transformation scanning
        val allClasspath = request.classpath + request.sourceRoots
        val config = createCompilerConfiguration(allClasspath)
        // Use configured parent classloader (default is platform/bootstrap to avoid pollution)
        val classLoader = GroovyClassLoader(parentClassLoader)

        request.classpath.forEach { classLoader.addClasspath(it.toString()) }
        request.sourceRoots.forEach { classLoader.addClasspath(it.toString()) }

        val compilationUnit = CompilationUnit(config, null, classLoader)

        val source = StringReaderSource(request.content, config)
        val sourceUnit = SourceUnit(request.sourceUnitName, source, config, classLoader, compilationUnit.errorCollector)
        compilationUnit.addSource(sourceUnit)

        addWorkspaceSources(compilationUnit, request)

        var compilationFailed = false
        try {
            compilationUnit.compile(request.compilePhase)
        } catch (e: CompilationFailedException) {
            compilationFailed = true
            logger.debug("Compilation failed for ${request.uri}: ${e.message}")
        }

        val tokenIndex = GroovyTokenIndex.build(request.content)

        val ast = extractAst(compilationUnit)
        val diagnostics =
            ParserDiagnosticConverter.convert(compilationUnit.errorCollector, request.locatorCandidates).toMutableList()

        if (compilationFailed && diagnostics.none { it.severity == ParserSeverity.ERROR }) {
            val errorMessage = compilationUnit.errorCollector.lastError?.toString() ?: "Unknown error"
            diagnostics.add(
                com.github.albertocavalcante.groovyparser.api.ParserDiagnostic(
                    range = com.github.albertocavalcante.groovyparser.api.ParserRange.point(0, 0),
                    severity = ParserSeverity.ERROR,
                    message = "Compilation failed: $errorMessage",
                    source = "GroovyParser",
                ),
            )
        }

        val tracker = NodeRelationshipTracker()
        val astModel = RecursiveAstVisitor(tracker)
        ast?.let { astModel.visitModule(it, request.uri) }

        val symbolTable = SymbolTable()
        symbolTable.buildFromVisitor(astModel)

        logger.debug(
            "Parsed {} -> success={}, diagnostics={}",
            request.uri,
            ast != null && diagnostics.none { it.severity == ParserSeverity.ERROR },
            diagnostics.size,
        )

        return ParseResult(
            ast = ast,
            compilationUnit = compilationUnit,
            sourceUnit = sourceUnit,
            diagnostics = diagnostics,
            symbolTable = symbolTable,
            astModel = astModel,
            tokenIndex = tokenIndex,
        )
    }

    /**
     * Creates a compiler configuration with global AST transformations disabled.
     *
     * This prevents NoClassDefFoundError when the project classpath contains AST transformation
     * classes that conflict with the LSP's bundled Groovy version. For LSP purposes, we only
     * need AST structure for navigation/completion - not transformation execution.
     *
     * @param classpath The classpath entries to scan for AST transformations
     */
    private fun createCompilerConfiguration(classpath: List<Path>): CompilerConfiguration =
        CompilerConfiguration().apply {
            targetDirectory = null
            debug = true
            optimizationOptions = mapOf(
                CompilerConfiguration.GROOVYDOC to true,
            )
            sourceEncoding = "UTF-8"

            // Scan and disable external AST transformations to prevent classloader conflicts
            val externalTransforms = scanForAstTransformations(classpath)
            if (externalTransforms.isNotEmpty()) {
                disabledGlobalASTTransformations = externalTransforms
                logger.info("Disabled {} AST transformations from project classpath", externalTransforms.size)
            }
        }

    /**
     * Scans classpath entries for global AST transformation service files and extracts class names.
     *
     * This reads the META-INF/services/org.codehaus.groovy.transform.ASTTransformation files
     * without loading the transformation classes, which prevents NoClassDefFoundError.
     *
     * @param classpath The classpath entries to scan (JARs and directories)
     * @return Set of fully qualified transformation class names
     */
    @Suppress("TooGenericExceptionCaught") // Need to catch all IO/archive exceptions
    private fun scanForAstTransformations(classpath: List<Path>): Set<String> {
        val transformNames = mutableSetOf<String>()

        for (path in classpath) {
            try {
                when {
                    path.extension.equals("jar", ignoreCase = true) && Files.exists(path) -> {
                        scanJarForTransformations(path, transformNames)
                    }

                    Files.isDirectory(path) -> {
                        scanDirectoryForTransformations(path, transformNames)
                    }
                }
            } catch (e: Exception) {
                logger.debug("Failed to scan {} for transformations: {}", path, e.message)
            }
        }

        return transformNames
    }

    private fun scanJarForTransformations(jarPath: Path, names: MutableSet<String>) {
        JarFile(jarPath.toFile()).use { jar ->
            jar.getEntry(AST_TRANSFORMATION_SERVICE_FILE)?.let { entry ->
                jar.getInputStream(entry).bufferedReader().useLines { lines ->
                    lines.map { it.substringBefore(SERVICE_FILE_COMMENT_CHAR).trim() }
                        .filter { it.isNotBlank() }
                        .forEach { names += it }
                }
            }
        }
    }

    private fun scanDirectoryForTransformations(dirPath: Path, names: MutableSet<String>) {
        val serviceFilePath = dirPath.resolve(AST_TRANSFORMATION_SERVICE_FILE)
        if (Files.exists(serviceFilePath)) {
            Files.readAllLines(serviceFilePath)
                .map { it.substringBefore(SERVICE_FILE_COMMENT_CHAR).trim() }
                .filter { it.isNotBlank() }
                .forEach { names += it }
        }
    }

    private fun addWorkspaceSources(compilationUnit: CompilationUnit, request: ParseRequest) {
        request.workspaceSources
            .filter {
                it.toUri() != request.uri &&
                    it.extension.equals("groovy", ignoreCase = true) &&
                    it.isRegularFile()
            }
            .forEach { path ->
                runCatching {
                    logger.info("Adding workspace source: $path")
                    compilationUnit.addSource(path.toFile())
                }.onFailure { throwable ->
                    logger.debug("Failed adding workspace source {}: {}", path, throwable.message)
                }
            }
    }

    private fun extractAst(compilationUnit: CompilationUnit): ModuleNode? = try {
        val ast = compilationUnit.ast
        if (ast?.modules?.isNotEmpty() == true) {
            ast.modules.first()
        } else {
            logger.debug("No modules available in compilation unit")
            null
        }
    } catch (e: CompilationFailedException) {
        logger.debug("Failed to extract AST: ${e.message}")
        null
    }
}
