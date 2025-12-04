package com.github.albertocavalcante.groovyjenkins.metadata

/**
 * Metadata for a Jenkins pipeline step.
 *
 * This represents information about a single pipeline step (e.g., `sh`, `git`, `docker.image`).
 * Used for providing completions, validation, and documentation.
 *
 * Phase 0: Bundled Jenkins SDK Stubs - Initial implementation
 */
data class JenkinsStepMetadata(
    /**
     * Step name (e.g., "sh", "git", "checkout")
     */
    val name: String,

    /**
     * Plugin that provides this step (e.g., "workflow-durable-task-step:1400.v7fd76b_8b_6b_9a")
     */
    val plugin: String,

    /**
     * Map of parameter name → parameter metadata
     *
     * IMPORTANT: For Map-based steps (e.g., `git(Map args)`), these are the VALID MAP KEYS,
     * not the method parameter name "args".
     */
    val parameters: Map<String, StepParameter>,

    /**
     * Documentation for this step
     */
    val documentation: String? = null,
)

/**
 * Metadata for a single step parameter.
 */
data class StepParameter(
    /**
     * Parameter name
     */
    val name: String,

    /**
     * Parameter type (e.g., "String", "boolean", "int")
     */
    val type: String,

    /**
     * Whether this parameter is required
     */
    val required: Boolean = false,

    /**
     * Default value if parameter is optional
     */
    val default: String? = null,

    /**
     * Documentation for this parameter
     */
    val documentation: String? = null,
)

/**
 * Metadata for a Jenkins global variable.
 *
 * Global variables are available in all Jenkinsfiles (e.g., `env`, `params`, `currentBuild`).
 */
data class GlobalVariableMetadata(
    /**
     * Variable name (e.g., "env", "params", "docker")
     */
    val name: String,

    /**
     * Type/class name (e.g., "org.jenkinsci.plugins.workflow.cps.EnvActionImpl")
     */
    val type: String,

    /**
     * Documentation for this global variable
     */
    val documentation: String? = null,
)

/**
 * Complete metadata for bundled Jenkins stubs.
 *
 * This contains all known steps and global variables from bundled plugins.
 */
data class BundledJenkinsMetadata(
    /**
     * All known steps indexed by name
     */
    val steps: Map<String, JenkinsStepMetadata>,

    /**
     * All known global variables indexed by name
     */
    val globalVariables: Map<String, GlobalVariableMetadata>,
) {
    /**
     * Query for a step by name.
     *
     * @return Step metadata or null if not found
     */
    fun getStep(name: String): JenkinsStepMetadata? = steps[name]

    /**
     * Query for a global variable by name.
     *
     * @return Global variable metadata or null if not found
     */
    fun getGlobalVariable(name: String): GlobalVariableMetadata? = globalVariables[name]
}
