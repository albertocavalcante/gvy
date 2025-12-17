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
        "libraries",
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
     * Post build condition blocks.
     * Token type: KEYWORD (condition keywords like if/else)
     *
     * NOTE: These are DSL keywords that control when enclosed steps execute
     * based on build result. Not actual steps themselves.
     */
    val POST_CONDITIONS = setOf(
        "always", "success", "failure", "unstable", "aborted",
        "changed", "fixed", "regression", "unsuccessful", "cleanup",
    )

    /**
     * Declarative pipeline options.
     * Token type: DECORATOR (configuration annotations)
     *
     * NOTE: These are JobProperty wrappers, not steps. They configure
     * the job, not the execution flow.
     */
    val DECLARATIVE_OPTIONS = setOf(
        "timestamps", "disableConcurrentBuilds", "skipDefaultCheckout",
        "checkoutToSubdirectory", "quietPeriod", "buildDiscarder",
        "preserveStashes", "parallelsAlwaysFailFast", "disableResume",
        "durabilityHint", "skipStagesAfterUnstable", "retry", "timeout",
    )

    /**
     * Agent specification keywords.
     * Token type: KEYWORD
     *
     * NOTE: Agent blocks can be at pipeline level or stage level.
     */
    val AGENT_TYPES = setOf(
        "any",
        "none",
        "label",
        "docker",
        "dockerfile",
        "kubernetes",
        "node",
    )

    /**
     * All Jenkins blocks that get special highlighting.
     */
    val ALL_BLOCKS = PIPELINE_BLOCKS + WRAPPER_BLOCKS + CREDENTIAL_BLOCKS +
        POST_CONDITIONS + DECLARATIVE_OPTIONS + AGENT_TYPES

    /**
     * Get the category for a Jenkins block.
     */
    fun getCategoryFor(methodName: String): BlockCategory? = when (methodName) {
        in PIPELINE_BLOCKS -> BlockCategory.PIPELINE_STRUCTURE
        in WRAPPER_BLOCKS -> BlockCategory.WRAPPER
        in CREDENTIAL_BLOCKS -> BlockCategory.CREDENTIAL
        in POST_CONDITIONS -> BlockCategory.POST_CONDITION
        in DECLARATIVE_OPTIONS -> BlockCategory.DECLARATIVE_OPTION
        in AGENT_TYPES -> BlockCategory.AGENT_TYPE
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
        POST_CONDITION("Post Condition"),
        DECLARATIVE_OPTION("Declarative Option"),
        AGENT_TYPE("Agent Type"),
    }
}
