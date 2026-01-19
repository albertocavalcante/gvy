package com.github.albertocavalcante.gvy.build

/**
 * Represents a command to execute a test.
 *
 * Used by clients (VS Code extension) to spawn the actual test process.
 * This keeps the LSP build-tool-agnostic while letting the extension handle execution.
 */
data class TestCommand(
    val executable: String,
    val args: List<String>,
    val cwd: String,
    val env: Map<String, String> = emptyMap(),
)
