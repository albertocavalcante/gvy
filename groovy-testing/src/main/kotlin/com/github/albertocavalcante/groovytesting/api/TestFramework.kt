package com.github.albertocavalcante.groovytesting.api

/**
 * Supported test frameworks.
 *
 * Each framework has its own detection and extraction logic.
 */
enum class TestFramework {
    /** Spock Framework - BDD-style testing with block-based DSL */
    SPOCK,

    /** JUnit 5 (Jupiter) - Modern annotation-based testing */
    JUNIT5,

    /** JUnit 4 - Legacy annotation-based testing */
    JUNIT4,

    /** TestNG - Feature-rich annotation-based testing */
    TESTNG,
}

/**
 * Kind of test item (class or method).
 */
enum class TestItemKind {
    /** Test class/suite containing test methods */
    CLASS,

    /** Individual test method */
    METHOD,
}
