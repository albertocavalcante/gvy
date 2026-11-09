package com.github.albertocavalcante.gvy.spock

import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.stmt.BlockStatement

enum class UnrollPatternOrigin { FEATURE_ANNOTATION, FEATURE_NAME, SPEC_ANNOTATION }

enum class UnrollUncertainty {
    /** An `@Unroll`/`@Rollup` spelling is only bound through a star import. */
    ANNOTATION_IDENTITY,

    /** The relevant `@Unroll` value is not a single string constant. */
    NON_CONSTANT_VALUE,

    /** `@Unroll` and `@Rollup` on the same element; Spock rejects the specification. */
    CONFLICTING_ANNOTATIONS,
}

/** The iteration-name pattern a parameterized feature uses, or why it cannot be stated. */
sealed interface UnrollSelection {
    /**
     * [text] is the runtime pattern. [annotationValue] is its literal for annotation origins and
     * null for [UnrollPatternOrigin.FEATURE_NAME]. When [requiresUnrollByDefault] is true the
     * pattern applies only if `SpockConfig` keeps `unroll.unrollByDefault` (default true).
     */
    data class Pattern(
        val text: String,
        val origin: UnrollPatternOrigin,
        val annotationValue: ConstantExpression?,
        val requiresUnrollByDefault: Boolean,
    ) : UnrollSelection

    /** Without a `where:` block Spock reports no iterations. */
    data object NotParameterized : UnrollSelection

    data object RolledUp : UnrollSelection

    /** `unroll.defaultPattern` or default data-variable naming applies; configuration is unknown. */
    data object ConfigurationDependent : UnrollSelection

    data class Uncertain(val reason: UnrollUncertainty) : UnrollSelection
}

/**
 * Mirrors `UnrollExtension` (identical at spock-2.3, spock-2.4 and spock-2.5) over a pre-transform
 * AST. The declaring class supplies the spec annotations, as Spock visits each super spec with its
 * own. `SpockConfig` is never read, so configuration-controlled outcomes are explicit states.
 */
object SpockUnrollSelection {
    private const val UNROLL = "spock.lang.Unroll"
    private const val ROLLUP = "spock.lang.Rollup"

    fun select(method: MethodNode, module: ModuleNode): UnrollSelection {
        if (!hasWhereBlock(method)) return UnrollSelection.NotParameterized
        val feature = Annotations.of(method, module)
        val spec = Annotations.of(method.declaringClass, module)
        // Spock rejects a conflicting spec before visiting any of its features.
        val specConflict = spec.problem?.takeIf { it.reason == UnrollUncertainty.CONFLICTING_ANNOTATIONS }
        return specConflict ?: feature.problem ?: decide(method, feature, spec)
    }

    private fun decide(method: MethodNode, feature: Annotations, spec: Annotations): UnrollSelection {
        if (feature.rollup) return UnrollSelection.RolledUp
        val unrolledByDefault = !feature.unroll
        val specGate = when {
            !unrolledByDefault -> null
            spec.problem != null -> spec.problem
            spec.rollup -> UnrollSelection.RolledUp
            else -> null
        }
        return specGate ?: choosePattern(method, feature, spec, unrolledByDefault && !spec.unroll)
    }

    /** `chooseNameProvider` order; Spock computes the spec pattern even when the feature has @Unroll. */
    private fun choosePattern(
        method: MethodNode,
        feature: Annotations,
        spec: Annotations,
        requiresDefault: Boolean,
    ): UnrollSelection = annotationPattern(feature.value, UnrollPatternOrigin.FEATURE_ANNOTATION, false)
        ?: method.name.takeIf { '#' in it }
            ?.let { UnrollSelection.Pattern(it, UnrollPatternOrigin.FEATURE_NAME, null, requiresDefault) }
        ?: spec.problem
        ?: annotationPattern(spec.value, UnrollPatternOrigin.SPEC_ANNOTATION, requiresDefault)
        ?: UnrollSelection.ConfigurationDependent

    /** Null when absent or empty, so the next source in Spock's order applies. */
    private fun annotationPattern(
        value: Value?,
        origin: UnrollPatternOrigin,
        requiresDefault: Boolean,
    ): UnrollSelection? {
        val pattern = value?.let {
            it.pattern ?: return UnrollSelection.Uncertain(UnrollUncertainty.NON_CONSTANT_VALUE)
        }
        return pattern?.takeIf { it.isNotEmpty() }
            ?.let { UnrollSelection.Pattern(it, origin, value.literal, requiresDefault) }
    }

    private fun hasWhereBlock(method: MethodNode): Boolean =
        (method.code as? BlockStatement)?.statements.orEmpty().any { "where" in it.statementLabels.orEmpty() }

    /** A constant pattern (null when the value is not a string constant) and its literal. */
    private class Value(val pattern: String?, val literal: ConstantExpression?)

    private class Annotations(
        val unroll: Boolean,
        val rollup: Boolean,
        val value: Value?,
        val problem: UnrollSelection.Uncertain?,
    ) {
        companion object {
            fun of(node: AnnotatedNode, module: ModuleNode): Annotations {
                val bindings = node.annotations.map { it to binding(it.classNode, module) }
                val unroll = bindings.filter { it.second == Binding.UNROLL }.map { it.first }
                val rollup = bindings.any { it.second == Binding.ROLLUP }
                val problem = when {
                    bindings.any { it.second == Binding.UNCERTAIN } ->
                        UnrollSelection.Uncertain(UnrollUncertainty.ANNOTATION_IDENTITY)
                    unroll.isNotEmpty() && rollup -> UnrollSelection.Uncertain(
                        UnrollUncertainty.CONFLICTING_ANNOTATIONS,
                    )
                    else -> null
                }
                return Annotations(unroll.isNotEmpty(), rollup, unroll.firstOrNull()?.let(::value), problem)
            }

            private fun value(annotation: AnnotationNode): Value {
                val member = annotation.getMember("value") ?: return Value("", null)
                val constant = member as? ConstantExpression
                return Value(constant?.value as? String, constant?.takeIf { it.value is String })
            }
        }
    }

    private enum class Binding { UNROLL, ROLLUP, OTHER, UNCERTAIN }

    /** Same conservative binding as [SpockSourceContext]: star-import-only spellings stay uncertain. */
    private fun binding(type: ClassNode, module: ModuleNode): Binding {
        val name = type.name
        val qualified = when {
            '.' in name -> name
            else -> module.getImport(name)?.className
        }
        return when {
            qualified == UNROLL -> Binding.UNROLL
            qualified == ROLLUP -> Binding.ROLLUP
            qualified != null || type.isResolved -> Binding.OTHER
            module.classes.any { it.nameWithoutPackage == name } -> Binding.OTHER
            name in setOf("Unroll", "Rollup") && module.starImports.any { it.packageName == "spock.lang." } ->
                Binding.UNCERTAIN
            else -> Binding.OTHER
        }
    }
}
