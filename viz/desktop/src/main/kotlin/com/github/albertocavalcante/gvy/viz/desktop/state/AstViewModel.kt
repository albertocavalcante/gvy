package com.github.albertocavalcante.gvy.viz.desktop.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.ParserConfiguration
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.printer.DotPrinter
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.gvy.viz.converters.RewriteAstConverter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI

/**
 * Supported parser types.
 */
enum class ParserType {
    CORE,
    NATIVE,
    REWRITE,
}

/**
 * View model for the AST Visualizer application.
 *
 * Manages the application state including source code, parsed AST, selected nodes, and parser configuration.
 */
class AstViewModel {

    // Source code
    var sourceCode by mutableStateOf("")
        private set

    // Selected parser
    var selectedParser by mutableStateOf(ParserType.CORE)

    // Parsed AST tree
    var astTree by mutableStateOf<AstNodeDto?>(null)
        private set

    // Selected node in the tree
    var selectedNode by mutableStateOf<AstNodeDto?>(null)

    // Parse errors
    var parseErrors by mutableStateOf<List<String>>(emptyList())
        private set

    // Parsing state
    var isParsing by mutableStateOf(false)
        private set

    // Last loaded file path
    var currentFilePath by mutableStateOf<String?>(null)
        private set

    // JSON serializer
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        classDiscriminator = "nodeClass"
    }

    /**
     * Load Groovy code from a file.
     */
    fun loadFile(file: File) {
        try {
            sourceCode = file.readText()
            currentFilePath = file.absolutePath
            parseCode()
        } catch (e: Exception) {
            parseErrors = listOf("Failed to load file: ${e.message}")
        }
    }

    /**
     * Update source code directly (for paste or manual entry).
     */
    fun updateSourceCode(code: String) {
        sourceCode = code
        currentFilePath = null
    }

    /**
     * Parse the current source code with the selected parser.
     */
    fun parseCode() {
        if (sourceCode.isBlank()) {
            astTree = null
            parseErrors = emptyList()
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
            parseErrors = listOf("Parse error: ${e.message}")
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
            parseErrors = result.problems.map { it.message }
        }

        result.result.ifPresent { compilationUnit ->
            val converter = CoreAstConverter()
            astTree = converter.convert(compilationUnit)
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
            parseErrors = result.diagnostics.map { it.message }
        }

        result.ast?.let { moduleNode ->
            val converter = NativeAstConverter()
            astTree = converter.convert(moduleNode)
        }
    }

    private fun parseRewriteAst() {
        val converter = RewriteAstConverter()
        val result = converter.parse(sourceCode)

        if (result == null) {
            parseErrors = listOf("Failed to parse with OpenRewrite parser")
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
     * Select a node in the tree.
     */
    fun selectNode(node: AstNodeDto?) {
        selectedNode = node
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
