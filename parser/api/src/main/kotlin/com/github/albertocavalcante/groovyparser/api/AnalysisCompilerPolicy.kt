package com.github.albertocavalcante.groovyparser.api

import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.CompilationCustomizer

/**
 * Compiler policy shared by every engine that compiles user code only to analyse it.
 *
 * Analysis must never execute code taken from the sources being analysed, nor reach for the network:
 * opening a workspace is not consent to run it.
 */
object AnalysisCompilerPolicy {
    /**
     * Global AST transformations that must never run during analysis, even when the compiler's own class
     * loader can see them.
     *
     * - Spock's transform rewrites feature methods and drops the block labels the language server reads.
     * - `@Grab` resolves dependencies through Ivy (not shipped with the server, so it throws
     *   `NoClassDefFoundError`) and would download artifacts just because a file was opened.
     */
    val DISABLED_GLOBAL_TRANSFORMS: Set<String> = setOf(
        "org.spockframework.compiler.SpockTransform",
        "groovy.grape.GrabAnnotationTransformation",
    )

    /**
     * Annotations whose transformation executes source text: `@ASTTest` compiles its closure and runs it
     * inside the compiler JVM (during SEMANTIC_ANALYSIS by default), which is arbitrary code execution for
     * anyone who can put a file into the workspace. They are removed before the compiler collects local
     * transforms; the annotation itself is not needed to analyse the code.
     */
    val CODE_EXECUTING_ANNOTATIONS: Set<String> = setOf("groovy.transform.ASTTest")

    /** Applies the policy to [config]: disabled global transforms plus the annotation stripper. */
    fun apply(config: CompilerConfiguration) {
        config.disabledGlobalASTTransformations = DISABLED_GLOBAL_TRANSFORMS
        config.addCompilationCustomizers(CodeExecutingAnnotationStripper())
    }
}

/**
 * Removes [AnalysisCompilerPolicy.CODE_EXECUTING_ANNOTATIONS] from every annotated node of a class at
 * CONVERSION, i.e. before SEMANTIC_ANALYSIS collects local transforms. Matches the fully qualified name, the
 * simple name (an unresolved reference or star import) and any `import ... as Alias`.
 */
internal class CodeExecutingAnnotationStripper : CompilationCustomizer(CompilePhase.CONVERSION) {
    @Throws(CompilationFailedException::class)
    override fun call(
        source: SourceUnit,
        context: org.codehaus.groovy.classgen.GeneratorContext,
        classNode: ClassNode,
    ) {
        val names = blockedNames(classNode)
        object : ClassCodeVisitorSupport() {
            override fun getSourceUnit(): SourceUnit = source

            override fun visitAnnotations(node: AnnotatedNode) {
                node.annotations
                    .filter { annotation: AnnotationNode -> annotation.classNode.name in names }
                    .forEach { node.annotations.remove(it) }
                super.visitAnnotations(node)
            }
        }.visitClass(classNode)
    }

    private fun blockedNames(classNode: ClassNode): Set<String> {
        val blocked = AnalysisCompilerPolicy.CODE_EXECUTING_ANNOTATIONS
        val names = HashSet<String>(blocked)
        blocked.mapTo(names) { it.substringAfterLast('.') }
        classNode.module?.imports?.forEach { import ->
            if (import.className in blocked && import.alias != null) names.add(import.alias)
        }
        addCollectorAliases(classNode, names)
        return names
    }

    /**
     * `@AnnotationCollector([ASTTest]) @interface Alias` makes `@Alias` expand into `@ASTTest` later, at
     * SEMANTIC_ANALYSIS, so the alias has to be stripped too. Collectors declared in sources of the same
     * compilation (the file itself or other workspace sources) are visible here, transitively.
     */
    private fun addCollectorAliases(classNode: ClassNode, names: MutableSet<String>) {
        val declared = classNode.compileUnit?.classes.orEmpty() + classNode.module?.classes.orEmpty()
        val collectors = declared.distinct().filter { it.isAnnotationDefinition }
        var changed = true
        while (changed) {
            changed = false
            for (collector in collectors) {
                if (collector.name in names || collector.nameWithoutPackage in names) continue
                if (collectedSimpleNames(collector).any { it in names || it.substringAfterLast('.') in names }) {
                    changed = names.add(collector.name) or names.add(collector.nameWithoutPackage)
                }
            }
        }
    }

    private fun collectedSimpleNames(collector: ClassNode): List<String> = collector.annotations
        .filter { it.classNode.nameWithoutPackage == "AnnotationCollector" }
        .mapNotNull { it.getMember("value") }
        .flatMap { value -> if (value is ListExpression) value.expressions else listOf(value) }
        // Before resolution the entries are variable / property expressions, afterwards class expressions;
        // `text` is the (qualified) name in every case.
        .map { it.text }
}
