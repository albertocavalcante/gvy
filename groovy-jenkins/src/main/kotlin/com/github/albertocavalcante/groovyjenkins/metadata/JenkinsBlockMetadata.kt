package com.github.albertocavalcante.groovyjenkins.metadata

/**
 * Jenkins DSL block categorization for semantic highlighting.
 *
 * NOTE: These are Jenkins-specific blocks that get special visual treatment in IDEs.
 * Standard Groovy keywords (def, class, if, etc.) are handled by general Groovy provider.
 */
object JenkinsBlockMetadata {

    /**
     * Pipeline structural blocks - the skeleton of declarative pipelines.
     * Token type: MACRO (structural/metaprogramming constructs)
     */
    val PIPELINE_BLOCKS = setOf(
        "pipeline", "agent", "stages", "stage", "steps",
        "post", "environment", "options", "parameters",
        "triggers", "tools", "when", "matrix", "axes", "axis",
    )

    /**
     * Wrapper blocks that modify execution context.
     * Token type: DECORATOR (blocks that wrap other code)
     */
    val WRAPPER_BLOCKS = setOf(
        "withCredentials", "timeout", "retry", "lock", "node",
        "parallel", "script", "dir", "withEnv", "timestamps",
        "ansiColor", "sshagent", "input", "waitUntil", "catchError",
        "warnError",
    )

    /**
     * Credential binding helper steps.
     * Token type: DECORATOR (credential wrappers)
     */
    val CREDENTIAL_BLOCKS = setOf(
        "usernamePassword",
        "usernameColonPassword",
        "string", // Jenkins-specific credential binding, not the String type
        "file", // Jenkins-specific file credential binding
        "sshUserPrivateKey",
        "certificate",
    )

    /**
     * All Jenkins blocks that get special highlighting.
     */
    val ALL_BLOCKS = PIPELINE_BLOCKS + WRAPPER_BLOCKS + CREDENTIAL_BLOCKS

    /**
     * Get the category for a Jenkins block.
     */
    fun getCategoryFor(methodName: String): BlockCategory? = when (methodName) {
        in PIPELINE_BLOCKS -> BlockCategory.PIPELINE_STRUCTURE
        in WRAPPER_BLOCKS -> BlockCategory.WRAPPER
        in CREDENTIAL_BLOCKS -> BlockCategory.CREDENTIAL
        else -> null
    }

    /**
     * Check if a method is a special Jenkins block.
     */
    fun isJenkinsBlock(methodName: String): Boolean = methodName in ALL_BLOCKS

    enum class BlockCategory(val displayName: String) {
        PIPELINE_STRUCTURE("Pipeline Structure"),
        WRAPPER("Wrapper Block"),
        CREDENTIAL("Credential Binding"),
    }
}
