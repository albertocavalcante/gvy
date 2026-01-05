package com.github.albertocavalcante.groovyparser.spi

import com.github.albertocavalcante.groovyparser.Position
import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Service Provider Interface for Jenkins CPS (Continuation Passing Style) analysis.
 *
 * Jenkins Pipeline uses the workflow-cps plugin which transforms Groovy code
 * to be serializable and resumable. This transformation has restrictions on
 * what code patterns are allowed.
 *
 * Implementations of this interface can analyze AST nodes to detect CPS
 * compatibility issues before code is executed.
 *
 * Example usage:
 * ```kotlin
 * class JenkinsCpsAnalyzer : CpsAnalyzer {
 *     override fun isCpsCompatible(node: Node): Boolean {
 *         // Check if node uses patterns that are CPS-incompatible
 *         return !usesNonSerializableClosures(node)
 *     }
 * }
 * ```
 */
interface CpsAnalyzer {

    /**
     * Checks if the given node is compatible with CPS transformation.
     *
     * @param node the AST node to check
     * @return true if the node is CPS-compatible
     */
    fun isCpsCompatible(node: Node): Boolean

    /**
     * Returns all CPS violations found in the given node and its children.
     *
     * @param node the AST node to analyze
     * @return list of CPS violations found
     */
    fun getCpsViolations(node: Node): List<CpsViolation>

    /**
     * Checks if the given node is marked with @NonCPS annotation.
     *
     * Methods marked with @NonCPS are not transformed and can use
     * non-serializable constructs, but cannot call CPS-transformed code.
     *
     * @param node the AST node to check
     * @return true if the node is marked @NonCPS
     */
    fun isNonCps(node: Node): Boolean
}

/**
 * Represents a CPS compatibility violation.
 */
data class CpsViolation(
    /** Description of the violation */
    val message: String,

    /** The position where the violation occurs */
    val position: Position?,

    /** The type of violation */
    val type: CpsViolationType,

    /** The AST node that caused the violation */
    val node: Node? = null,
)

/**
 * Types of CPS violations.
 */
enum class CpsViolationType {
    /** Using a non-serializable closure in CPS context */
    NON_SERIALIZABLE_CLOSURE,

    /** Using a non-whitelisted method */
    NON_WHITELISTED_METHOD,

    /** Using thread-local or other non-serializable constructs */
    NON_SERIALIZABLE_CONSTRUCT,

    /** Calling CPS code from @NonCPS method */
    CPS_CALL_FROM_NON_CPS,

    /** Using synchronized blocks */
    SYNCHRONIZED_BLOCK,

    /** Other CPS-incompatible pattern */
    OTHER,
}
