package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ParserConfiguration
import com.github.albertocavalcante.groovyparser.printer.DotPrinter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class ExportFormat { JSON, DOT }

internal data class ExportSnapshot(val format: ExportFormat, val source: String, val tree: AstNodeDto?)

@OptIn(ExperimentalSerializationApi::class)
private val exportJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    classDiscriminator = "nodeClass"
}

/** Encodes only its captured input. The caller owns worker dispatch and parser mutual exclusion. */
internal fun encodeExport(snapshot: ExportSnapshot): String = when (snapshot.format) {
    ExportFormat.JSON -> exportJson.encodeToString<AstNodeDto>(requireNotNull(snapshot.tree))
    ExportFormat.DOT -> {
        val result = GroovyParser(ParserConfiguration()).parse(snapshot.source)
        result.result.map { DotPrinter().print(it) }.orElseThrow {
            IllegalStateException("Failed to parse source for DOT export")
        }
    }
}
