package com.github.albertocavalcante.groovyjupyter.handlers

/**
 * Result of code execution.
 *
 * Contains the execution result, captured output, and any error information.
 */
data class ExecuteResult(
    val status: ExecuteStatus,
    val result: Any? = null,
    val stdout: String = "",
    val stderr: String = "",
    val errorName: String? = null,
    val errorValue: String? = null,
    val traceback: List<String> = emptyList(),
)
