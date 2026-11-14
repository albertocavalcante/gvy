// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.annotations.SourceAnnotationCapture
import com.github.albertocavalcante.groovyparser.api.AnalysisCompilerPolicy
import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import com.github.albertocavalcante.groovyparser.internal.CompilerMessageSource
import com.github.albertocavalcante.groovyparser.internal.DiagnosticDocument
import com.github.albertocavalcante.groovyparser.internal.ParserDiagnosticConverter
import com.github.albertocavalcante.groovyparser.internal.SourceSnapshot
import com.github.albertocavalcante.groovyparser.internal.SyntaxSourceContext
import com.github.albertocavalcante.groovyparser.internal.hasClassDeclaration
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.gvy.common.ast.isStubSourceName
import com.github.albertocavalcante.nativeapi.ExcludedSource
import com.github.albertocavalcante.nativeapi.Fallback
import com.github.albertocavalcante.nativeapi.NativeClasspathInputs
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseOutcome
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import com.github.albertocavalcante.nativeapi.ParserSeverity
import groovy.lang.GroovyClassLoader
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.ClassNodeResolver
import org.codehaus.groovy.control.ClassNodeResolver.LookupResult
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.codehaus.groovy.control.messages.ExceptionMessage
import org.codehaus.groovy.control.messages.LocatedMessage
import org.codehaus.groovy.control.messages.Message
import org.codehaus.groovy.control.messages.SimpleMessage
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Path to the service provider configuration file for AST transformations.
 * This file is located in META-INF/services/ and lists all global AST transformation classes.
 */
internal const val AST_TRANSFORMATION_SERVICE_FILE =
    "META-INF/services/org.codehaus.groovy.transform.ASTTransformation"

private const val COMPILATION_FAILED_MESSAGE_PREFIX = "Compilation failed:"
private const val LOG_CLASS_INVENTORY_LIMIT = 16
private const val LOG_CLASS_NAME_LIMIT = 80
private const val LOG_URI_LIMIT = 160
private const val UNATTRIBUTED_ERROR_REASON =
    "an open buffer was dropped because the compile failed with an error no source could be blamed for"
private const val SCRIPT_FALLBACK_REASON =
    "a class was compiled as a Script because its superclass could not be resolved"

/** A useful raw cause, before source-location filtering discards unit-wide errors. */
internal fun firstCompilerCause(messages: List<Message>): String? = messages.asSequence().mapNotNull { message ->
    val text = when (message) {
        is SyntaxErrorMessage -> {
            val cause = message.cause
            val detail = cause.originalMessage?.let(::firstMeaningfulLine)
            detail?.let { listOfNotNull(cause.sourceLocator?.substringAfterLast('/'), it).joinToString(": ") }
        }
        is LocatedMessage -> message.message
        is SimpleMessage -> message.message
        is ExceptionMessage -> message.cause?.message
        // Unknown compiler messages may execute arbitrary write() logic; never render them for fallback text.
        else -> null
    }
    text?.let(::firstMeaningfulLine)?.let(ParseOutcome::truncate)
}.firstOrNull()

private fun firstMeaningfulLine(text: String): String? = text.lineSequence()
    .map { it.trim() }
    .firstOrNull { it.isNotBlank() && !isCompilerBoilerplate(it) }

private fun isCompilerBoilerplate(text: String): Boolean {
    val trimmed = text.trim()
    val withoutPrefix = if (trimmed.startsWith(COMPILATION_FAILED_MESSAGE_PREFIX, ignoreCase = true)) {
        trimmed.substring(COMPILATION_FAILED_MESSAGE_PREFIX.length)
    } else {
        trimmed
    }
    val stripped = withoutPrefix.trim().trimEnd(':').trim()
    return stripped.equals("startup failed", ignoreCase = true) ||
        stripped.equals("compilation failed", ignoreCase = true)
}

/** The first meaningful raw compiler error, or a filtered diagnostic when none is available. */
private fun firstErrorMessage(result: ParseResult): String? =
    firstCompilerCause(result.compilationUnit.errorCollector.errors.orEmpty())
        ?: result.diagnostics.firstOrNull { it.severity == ParserSeverity.ERROR }?.message

private val compileLogger = KotlinLogging.logger {}

/** Outcome of a compile that did not complete, with the thrown exception's message when it has one. */
private class CompileFailure(val message: String? = null)

private fun compileCatching(compilationUnit: CompilationUnit, request: ParseRequest): CompileFailure? = try {
    compilationUnit.compile(request.compilePhase)
    null
} catch (e: CompilationFailedException) {
    compileLogger.debug { "Compilation failed for ${request.uri}: ${e.message}" }
    CompileFailure(e.message)
} catch (e: NoClassDefFoundError) {
    // A transform needed a class the server does not ship; surface it as a diagnostic for this
    // source instead of aborting the caller (e.g. workspace indexing).
    val cause = e.cause?.message?.let { " (caused by: $it)" }.orEmpty()
    val message = "NoClassDefFoundError: ${e.message}$cause"
    compileLogger.warn(e) { "Compilation of ${request.uri} hit a missing class" }
    CompileFailure(message)
} finally {
    // The unit is cached with the result; don't keep the caller's job alive through it.
    compilationUnit.progressCallback = null
}

/**
 * True when the project class path can supply [name]. Only loads it (no initialisation, no script lookup), so no
 * project code runs; used to tell "a transform from a project jar that analysis never runs" from a real typo.
 */
private fun GroovyClassLoader.hasClass(name: String): Boolean = runCatching { loadClass(name, false, true) }.isSuccess

/** A source unit name as a normalized path: a plain path, or a `file:` URL for loader-found sources. */
private fun locatorPath(locator: String): Any = if (locator.startsWith("file:")) {
    runCatching { Path.of(URI(locator)).toAbsolutePath().normalize() }.getOrDefault(locator)
} else {
    runCatching { Path.of(locator).toAbsolutePath().normalize() }.getOrDefault(locator)
}

private fun urlPath(url: URL): Path? = if (url.protocol == "file") {
    runCatching { Path.of(url.toURI()).toAbsolutePath().normalize() }.getOrNull()
} else {
    null
}

/**
 * Lightweight, LSP-free parser facade that produces Groovy ASTs and diagnostics.
 */
class GroovyParserFacade internal constructor(
    private val parentClassLoader: ClassLoader,
    private val classLoaderFactory: (ClassLoader) -> SdkFallbackGroovyClassLoader,
) {
    @JvmOverloads
    constructor(parentClassLoader: ClassLoader = ClassLoader.getPlatformClassLoader()) :
        this(parentClassLoader, ::SdkFallbackGroovyClassLoader)

    companion object {
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

        /**
         * Shared transform loader that has access to bundled Groovy via the LSP's classloader.
         * This allows Groovy's built-in transformations to work correctly while project-specific
         * transformations are disabled.
         *
         * This is a singleton to prevent resource leaks - GroovyClassLoader extends URLClassLoader
         * which holds native resources. Since no project classpath is added, reusing this instance
         * is safe and efficient.
         */
        private val transformLoader: GroovyClassLoader by lazy {
            GroovyClassLoader(GroovyParserFacade::class.java.classLoader)
        }
    }

    private val logger = KotlinLogging.logger {}

    /**
     * Parse a Groovy source file.
     *
     * If compilation fails at a phase later than CONVERSION and produces a "Script fallback"
     * (where the class extends groovy.lang.Script due to unresolved superclass), this method
     * automatically retries at CONVERSION phase to preserve the original class structure.
     *
     * Neither the exclusion of failing sources nor the retry is silent: [ParseResult.outcome] records them.
     */
    fun parse(request: ParseRequest): ParseResult {
        // Structural parses do not bind external symbols. Preserve only immutable input paths;
        // source navigation must never mistake these unknown observations for a compiled context.
        val inputs = if (request.compilePhase < Phases.SEMANTIC_ANALYSIS) {
            NativeClasspathInputs(request.classpath, request.sourceRoots)
        } else {
            NativeClasspathInputs.capture(request.classpath, request.sourceRoots, request.checkCancelled)
        }
        return parse(
            request.copy(classpath = inputs.classpath, sourceRoots = inputs.sourceRoots),
            excluded = emptyList(),
            inputs = inputs,
        )
    }

    /** [excluded] accumulates the sources set aside by the recursive recompiles so far. */
    private fun parse(
        request: ParseRequest,
        excluded: List<ExcludedSource>,
        inputs: NativeClasspathInputs,
    ): ParseResult {
        var compileFailure: CompileFailure? = null
        return ParseResultOwner(
            MissingClassStubs.compile(request) { retryRequest, stubs ->
                parseInternal(retryRequest, inputs, stubs) { compileFailure = it }
            },
        ).use { owner ->
            val result = owner.result

            // Other sources in the unit (open buffers mid-edit, or a broken file on disk, e.g. one extending
            // a class that is not on the classpath) fail the whole compilation unit. That would cost this
            // file its AST, or its type resolution via the CONVERSION retry below, and show their error
            // here. Classes that merely cannot be resolved never get here: they are stubbed above. Compile
            // again without the sources that failed. A failure that cannot be attributed to
            // a source drops the open buffers (most likely mid-edit) and otherwise falls through.
            if (
                request.openSources.isNotEmpty() ||
                request.workspaceSources.isNotEmpty() ||
                request.workspaceClassLocator != null
            ) {
                val failing = failingOtherSources(request, result)
                val newlyFailing = failing.workspace - request.excludedSources
                if (failing.open.isNotEmpty() || newlyFailing.isNotEmpty()) {
                    logger.debug { "Sources with errors excluded from compiling ${request.uri}: $failing" }
                    val recovered = owner.replaceWith {
                        parse(
                            request.copy(
                                openSources = request.openSources.filterKeys { it !in failing.open },
                                workspaceSources = request.workspaceSources.filterNot { it in newlyFailing },
                                excludedSources = request.excludedSources + newlyFailing,
                            ),
                            excluded + failing.excludedSources(),
                            inputs,
                        )
                    }
                    return@use owner.publish(recovered)
                }
                if (failing.unattributed && request.openSources.isNotEmpty()) {
                    logger.debug { "Unattributed compile errors for ${request.uri}; compiling without open buffers" }
                    val dropped = request.openSources.keys.map { sourceUri ->
                        ExcludedSource(sourceUri.toString(), UNATTRIBUTED_ERROR_REASON)
                    }
                    val recovered = owner.replaceWith {
                        parse(request.copy(openSources = emptyMap()), excluded + dropped, inputs)
                    }
                    return@use owner.publish(recovered)
                }
            }

            val finalResult = retryAtEarlierPhaseIfNeeded(request, owner, inputs, compileFailure)
            val unlocated = LocatingClassNodeResolver.unlocatedBy(finalResult.compilationUnit)
            owner.publish(
                finalResult.copy(
                    outcome = finalResult.outcome.copy(excludedSources = excluded, unlocatedClasses = unlocated),
                ),
            )
        }
    }

    /**
     * Other sources of the compilation unit whose errors failed it, and whether some error could not be
     * attributed to any source.
     */
    private data class FailingSources(
        val open: Set<URI>,
        val workspace: Set<Path>,
        val unattributed: Boolean,
        /** The first error of each failing source, keyed by its URI or path as a string. */
        val reasons: Map<String, String>,
    ) {
        fun excludedSources(): List<ExcludedSource> = reasons.map { (source, reason) -> ExcludedSource(source, reason) }
    }

    /**
     * Attributes [result]'s errors to the open buffers and workspace files that caused them. Locators
     * are compared as normalized paths: workspace files are named after their path, and a file the class
     * loader pulled in from a source root is named by its `file:` URL.
     */
    private fun failingOtherSources(request: ParseRequest, result: ParseResult): FailingSources {
        val errors = result.compilationUnit.errorCollector.errors.orEmpty()
        val openByKey = request.openSources.keys.associateBy { fileKey(it) }
        val workspaceByKey = request.workspaceSources.associateBy { it.toAbsolutePath().normalize() }
        val locatedPaths = LocatingClassNodeResolver.addedBy(result.compilationUnit)
        val open = mutableSetOf<URI>()
        val workspace = mutableSetOf<Path>()
        val reasons = linkedMapOf<String, String>()
        var unattributed = false
        for (message in errors) {
            val locator = CompilerMessageSource.locator(message)
            when (locator) {
                null -> unattributed = true
                else -> {
                    val key = locatorPath(locator)
                    if (key == fileKey(request.uri) || locator == request.sourceUnitName) continue
                    // A buffer and its disk copy are one source: both leave the unit, under one reason.
                    val buffer = openByKey[key]?.also { open += it }
                    // A file the class locator added (#1425) fails the unit like a workspace source does; a file
                    // Groovy found by its name, or a stub, is not the locator's and is left to the other rules.
                    val located = (key as? Path)?.takeIf { key in locatedPaths }
                    val bufferPath = (key as? Path)?.takeIf { buffer != null }
                    val disk = (workspaceByKey[key] ?: located ?: bufferPath)?.also { workspace.add(it) }
                    val source = buffer?.toString() ?: disk?.toString()
                    val reason = (message as? SyntaxErrorMessage)?.cause?.originalMessage
                        ?: ParserDiagnosticConverter.textOf(message)
                    if (source != null) reasons.putIfAbsent(source, ParseOutcome.truncate(reason))
                }
            }
        }
        return FailingSources(open, workspace, unattributed, reasons)
    }

    private fun retryAtEarlierPhaseIfNeeded(
        request: ParseRequest,
        owner: ParseResultOwner,
        inputs: NativeClasspathInputs,
        compileFailure: CompileFailure?,
    ): ParseResult {
        val result = owner.result
        val fallback = when {
            shouldRetryAtConversion(request, result) -> Fallback.SCRIPT_FALLBACK
            shouldRetryOnCompilationFailure(request, result, compileFailure) -> Fallback.COMPILATION_FAILED
            else -> return result
        }
        logger.debug {
            "Detected $fallback for ${request.uri.toString().take(LOG_URI_LIMIT)}, retrying at CONVERSION phase"
        }

        val reason = firstErrorMessage(result) ?: SCRIPT_FALLBACK_REASON
        val ownExceptions = result.compilationUnit.errorCollector.errors.orEmpty()
            .filterIsInstance<ExceptionMessage>()
            .filter { CompilerMessageSource.locator(it) == request.sourceUnitName }
            .map(ParserDiagnosticConverter::textOf).toSet()
        val retainedDiagnostics = result.diagnostics.filter { it.message in ownExceptions }
        val retried = owner.replaceWith {
            parseInternal(request.copy(compilePhase = Phases.CONVERSION), inputs = inputs)
        }
        return retried.copy(
            diagnostics = (retainedDiagnostics + retried.diagnostics).distinct(),
            outcome = retried.outcome.copy(
                requestedPhase = request.compilePhase,
                achievedPhase = Phases.CONVERSION,
                fallback = fallback,
                fallbackReason = ParseOutcome.truncate(reason),
            ),
        )
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
        if (request.compilePhase <= Phases.CONVERSION) {
            logger.debug {
                "shouldRetryAtConversion: false - already at CONVERSION or earlier (phase=${request.compilePhase})"
            }
            return false
        }

        // NOTE: We do NOT check result.isSuccessful here.
        // Groovy often successfully compiles a broken class as a Script, so we must check the AST structure
        // regardless of compilation status.

        var shouldRetry = true

        // Check for Script fallback pattern: single class extending groovy.lang.Script
        val ast = result.ast
        if (ast == null) {
            logger.debug { "shouldRetryAtConversion: false - AST is null" }
            shouldRetry = false
        }

        val classes = ast?.classes.orEmpty()
        if (shouldRetry && classes.isEmpty()) {
            logger.debug { "shouldRetryAtConversion: false - no classes in AST" }
            shouldRetry = false
        }

        if (shouldRetry) {
            logger.trace {
                val names = classes.take(LOG_CLASS_INVENTORY_LIMIT).joinToString { it.name.take(LOG_CLASS_NAME_LIMIT) }
                val omitted = (classes.size - LOG_CLASS_INVENTORY_LIMIT).coerceAtLeast(0)
                "shouldRetryAtConversion: class inventory: [$names] ($omitted omitted)"
            }
        }

        if (shouldRetry && classes.size != 1) {
            logger.debug { "shouldRetryAtConversion: false - ${classes.size} classes (expected 1)" }
            shouldRetry = false
        }

        if (!shouldRetry) return false

        val cls = classes.firstOrNull()
        val isScript = cls?.superClass?.name == "groovy.lang.Script"
        if (shouldRetry && !isScript) {
            logger.debug {
                "shouldRetryAtConversion: false - not a Script for ${cls?.name?.take(LOG_CLASS_NAME_LIMIT)}"
            }
            shouldRetry = false
        }

        // Check if the source code actually contains a class declaration.
        // If it doesn't, this is an intentional script (not a class that got converted to Script).
        if (shouldRetry && !hasClassDeclaration(request.content, request.checkCancelled)) {
            logger.debug { "shouldRetryAtConversion: false - intentional script (no class declaration)" }
            shouldRetry = false
        }

        if (shouldRetry) {
            logger.debug {
                "shouldRetryAtConversion: true - class ${cls?.name?.take(LOG_CLASS_NAME_LIMIT)} was converted to Script"
            }
        }

        return shouldRetry
    }

    private fun shouldRetryOnCompilationFailure(
        request: ParseRequest,
        result: ParseResult,
        failure: CompileFailure?,
    ): Boolean {
        if (request.compilePhase <= Phases.CONVERSION) {
            logger.debug {
                "shouldRetryOnCompilationFailure: false - already at CONVERSION or earlier " +
                    "(phase=${request.compilePhase})"
            }
            return false
        }

        if (failure == null) return false
        val unexpected = result.compilationUnit.errorCollector.errors.orEmpty().any { it is ExceptionMessage }
        val hasError = result.diagnostics.any { it.severity == ParserSeverity.ERROR }
        val explained = result.diagnostics.any { it.code == ParserDiagnosticConverter.PROJECT_TRANSFORM_NOT_RUN }
        return unexpected || (!hasError && !explained) || result.diagnostics.any {
            it.source == "GroovyParser" && it.message.startsWith(COMPILATION_FAILED_MESSAGE_PREFIX)
        }
    }

    private fun createCompilationContext(
        request: ParseRequest,
        config: CompilerConfiguration,
        classLoader: SdkFallbackGroovyClassLoader,
        stubs: MissingClassStubs?,
    ): Pair<CompilationUnit, SourceUnit> {
        // Use 4-arg constructor with separate transform loader for proper classloader isolation.
        // transformLoader is a singleton (see companion object) to prevent resource leaks.
        val compilationUnit = CompilationUnit(config, null, classLoader, transformLoader)
        val source = StringReaderSource(request.content, config)
        val sourceUnit = SourceUnit(
            request.sourceUnitName,
            source,
            config,
            classLoader,
            compilationUnit.errorCollector,
        )
        compilationUnit.addSource(sourceUnit)

        addWorkspaceSources(compilationUnit, request, config, classLoader)
        LocatingClassNodeResolver.install(compilationUnit, request)
        stubs?.addTo(compilationUnit, config, classLoader)

        // Groovy's compile(phase) restarts from INITIALIZATION on every call, so it cannot be driven phase by
        // phase. Its progress callback runs after each phase instead; throwing from it aborts the compile.
        // CancellationException is not a CompilationFailedException, so it propagates past the catch below.
        // Groovy also reports per-source progress (context is a SourceUnit) from inside a source operation,
        // where it would wrap anything thrown in a GroovyBugError; only the per-phase report is used.
        compilationUnit.progressCallback = CompilationUnit.ProgressCallback { context, _ ->
            if (context is CompilationUnit) request.checkCancelled()
        }
        return compilationUnit to sourceUnit
    }

    private fun parseInternal(
        request: ParseRequest,
        inputs: NativeClasspathInputs,
        stubs: MissingClassStubs? = null,
        onCompileFailure: (CompileFailure?) -> Unit = {},
    ): ParseResult {
        // Before any work: building the configuration below opens every classpath jar.
        request.checkCancelled()
        val annotationCapture = SourceAnnotationCapture(request)
        try {
            return parseInternalWithCapture(request, inputs, stubs, annotationCapture, onCompileFailure)
        } finally {
            annotationCapture.discard()
        }
    }

    private fun parseInternalWithCapture(
        request: ParseRequest,
        inputs: NativeClasspathInputs,
        stubs: MissingClassStubs?,
        annotationCapture: SourceAnnotationCapture,
        onCompileFailure: (CompileFailure?) -> Unit,
    ): ParseResult {
        // Collect all classpath entries for transformation scanning
        val allClasspath = request.classpath + request.sourceRoots
        val config = createCompilerConfiguration(allClasspath, annotationCapture)

        val classLoader = createClassLoader(request)

        return classLoader.closeOnFailure {
            val (compilationUnit, sourceUnit) = createCompilationContext(request, config, classLoader, stubs)

            val failure = compileCatching(compilationUnit, request)
            onCompileFailure(failure)
            val compilationFailed = failure != null
            val failureMessage = failure?.message

            val tokenIndex = GroovyTokenIndex.build(request.content)

            val ast = extractAst(compilationUnit, request.sourceUnitName)
            val diagnostics =
                ParserDiagnosticConverter.convert(
                    compilationUnit.errorCollector,
                    request.locatorCandidates,
                    SyntaxSourceContext(SourceSnapshot(request.content), tokenIndex, setOf(request.sourceUnitName)),
                    document = DiagnosticDocument(request.content, request.sourceUnitName),
                ) { name ->
                    classLoader.hasClass(name)
                }.toMutableList()

            // A failure explained only by "this project transform is not run" is already reported; no synthetic error
            val explained = diagnostics.any { it.code == ParserDiagnosticConverter.PROJECT_TRANSFORM_NOT_RUN }
            if (compilationFailed && !explained && diagnostics.none { it.severity == ParserSeverity.ERROR }) {
                // ErrorCollector.getLastError throws when nothing was collected, so read the list instead.
                val collectedCause = firstCompilerCause(compilationUnit.errorCollector.errors.orEmpty())
                val errorMessage = collectedCause ?: failureMessage ?: "Unknown error"
                diagnostics.add(
                    com.github.albertocavalcante.nativeapi.ParserDiagnostic(
                        range = com.github.albertocavalcante.nativeapi.ParserRange.point(0, 0),
                        severity = ParserSeverity.ERROR,
                        message = "$COMPILATION_FAILED_MESSAGE_PREFIX $errorMessage",
                        source = "GroovyParser",
                    ),
                )
            }

            val tracker = NodeRelationshipTracker()
            val astModel = RecursiveAstVisitor(tracker)
            ast?.let { astModel.visitModule(it, request.uri) }

            val symbolTable = SymbolTable()
            symbolTable.buildFromVisitor(astModel)

            logger.debug {
                "Parsed ${request.uri} -> success=${ast != null && diagnostics.none {
                    it.severity == ParserSeverity.ERROR
                }}, diagnostics=${diagnostics.size}"
            }

            ParseResult(
                ast = ast,
                compilationUnit = compilationUnit,
                sourceUnit = sourceUnit,
                diagnostics = diagnostics,
                symbolTable = symbolTable,
                astModel = astModel,
                tokenIndex = tokenIndex,
                outcome = ParseOutcome(requestedPhase = request.compilePhase),
                sourceAnnotations = annotationCapture.finish(ast, compilationFailed),
                classpathInputs = inputs.withCompilerSdk(classLoader.compilerSdk),
            )
        }
    }

    /**
     * The compile's class loader. SdkFallbackGroovyClassLoader prevents discovery of project AST
     * transformations, which would cause NoClassDefFoundError due to classloader isolation (it returns
     * empty results for META-INF/services/...ASTTransformation). Sources excluded for failing the
     * compile are hidden from its source lookup, so they are not loaded back from a source root.
     */
    private fun createClassLoader(request: ParseRequest): SdkFallbackGroovyClassLoader {
        val classLoader = classLoaderFactory(parentClassLoader)
        return classLoader.closeOnFailure {
            request.classpath.forEach { classLoader.addClasspath(it.toString()) }
            request.sourceRoots.forEach { classLoader.addClasspath(it.toString()) }
            if (request.compilePhase >= Phases.SEMANTIC_ANALYSIS) classLoader.selectSdkFallback()
            val sourcePathAdmission = request.sourcePathAdmission
            if (request.excludedSources.isNotEmpty() || sourcePathAdmission != null) {
                val excluded = request.excludedSources.mapTo(HashSet()) { it.toAbsolutePath().normalize() }
                val sources = classLoader.resourceLoader
                classLoader.resourceLoader = groovy.lang.GroovyResourceLoader { name ->
                    sources.loadGroovySource(name)?.takeIf { url ->
                        val path = urlPath(url) ?: return@takeIf true
                        path !in excluded && sourcePathAdmission?.invoke(path) != false
                    }
                }
            }
            classLoader
        }
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
    private fun createCompilerConfiguration(
        classpath: List<Path>,
        annotationCapture: SourceAnnotationCapture,
    ): CompilerConfiguration = CompilerConfiguration().apply {
        targetDirectory = null
        debug = true
        optimizationOptions = mapOf(
            CompilerConfiguration.GROOVYDOC to true,
        )
        sourceEncoding = "UTF-8"

        // Scan and disable external AST transformations to prevent classloader conflicts
        val externalTransforms = scanForAstTransformations(classpath)
        if (externalTransforms.isNotEmpty()) {
            logger.info { "Disabled ${externalTransforms.size} AST transformations from project classpath" }
        }
        // Policy first (it sets the always-disabled globals and strips code-executing annotations),
        // then widen the disabled set with transforms found on the project classpath.
        addCompilationCustomizers(annotationCapture)
        AnalysisCompilerPolicy.apply(this)
        disabledGlobalASTTransformations = externalTransforms + disabledGlobalASTTransformations
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
                logger.debug { "Failed to scan $path for transformations: ${e.message}" }
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

    private fun addWorkspaceSources(
        compilationUnit: CompilationUnit,
        request: ParseRequest,
        config: CompilerConfiguration,
        classLoader: GroovyClassLoader,
    ) {
        // In MINIMAL mode, skip workspace sources for fast, isolated parsing (Issue #743)
        if (request.parseMode == ParseMode.MINIMAL) {
            logger.trace { "Skipping workspace sources in MINIMAL mode for ${request.uri}" }
            return
        }

        // Unsaved buffers replace their on-disk copies; adding both would declare each class twice.
        // Compared as paths: editors percent-encode URIs differently from Path.toUri().
        val self = fileKey(request.uri)
        val openSources = request.openSources.filterKeys { uri ->
            fileKey(uri) != self && (uri.path ?: "").endsWith(".groovy", ignoreCase = true)
        }
        val openKeys = openSources.keys.mapTo(HashSet()) { fileKey(it) }
        openSources.forEach { (uri, text) ->
            runCatching {
                logger.trace { "Adding open source: $uri" }
                val name = uri.path ?: uri.toString()
                compilationUnit.addSource(
                    SourceUnit(
                        name,
                        StringReaderSource(text, config),
                        config,
                        classLoader,
                        compilationUnit.errorCollector,
                    ),
                )
            }.onFailure { throwable ->
                logger.trace { "Failed adding open source $uri: ${throwable.message}" }
            }
        }

        request.workspaceSources
            .filter {
                it.toUri() != request.uri &&
                    fileKey(it.toUri()) !in openKeys &&
                    it.extension.equals("groovy", ignoreCase = true) &&
                    it.isRegularFile()
            }
            .forEach { path ->
                runCatching {
                    logger.trace { "Adding workspace source: $path" }
                    compilationUnit.addSource(path.toFile())
                }.onFailure { throwable ->
                    logger.trace { "Failed adding workspace source $path: ${throwable.message}" }
                }
            }
    }

    /** A `file:` URI as a normalized path (decoded, platform case rules); other URIs unchanged. */
    private fun fileKey(uri: URI): Any = if (uri.scheme.equals("file", ignoreCase = true)) {
        runCatching { Path.of(uri).toAbsolutePath().normalize() }.getOrDefault(uri)
    } else {
        uri
    }

    /**
     * Extracts the AST module for the requested source file.
     *
     * When workspace sources are included, multiple modules may exist in the compilation unit.
     * This method ensures deterministic selection by matching the requested source's name.
     *
     * @param compilationUnit The compiled unit containing all modules
     * @param sourceUnitName The name of the requested source (from ParseRequest.sourceUnitName)
     * @return The module matching the requested source, or first module as fallback
     */
    private fun extractAst(compilationUnit: CompilationUnit, sourceUnitName: String): ModuleNode? = try {
        // Stubs are not the user's code: they are never the module returned.
        val modules = compilationUnit.ast?.modules.orEmpty().filterNot { isStubSourceName(it.context?.name) }
        if (modules.isNotEmpty()) {
            // Deterministic module selection: find the module matching the requested source
            modules.find { module ->
                module.context?.name == sourceUnitName
            } ?: run {
                // Fallback to first module if no match (e.g., if source was parsed with different name)
                logger.debug {
                    "Requested source '$sourceUnitName' not found in ${modules.size} modules, using first"
                }
                modules.first()
            }
        } else {
            logger.debug { "No modules available in compilation unit" }
            null
        }
    } catch (e: CompilationFailedException) {
        logger.debug { "Failed to extract AST: ${e.message}" }
        null
    }
}

/**
 * Groovy's class lookup, then the workspace class index (#1425): a name Groovy cannot find on the classpath or as
 * a file named after it is looked up by the exact fully qualified name of its top-level class (the part before
 * any `$`, so `Outer2$Inner` asks for `Outer2`), and the one file declaring it is added to the unit, from its open
 * buffer when it has one. Groovy then compiles it and resolves the name from it, as it does for a file it finds by
 * name. Excluded sources are never added, so a file that failed the unit stays out on the recompile.
 *
 * Each file is answered once. Groovy restarts resolution when an answer is a source and expects that source to be
 * queued; once the file is in the unit, its own members probe names such as `Outer2$Specification`, and answering
 * those with the file again would restart with nothing queued. Added files resolve through this resolver too, as
 * a hierarchy needs, so at most [MAX_LOCATED_SOURCES] files are added per compile; a name past the cap is left
 * unresolved and recorded, so [MissingClassStubs] stubs it and [ParseOutcome.unlocatedClasses] reports it.
 */
internal class LocatingClassNodeResolver(
    private val locator: (String) -> List<Path>,
    excludedSources: Set<Path>,
    private val buffers: Map<Path, Pair<URI, String>>,
) : ClassNodeResolver() {
    private val excluded = excludedSources.mapTo(HashSet()) { it.toAbsolutePath().normalize() }
    private val added = LinkedHashSet<Path>()
    private val unlocated = LinkedHashSet<String>()

    override fun resolveName(name: String, compilationUnit: CompilationUnit): LookupResult? =
        super.resolveName(name, compilationUnit) ?: located(name, compilationUnit)

    private fun located(name: String, unit: CompilationUnit): LookupResult? {
        val topLevel = name.substringBefore('$')
        val path = locator(topLevel).map { it.toAbsolutePath().normalize() }
            .firstOrNull { it !in excluded && it !in added } ?: return null
        if (added.size >= MAX_LOCATED_SOURCES) {
            // Past the cap the class stays unresolved here and is recorded; MissingClassStubs stubs it by this exact
            // name on its next pass. Answering with a stand-in instead would also answer Groovy's nested-class
            // probes (`C32$String`) and capture every simple name below the cut.
            unlocated.add(topLevel)
            return null
        }
        added.add(path)
        val buffer = buffers[path]
        val source = if (buffer == null) {
            unit.addSource(path.toUri().toURL())
        } else {
            // Named as addWorkspaceSources names an open buffer, so a failure is attributed to the buffer.
            val (uri, text) = buffer
            val config = unit.configuration
            unit.addSource(
                SourceUnit(
                    uri.path ?: uri.toString(),
                    StringReaderSource(text, config),
                    config,
                    unit.classLoader,
                    unit.errorCollector,
                ),
            )
        }
        return LookupResult(source, null)
    }

    companion object {
        /** Located files a compile may add before the rest of a chain is left unresolved. */
        const val MAX_LOCATED_SOURCES = 32

        /** Installs the lookup on [unit] when [request] carries a class locator. */
        fun install(unit: CompilationUnit, request: ParseRequest) {
            val locator = request.workspaceClassLocator ?: return
            val buffers = request.openSources.entries.mapNotNull { (uri, text) ->
                runCatching { Path.of(uri).toAbsolutePath().normalize() to (uri to text) }.getOrNull()
            }.toMap()
            unit.classNodeResolver = LocatingClassNodeResolver(locator, request.excludedSources, buffers)
        }

        /** The files [unit]'s class locator added, so only they are attributed as its failing sources. */
        fun addedBy(unit: CompilationUnit): Set<Path> =
            (unit.classNodeResolver as? LocatingClassNodeResolver)?.added.orEmpty()

        /** The names [unit]'s class locator left unresolved at the cap. */
        fun unlocatedBy(unit: CompilationUnit): List<String> =
            (unit.classNodeResolver as? LocatingClassNodeResolver)?.unlocated?.toList().orEmpty()
    }
}

/** A parse result takes normal ownership; cancellation/failure before publication must release its loader. */
@Suppress("TooGenericExceptionCaught") // Cleanup preserves the exact original cancellation, Error or ordinary failure.
private inline fun <T> GroovyClassLoader.closeOnFailure(operation: () -> T): T = try {
    operation()
} catch (failure: Throwable) {
    try {
        close()
    } catch (cleanup: Throwable) {
        if (cleanup !== failure) failure.addSuppressed(cleanup)
    }
    throw failure
}
