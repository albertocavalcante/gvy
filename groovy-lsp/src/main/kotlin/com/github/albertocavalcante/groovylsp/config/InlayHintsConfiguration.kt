package com.github.albertocavalcante.groovylsp.config

/**
 * Configuration for inlay hints display.
 *
 * Inlay hints are inline annotations that display type information and parameter names
 * directly in the source code. Each hint type can be independently enabled/disabled.
 *
 * TODO(#566): Wire this configuration into GroovyTextDocumentService.inlayHint.
 *   See: https://github.com/albertocavalcante/gvy/issues/566
 *
 * @property typeHints Show type hints for `def` variable declarations and untyped closure parameters
 * @property parameterHints Show parameter name hints at method/constructor call sites
 */
data class InlayHintsConfiguration(val typeHints: Boolean = true, val parameterHints: Boolean = true)
