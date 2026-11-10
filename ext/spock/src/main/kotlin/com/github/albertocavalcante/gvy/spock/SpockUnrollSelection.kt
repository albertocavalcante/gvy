package com.github.albertocavalcante.gvy.spock

import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import java.util.Collections
import java.util.IdentityHashMap

enum class UnrollPatternOrigin { FEATURE_ANNOTATION, FEATURE_NAME, SPEC_ANNOTATION }

/** `SpockConfig` `unroll { … }` settings a [UnrollSelection.Pattern] depends on; never read here. */
enum class UnrollConfigurationKey {
    /** `unrollByDefault` (default true): whether unannotated features are unrolled at all. */
    UNROLL_BY_DEFAULT,

    /**
     * `validateExpressions` (default true): whether an invalid or unresolvable placeholder fails the
     * iteration with `SpockAssertionError` or is rendered as `#Error:…`.
     */
    VALIDATE_EXPRESSIONS,
}

enum class UnrollUncertainty {
    /** An `@Unroll`/`@Rollup` spelling is only bound through a star import. */
    ANNOTATION_IDENTITY,

    /** The relevant `@Unroll` value is not a single string constant. */
    NON_CONSTANT_VALUE,

    /**
     * `@Unroll` and `@Rollup` on the same element: the feature, its declaring spec, a parameterized
     * sibling feature, or an in-module super spec. Spock rejects the whole specification.
     */
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
    ) : UnrollSelection {
        /** Configuration that decides this pattern's outcome; expression failures only matter with placeholders. */
        val dependsOn: Set<UnrollConfigurationKey>
            get() = buildSet {
                if (SpockUnrollPattern.parse(text).isNotEmpty()) add(UnrollConfigurationKey.VALIDATE_EXPRESSIONS)
                if (requiresUnrollByDefault) add(UnrollConfigurationKey.UNROLL_BY_DEFAULT)
            }
    }

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

    /** One-off selection; providers selecting every feature of a file should reuse [forModule]. */
    fun select(method: MethodNode, module: ModuleNode): UnrollSelection = forModule(module).select(method)

    /** A selector for one module that computes each spec chain's conflict scan once. */
    fun forModule(module: ModuleNode): ModuleSelector = ModuleSelector(module)

    class ModuleSelector internal constructor(private val module: ModuleNode) {
        private val conflicts = IdentityHashMap<ClassNode, Boolean>()

        fun select(method: MethodNode): UnrollSelection {
            if (!hasWhereBlock(method)) return UnrollSelection.NotParameterized
            val feature = Annotations.of(method, module)
            val spec = Annotations.of(method.declaringClass, module)
            // Spock rejects the whole spec when any element it visits carries both annotations.
            val conflict = conflicts.getOrPut(method.declaringClass) { conflictInSpec(method.declaringClass, module) }
            if (conflict) return UnrollSelection.Uncertain(UnrollUncertainty.CONFLICTING_ANNOTATIONS)
            return feature.problem ?: decide(method, feature, spec)
        }
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

    /**
     * Mirrors `visitSpec`: the spec, its parameterized features, then its super spec. Not detected
     * (missed detection, never a wrong conflict): super specs declared outside this module, a super
     * spec spelled as a bare nested name (`extends Inner` meaning `Outer$Inner`), and a conflicting
     * subclass spec that runs these features.
     */
    private fun conflictInSpec(spec: ClassNode, module: ModuleNode): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<ClassNode, Boolean>())
        return generateSequence(spec) { current -> moduleSuperclass(current, module) }
            .takeWhile { visited.add(it) }
            .any { current ->
                current.isConflicting(module) ||
                    current.methods.any { hasWhereBlock(it) && it.isConflicting(module) }
            }
    }

    private fun AnnotatedNode.isConflicting(module: ModuleNode): Boolean =
        Annotations.of(this, module).problem?.reason == UnrollUncertainty.CONFLICTING_ANNOTATIONS

    private fun moduleSuperclass(node: ClassNode, module: ModuleNode): ClassNode? {
        val name = node.superClass?.name?.replace('$', '.') ?: return null
        return module.classes.singleOrNull { candidate ->
            candidate.name.replace('$', '.') == name || candidate.nameWithoutPackage.replace('$', '.') == name
        }
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
