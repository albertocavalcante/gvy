package com.github.albertocavalcante.groovylsp.cli

/**
 * Output format for the check command.
 */
enum class OutputFormat {
    /**
     * Human-readable text output with colors (default).
     */
    TEXT,

    /**
     * SARIF 2.1.0 format for GitHub Code Scanning and other tooling.
     */
    SARIF,
}
