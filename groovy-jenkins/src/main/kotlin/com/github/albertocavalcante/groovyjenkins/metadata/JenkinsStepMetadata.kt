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
     * Positional parameters for this step (e.g. ["script"] for sh '...')
     * Derived from @DataBoundConstructor.
     */
    val positionalParams: List<String> = emptyList(),

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
 * Post build condition (e.g., `always`, `success`, `failure`).
 * Used inside `post { }` blocks in declarative pipelines.
 *
 * NOTE: These are not steps - they're DSL keywords that control
 * when the enclosed steps execute based on build result.
 */
data class PostConditionMetadata(
    /**
     * Condition name (e.g., "always", "success", "failure")
     */
    val name: String,

    /**
     * Human-readable description of when this condition triggers
     */
    val description: String,

    /**
     * Execution order (lower = earlier). "cleanup" typically runs last.
     * HEURISTIC: Default order based on Jenkins documentation.
     */
    val executionOrder: Int = 0,
)

/**
 * Declarative pipeline option (e.g., `disableConcurrentBuilds`).
 * Defined in `options { }` block.
 *
 * NOTE: These are JobProperty wrappers, not steps. They configure
 * the job, not the execution flow.
 */
data class DeclarativeOptionMetadata(
    /**
     * Option name (e.g., "disableConcurrentBuilds", "timestamps")
     */
    val name: String,

    /**
     * Plugin that provides this option
     */
    val plugin: String,

    /**
     * Parameters for this option (e.g., abortPrevious for disableConcurrentBuilds)
     */
    val parameters: Map<String, StepParameter>,

    /**
     * Documentation for this option
     */
    val documentation: String? = null,
)

/**
 * Agent type for declarative pipelines (e.g., `label`, `docker`, `any`).
 *
 * NOTE: Agent blocks can be at pipeline level or stage level.
 */
data class AgentTypeMetadata(
    /**
     * Agent type name (e.g., "any", "none", "label", "docker")
     */
    val name: String,

    /**
     * Parameters for this agent type (e.g., "label" string for label agent)
     */
    val parameters: Map<String, StepParameter>,

    /**
     * Documentation for this agent type
     */
    val documentation: String? = null,
)

/**
 * Complete metadata for bundled Jenkins stubs.
 *
 * This contains all known steps, global variables, and declarative
 * pipeline constructs from bundled plugins.
 */
data class BundledJenkinsMetadata(
    /**
     * Jenkins version this metadata was extracted from (e.g., "2.426.3").
     * May be null for dynamically scanned metadata or legacy sources.
     */
    val jenkinsVersion: String? = null,

    /**
     * All known steps indexed by name
     */
    val steps: Map<String, JenkinsStepMetadata>,

    /**
     * All known global variables indexed by name
     */
    val globalVariables: Map<String, GlobalVariableMetadata>,

    /**
     * Post build conditions (always, success, failure, etc.)
     */
    val postConditions: Map<String, PostConditionMetadata> = emptyMap(),

    /**
     * Declarative pipeline options (timestamps, disableConcurrentBuilds, etc.)
     */
    val declarativeOptions: Map<String, DeclarativeOptionMetadata> = emptyMap(),

    /**
     * Agent types (any, none, label, docker, etc.)
     */
    val agentTypes: Map<String, AgentTypeMetadata> = emptyMap(),
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

    /**
     * Query for a post condition by name.
     *
     * @return Post condition metadata or null if not found
     */
    fun getPostCondition(name: String): PostConditionMetadata? = postConditions[name]

    /**
     * Query for a declarative option by name.
     *
     * @return Option metadata or null if not found
     */
    fun getDeclarativeOption(name: String): DeclarativeOptionMetadata? = declarativeOptions[name]

    /**
     * Query for an agent type by name.
     *
     * @return Agent type metadata or null if not found
     */
    fun getAgentType(name: String): AgentTypeMetadata? = agentTypes[name]
}
