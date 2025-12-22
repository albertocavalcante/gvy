package com.github.albertocavalcante.groovytesting.api

/**
 * Represents a discovered test item (class or individual test method).
 *
 * This is the common data model used by all test framework detectors.
 *
 * @property id Unique identifier for the test item (typically fully qualified name)
 * @property name Display name for the test (may differ from method name, e.g., Spock feature names)
 * @property kind Whether this is a test class or test method
 * @property framework The test framework this item belongs to
 * @property line Line number in source file (1-indexed)
 * @property parent Parent test item ID (for nested tests), null for top-level classes
 */
data class TestItem(
    val id: String,
    val name: String,
    val kind: TestItemKind,
    val framework: TestFramework,
    val line: Int,
    val parent: String? = null,
)
