@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package com.github.albertocavalcante.gvy.viz.desktop.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.ParserConfiguration
import com.github.albertocavalcante.groovyparser.printer.DotPrinter
import com.github.albertocavalcante.groovyparser.provider.RewriteParserProvider
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.gvy.viz.converters.RewriteAstConverter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.GroovyRangeGuard
import com.github.albertocavalcante.gvy.viz.model.RangeDto
import com.github.albertocavalcante.nativeapi.ParseRequest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.util.prefs.Preferences

/**
 * Supported parser types.
 */
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

private fun codeError(message: String, range: RangeDto?): CodeError = if (range == null) {
    CodeError(message)
} else {
    CodeError(message, range.startLine, range.startColumn, range.endLine, range.endColumn)
}

/**
 * View model for the AST Visualizer application.
 *
 * Manages the application state including source code, parsed AST, selected nodes, and parser configuration.
 */
class AstViewModel {

    private val prefs = Preferences.userNodeForPackage(AstViewModel::class.java)

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
     * Update the last used directory.
     */
    fun updateLastDirectory(path: String) {
        val file = File(path)
        val dir = if (file.isDirectory) file.absolutePath else file.parent ?: lastDirectory
        lastDirectory = dir
        prefs.put("lastDirectory", dir)
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

    /**
     * Load Groovy code from a file.
     */
    @Suppress("TooGenericExceptionCaught")
    fun loadFile(file: File) {
        try {
            val contents = file.readText()
            invalidateParse()
            sourceCode = contents
            currentFilePath = file.absolutePath
            inputPath = file.absolutePath
            updateLastDirectory(file.absolutePath)
            parseCode()
        } catch (e: Exception) {
            invalidateParse()
            parseErrors = listOf(CodeError("Failed to load file: ${e.message}"))
        }
    }

    /**
     * Update source code directly (for paste or manual entry).
     */
    fun updateSourceCode(code: String) {
        if (sourceCode != code) invalidateParse()
        sourceCode = code
        currentFilePath = null
    }

    fun updateInputPath(path: String) {
        inputPath = path
    }

    fun loadFromInputPath() {
        val file = File(inputPath)
        if (file.exists() && file.isFile) {
            loadFile(file)
        } else {
            invalidateParse()
            parseErrors = listOf(CodeError("File not found or invalid: $inputPath"))
        }
    }

    /**
     * Parse the current source code with the selected parser.
     */
    fun parseCode() {
        invalidateParse()
        if (sourceCode.isBlank()) {
            return
        }

        isParsing = true
        parseErrors = emptyList()

        try {
            when (selectedParser) {
                ParserType.CORE -> parseCoreAst()
                ParserType.NATIVE -> parseNativeAst()
                ParserType.REWRITE -> parseRewriteAst()
            }
        } catch (e: Exception) {
            parseErrors = listOf(CodeError("Parse error: ${e.message}"))
            astTree = null
        } finally {
            isParsing = false
        }
    }

    private fun parseCoreAst() {
        val config = ParserConfiguration()
        val parser = GroovyParser(config)
        val result = parser.parse(sourceCode)

        if (!result.isSuccessful) {
            val guard = GroovyRangeGuard(sourceCode)
            parseErrors = result.problems.map { problem ->
                val range = problem.range?.let {
                    guard.validRange(RangeDto(it.begin.line, it.begin.column, it.end.line, it.end.column))
                }
                codeError(problem.message, range)
            }
        }

        result.result.ifPresent { compilationUnit ->
            val converter = CoreAstConverter()
            astTree = converter.convert(compilationUnit, sourceCode)
        }
    }

    private fun parseNativeAst() {
        val uri = currentFilePath?.let { URI(File(it).toURI().toString()) }
            ?: URI("file:///temp.groovy")

        val request = ParseRequest(
            uri = uri,
            content = sourceCode,
        )

        val parser = GroovyParserFacade()
        val result = parser.parse(request)

        if (result.diagnostics.isNotEmpty()) {
            val guard = GroovyRangeGuard(sourceCode)
            parseErrors = result.diagnostics.map { diagnostic ->
                val raw = diagnostic.range
                val range = guard.validRange(
                    RangeDto(
                        raw.start.line + 1,
                        raw.start.character + 1,
                        raw.end.line + 1,
                        raw.end.character + 1,
                    ),
                )
                codeError(diagnostic.message, range)
            }
        }

        result.ast?.let { moduleNode ->
            val converter = NativeAstConverter()
            astTree = converter.convert(moduleNode, sourceCode)
        }
    }

    private fun parseRewriteAst() {
        // Create fresh provider for each parse to avoid state issues
        val parserProvider = RewriteParserProvider()
        val converter = RewriteAstConverter(parserProvider)
        val result = converter.parse(sourceCode)

        if (result == null) {
            parseErrors = listOf(CodeError("Failed to parse with OpenRewrite parser"))
            astTree = null
        } else {
            astTree = result
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

    /**
     * Export the current AST as JSON.
     */
    fun exportAsJson(): String {
        val tree = astTree ?: return ""
        return json.encodeToString<AstNodeDto>(tree)
    }

    /**
     * Export the current AST as DOT (Graphviz format).
     *
     * Note: This only works for Core parser AST since DotPrinter operates on Core AST nodes.
     */
    fun exportAsDot(): String {
        if (selectedParser != ParserType.CORE) {
            return "# DOT export is only available for Core parser\n# Please switch to Core parser to use this feature"
        }

        try {
            val config = ParserConfiguration()
            val parser = GroovyParser(config)
            val result = parser.parse(sourceCode)

            return result.result.map { compilationUnit ->
                val dotPrinter = DotPrinter()
                dotPrinter.print(compilationUnit)
            }.orElse("# Failed to parse code")
        } catch (e: Exception) {
            return "# Error: ${e.message}"
        }
    }

    /**
     * Save export to file.
     */
    fun saveToFile(content: String, suggestedFileName: String): File? = try {
        // Use system file chooser
        val fileChooser = javax.swing.JFileChooser()
        fileChooser.selectedFile = File(suggestedFileName)

        if (fileChooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            val file = fileChooser.selectedFile
            file.writeText(content)
            file
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}
