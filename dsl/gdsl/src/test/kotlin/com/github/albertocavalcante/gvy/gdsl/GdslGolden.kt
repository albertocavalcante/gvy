package com.github.albertocavalcante.gvy.gdsl

import com.github.albertocavalcante.gvy.gdsl.model.GdslParseResult

/** Canonical text form of a [GdslParseResult], used for the golden fixtures in `gdsl-golden/`. */
internal object GdslGolden {
    fun render(result: GdslParseResult): String = buildString {
        appendLine("success=${result.success} error=${result.error}")
        result.methods.forEach { appendLine("method $it") }
        result.properties.forEach { appendLine("property $it") }
    }
}
