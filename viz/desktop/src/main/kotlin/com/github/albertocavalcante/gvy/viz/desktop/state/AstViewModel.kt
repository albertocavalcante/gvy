@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package com.github.albertocavalcante.gvy.viz.desktop.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ParserConfiguration
import com.github.albertocavalcante.groovyparser.printer.DotPrinter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.RangeDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.prefs.Preferences

/**
 * Supported parser types.
 */
private const val DEFAULT_DEBOUNCE_MILLIS = 300L

enum class ParserType {
    CORE,
    NATIVE,
    REWRITE,
}

data class CodeError(
    val message: String,
    val startLine: Int = -1,
    val startColumn: Int = -1,
    val endLine: Int = -1,
    val endColumn: Int = -1,
)

data class SourceNavigation(val sequence: Long, val range: RangeDto)

internal enum class ExportFormat { JSON, DOT }

internal data class ExportSnapshot(val format: ExportFormat, val source: String, val tree: AstNodeDto?)

internal class FileEnvironment(
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val fileOperations: DesktopFileOperations = LocalDesktopFileOperations,
    val prefs: Preferences = Preferences.userNodeForPackage(AstViewModel::class.java),
)

/**
 * View model for the AST Visualizer application.
 *
 * Manages the application state including source code, parsed AST, selected nodes, and parser configuration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AstViewModel internal constructor(
    uiDispatcher: CoroutineDispatcher,
    private val backgroundDispatcher: CoroutineDispatcher,
    private val parseEngine: suspend (ParseSnapshot, AtomicBoolean) -> ParseOutput,
    debounceMillis: Long,
    private val files: FileEnvironment = FileEnvironment(),
) {
    constructor() : this(Preferences.userNodeForPackage(AstViewModel::class.java))

    internal constructor(prefs: Preferences) : this(
        AwtDispatcher,
        Dispatchers.Default.limitedParallelism(1),
        ::parseSnapshot,
        DEFAULT_DEBOUNCE_MILLIS,
        files = FileEnvironment(prefs = prefs),
    )

    private val ioDispatcher = files.ioDispatcher
    private val fileOperations = files.fileOperations
    private val prefs = files.prefs
    private val scope = CoroutineScope(SupervisorJob() + uiDispatcher)
    private val parseMutex = Mutex()
    private val readMutex = Mutex()
    private val preferencesMutex = Mutex()
    private var readJob: Job? = null
    private var closed = false
    private val parser = ParseCoordinator(scope, backgroundDispatcher, { input, token ->
        parseMutex.withLock { parseEngine(input, token) }
    }, { result ->
        astTree = result.tree
        parseErrors = result.errors
        isParsing = false
    }, debounceMillis)

    fun close() {
        closed = true
        parser.close()
        scope.cancel()
    }

    // Source code
    var sourceCode by mutableStateOf("")
        private set

    // Selected parser
    var selectedParser by mutableStateOf(ParserType.CORE)
        private set

    // Parsed AST tree
    var astTree by mutableStateOf<AstNodeDto?>(null)
        private set

    // Selected node in the tree
    var selectedNode by mutableStateOf<AstNodeDto?>(null)
        private set

    var sourceNavigation by mutableStateOf<SourceNavigation?>(null)
        private set
    private var navigationSequence = 0L

    // Last used directory for file picker
    var lastDirectory by mutableStateOf(prefs.get("lastDirectory", System.getProperty("user.home")))
        private set

    /**
     * Select a node in the tree.
     */
    fun selectNode(node: AstNodeDto?) {
        fun contains(root: AstNodeDto): Boolean = root === node || root.children.any(::contains)
        if (node != null && astTree?.let(::contains) != true) return
        selectedNode = node
        sourceNavigation = node?.range?.let { SourceNavigation(++navigationSequence, it) }
    }

    /**
     * Find the smallest node containing the given line and column.
     */
    fun selectNodeAt(line: Int, column: Int) {
        val root = astTree ?: return
        val node = findSmallestNodeAt(root, line, column)
        selectedNode = node
    }

    private fun findSmallestNodeAt(node: AstNodeDto, line: Int, column: Int): AstNodeDto? {
        val coordinates = SourceCoordinates(sourceCode)
        val cursor = coordinates.offset(line, column) ?: return null
        data class Candidate(val node: AstNodeDto, val length: Int, val depth: Int)
        var best: Candidate? = null
        fun visit(current: AstNodeDto, depth: Int) {
            current.range?.let { range ->
                val start = coordinates.offset(range.startLine, range.startColumn) ?: return@let
                val end = coordinates.offset(range.endLine, range.endColumn) ?: return@let
                val validRange = start < end
                val containsCursor = cursor >= start && cursor < end
                if (!validRange || !containsCursor) return@let

                val candidate = Candidate(current, end - start, depth)
                val previous = best
                // Equal spans prefer the deeper node, then the first in tree order.
                if (previous == null) {
                    best = candidate
                } else {
                    val smallerSpan = candidate.length < previous.length
                    val deeperEqualSpan = candidate.length == previous.length && candidate.depth > previous.depth
                    if (smallerSpan || deeperEqualSpan) best = candidate
                }
            }
            current.children.forEach { visit(it, depth + 1) }
        }
        visit(node, 0)
        return best?.node
    }

    private fun invalidateParse() {
        astTree = null
        selectedNode = null
        sourceNavigation = null
        parseErrors = emptyList()
    }

    // Parse errors
    var parseErrors by mutableStateOf<List<CodeError>>(emptyList())
        private set

    // Parsing state
    var isParsing by mutableStateOf(false)
        private set

    // Last loaded file path
    var currentFilePath by mutableStateOf<String?>(null)
        private set

    // Path input text
    var inputPath by mutableStateOf("")
        private set

    // JSON serializer
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        classDiscriminator = "nodeClass"
    }

    /** Read off the event thread; edits and newer requests invalidate this result. */
    fun loadFile(file: File) {
        readJob?.cancel()
        val generation = parser.invalidate()
        invalidateParse()
        isParsing = true
        readJob = scope.launch {
            try {
                val contents = withContext(ioDispatcher) { readMutex.withLock { fileOperations.read(file) } }
                if (!parser.isCurrent(generation)) return@launch
                sourceCode = contents
                currentFilePath = file.absolutePath
                inputPath = file.absolutePath
                lastDirectory = file.absoluteFile.parent ?: lastDirectory
                val directory = lastDirectory
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    withContext(NonCancellable) {
                        preferencesMutex.withLock {
                            withContext(ioDispatcher) { prefs.put("lastDirectory", directory) }
                        }
                    }
                }
                readJob = null
                scheduleParse(deferred = false)
            } catch (cancelled: CancellationException) {
                if (!isActive) throw cancelled
                if (parser.isCurrent(generation)) {
                    parseErrors = listOf(CodeError("File load cancelled: ${cancelled.message}"))
                    isParsing = false
                }
            } catch (failure: Exception) {
                if (parser.isCurrent(generation)) {
                    parseErrors = listOf(CodeError("Failed to load file: ${failure.message}"))
                    isParsing = false
                }
            }
        }
    }

    /**
     * Update source code directly (for paste or manual entry).
     */
    fun updateSourceCode(code: String) {
        if (sourceCode == code) return
        sourceCode = code
        currentFilePath = null
        scheduleParse(deferred = true)
    }

    fun updateInputPath(path: String) {
        inputPath = path
    }

    fun loadFromInputPath() = loadFile(File(inputPath))

    /**
     * Parse the current source code with the selected parser.
     */
    fun parseCode() = scheduleParse(deferred = false)

    private fun scheduleParse(deferred: Boolean) {
        readJob?.cancel()
        readJob = null
        invalidateParse()
        if (sourceCode.isBlank()) {
            parser.invalidate()
            isParsing = false
        } else {
            isParsing = true
            parser.submit(ParseSnapshot(sourceCode, selectedParser, currentFilePath), deferred)
        }
    }

    /**
     * Switch parser type and re-parse.
     */
    fun setParser(parser: ParserType) {
        if (selectedParser != parser) {
            selectedParser = parser
            parseCode()
        }
    }

    var operationMessage by mutableStateOf<String?>(null)
        private set
    var isExporting by mutableStateOf(false)
        private set

    /** Called on the UI thread before closing the application window. */
    fun canClose(): Boolean {
        if (!isExporting) return true
        operationMessage = "Wait for the export to finish before closing"
        return false
    }

    /** Capture the current generation before opening the save dialog. */
    internal fun exportSnapshot(format: ExportFormat): ExportSnapshot? {
        if (isExporting) return null
        if (format == ExportFormat.JSON && astTree == null) {
            operationMessage = "There is no AST to export"
            return null
        }
        if (format == ExportFormat.DOT && selectedParser != ParserType.CORE) {
            operationMessage = "DOT export is only available for Core parser"
            return null
        }
        return ExportSnapshot(format, sourceCode, astTree)
    }

    /** Once accepted, the snapshot write is allowed to finish after the view is disposed. */
    internal fun saveExport(snapshot: ExportSnapshot, file: File) {
        if (isExporting || closed) return
        isExporting = true
        operationMessage = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    val contents = withContext(backgroundDispatcher) {
                        when (snapshot.format) {
                            ExportFormat.JSON -> json.encodeToString<AstNodeDto>(requireNotNull(snapshot.tree))
                            ExportFormat.DOT -> parseMutex.withLock { exportDot(snapshot.source) }
                        }
                    }
                    withContext(ioDispatcher) { fileOperations.write(file, contents) }
                    if (!closed) operationMessage = "Saved ${file.name}"
                } catch (failure: Exception) {
                    if (!closed) {
                        operationMessage = "Failed to save ${file.name}: ${failure.message}"
                    } else {
                        System.err.println("Failed to save ${file.name}: ${failure.message}")
                    }
                } finally {
                    if (!closed) isExporting = false
                }
            }
        }
    }

    private fun exportDot(source: String): String {
        val result = GroovyParser(ParserConfiguration()).parse(source)
        return result.result.map { DotPrinter().print(it) }.orElseThrow {
            IllegalStateException("Failed to parse source for DOT export")
        }
    }
}
