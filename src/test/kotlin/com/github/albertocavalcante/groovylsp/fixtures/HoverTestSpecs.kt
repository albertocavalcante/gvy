package com.github.albertocavalcante.groovylsp.fixtures

/**
 * Data class for hover test parameters
 */
data class HoverTestSpec(
    val code: String,
    val line: Int,
    val character: Int,
    val description: String,
    val expectedContent: String? = null,
    val shouldHaveHover: Boolean = true,
)

/**
 * Data class for hover test parameters with title support
 */
data class HoverTestWithTitleSpec(
    val code: String,
    val line: Int,
    val character: Int,
    val description: String,
    val expectedTitle: String? = null,
    val expectedContent: String? = null,
    val shouldHaveHover: Boolean = true,
)
