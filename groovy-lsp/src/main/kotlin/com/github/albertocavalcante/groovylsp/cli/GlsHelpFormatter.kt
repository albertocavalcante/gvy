package com.github.albertocavalcante.groovylsp.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.output.MordantHelpFormatter

/**
 * Custom help formatter that integrates Mordant's styling with Clikt.
 */
class GlsHelpFormatter(context: Context) : HelpFormatter by MordantHelpFormatter(context)
