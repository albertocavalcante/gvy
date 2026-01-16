package com.github.albertocavalcante.groovylsp.config

/**
 * Defines the language mode for Groovy LSP features.
 *
 * Controls which features are available:
 * - GROOVY: Pure Groovy without framework-specific features
 * - JENKINS: Jenkins Pipeline mode with steps, declarative blocks, CPS awareness
 * - AUTO: Auto-detect based on file patterns and workspace structure (default)
 */
enum class GroovyMode {
    /** Pure Groovy mode - no framework-specific features */
    GROOVY,

    /** Jenkins Pipeline mode - steps, declarative, CPS awareness */
    JENKINS,

    /** Auto-detect based on file patterns and workspace (default) */
    AUTO,
    ;

    companion object {
        fun fromString(value: String?): GroovyMode = when (value?.lowercase()) {
            "groovy" -> GROOVY
            "jenkins" -> JENKINS
            "auto" -> AUTO
            else -> AUTO
        }
    }
}
