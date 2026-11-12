// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.stubs

import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedStepMetadata
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * Generates lightweight Groovy stubs for Jenkins types defined in metadata.
 * This allows the Groovy parser to resolve types and methods without requiring
 * heavy plugin JARs on the classpath.
 *
 * @param writeFile writes one stub file (test seam); the public constructor uses an atomic write-then-rename.
 */
class JenkinsStubGenerator internal constructor(private val writeFile: (Path, String) -> Unit) {
    constructor() : this(::writeAtomically)

    private val logger = KotlinLogging.logger {}

    fun generateStubs(metadata: MergedJenkinsMetadata, outputDir: Path) {
        logger.info { "Generating Jenkins stubs in $outputDir" }
        writeSet(render(metadata), outputDir)
    }

    /**
     * The plugin stub sources for [metadata], by qualified class name. Pure: the same metadata always renders
     * the same sources, which is what [JenkinsStubSets] keys its directories by.
     */
    internal fun render(metadata: MergedJenkinsMetadata): Map<String, String> = linkedMapOf(
        // CpsScript carries the steps, for Declarative and Scripted Pipeline; the others are fixed base types
        // that global variables rely on.
        CPS_SCRIPT to cpsScript(metadata),
        ENV_ACTION_IMPL to ENV_ACTION_SOURCE,
        RUN_WRAPPER to RUN_WRAPPER_SOURCE,
        DOCKER to DOCKER_SOURCE,
    )

    /** Writes rendered [files] (qualified name to source) under [outputDir]. */
    internal fun writeSet(files: Map<String, String>, outputDir: Path) {
        outputDir.createDirectories()
        files.forEach { (qualifiedName, content) -> writeStub(outputDir, qualifiedName, content) }
    }

    private fun cpsScript(metadata: MergedJenkinsMetadata): String {
        val methodStubs = StringBuilder()

        metadata.steps.values.sortedBy { it.name }.forEach { step ->
            generateStepMethods(step, methodStubs)
        }

        return """
            package org.jenkinsci.plugins.workflow.cps

            // No `import groovy.lang.Script` (or Closure): both are default imports, and a fully qualified
            // import gives Groovy's JVM-wide cached ClassNode this file's position, which then shows up in
            // every Jenkinsfile's AST (their script class extends that node).

            /**
             * Stub for Jenkins CpsScript.
             * Defines steps available in the pipeline.
             */
            abstract class CpsScript extends Script implements Serializable {
                // Core pipeline method
                def pipeline(Closure body) {}

                // Generated Step Methods
                $methodStubs

                // Allow dynamic method invocation for other steps
                def methodMissing(String name, args) {}

                // Allow property access
                def propertyMissing(String name) {}
            }
        """.trimIndent()
    }

    private fun generateStepMethods(step: MergedStepMetadata, builder: StringBuilder) {
        val name = step.name
        val hasBody = step.namedParams.containsKey("body")
        val positionalParams = step.positionalParams

        // 1. Map-based variant: step(name: 'foo', ...)
        builder.append("\n    def $name(Map args) {}")

        if (hasBody) {
            // 2. Map + Body variant: step(name: 'foo') { ... }
            builder.append("\n    def $name(Map args, Closure body) {}")

            // 3. Body-only variant: step { ... } (common for block steps like timeout, parallel, etc)
            builder.append("\n    def $name(Closure body) {}")
        }

        // 4. Positional variants: step('foo') or step('foo') { ... }
        if (positionalParams.isNotEmpty()) {
            val typedParams = positionalParams.map { paramName ->
                val type = step.namedParams[paramName]?.type ?: "Object"
                // Simplify type to Object if complex, or keep simple types
                if (type.contains(".")) "Object $paramName" else "$type $paramName"
            }.joinToString(", ")

            builder.append("\n    def $name($typedParams) {}")

            if (hasBody) {
                builder.append("\n    def $name($typedParams, Closure body) {}")
            }
        }
    }

    /**
     * Generates the `@Library` annotation that Jenkinsfiles use to load shared libraries.
     *
     * Jenkins compiles every Pipeline script with `org.jenkinsci.plugins.workflow.libs.Library` as an
     * implicit import, so `@Library('lib') _` needs no import statement. Without that annotation on the
     * classpath, Groovy reports "unable to resolve class Library" and the whole file is treated as broken.
     * The stub is declared in the default package so the bare name resolves the same way without an import.
     * It does not cover the real package: `import org.jenkinsci.plugins.workflow.libs.Library` still needs
     * the plugin jar on the classpath.
     *
     * Unlike the other stubs, this one is needed even when the real Pipeline plugins are on the classpath,
     * because their jars cannot supply the implicit import.
     */
    fun generateLibraryAnnotation(outputDir: Path) {
        val content = """
            import java.lang.annotation.Retention
            import java.lang.annotation.RetentionPolicy

            /**
             * Stub for Jenkins `org.jenkinsci.plugins.workflow.libs.Library`, which Pipeline scripts use without
             * an import. Loads the named shared libraries, optionally pinned with `name@version`.
             */
            @Retention(RetentionPolicy.SOURCE)
            @interface Library {
                String[] value()
                boolean changelog() default true
            }
        """.trimIndent()
        writeStub(outputDir, LIBRARY_ANNOTATION, content)
    }

    private fun writeStub(root: Path, qualifiedName: String, content: String) {
        val file = stubPath(root, qualifiedName)
        file.parent.createDirectories()
        writeFile(file, content)
    }

    companion object {
        /** Default-package name of the generated `@Library` stub; see [generateLibraryAnnotation]. */
        private const val LIBRARY_ANNOTATION = "Library"

        private const val CPS_SCRIPT = "org.jenkinsci.plugins.workflow.cps.CpsScript"
        private const val ENV_ACTION_IMPL = "org.jenkinsci.plugins.workflow.cps.EnvActionImpl"
        private const val RUN_WRAPPER = "org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper"
        private const val DOCKER = "org.jenkinsci.plugins.docker.workflow.Docker"

        private val ENV_ACTION_SOURCE = """
            package org.jenkinsci.plugins.workflow.cps

            class EnvActionImpl {
                // Allow array access: env['VAR']
                Object getAt(String key) { return null }
                void putAt(String key, Object value) {}

                // Allow property access: env.VAR
                def propertyMissing(String name) { return null }
            }
        """.trimIndent()

        private val RUN_WRAPPER_SOURCE = """
            package org.jenkinsci.plugins.workflow.support.steps.build

            class RunWrapper implements Serializable {
                String result
                String currentResult
                int number
                String displayName
                String description
                long duration
                long startTimeInMillis
                String absoluteUrl

                RunWrapper getPreviousBuild() { return null }
                RunWrapper getNextBuild() { return null }
            }
        """.trimIndent()

        private val DOCKER_SOURCE = """
             package org.jenkinsci.plugins.docker.workflow

             class Docker implements Serializable {
                 Image image(String id) { return new Image() }

                 class Image implements Serializable {
                     void inside(String args = null, Closure body) {}
                     void run(String args = null) {}
                     void push(String tag = null) {}
                 }

                 void withRegistry(String url, String credentialsId = null, Closure body) {}
             }
        """.trimIndent()

        /** Every class [generateStubs] writes. */
        private val GENERATED_CLASSES = listOf(CPS_SCRIPT, ENV_ACTION_IMPL, RUN_WRAPPER, DOCKER)

        /** True when [outputDir] holds a stub file for every class [generateStubs] writes. */
        internal fun hasCompleteStubs(outputDir: Path): Boolean = GENERATED_CLASSES.all { qualifiedName ->
            Files.isRegularFile(stubPath(outputDir, qualifiedName))
        }

        /** True when [outputDir] holds the `@Library` stub that [generateLibraryAnnotation] writes. */
        internal fun hasLibraryAnnotationStub(outputDir: Path): Boolean =
            Files.isRegularFile(stubPath(outputDir, LIBRARY_ANNOTATION))

        /** Stub source location; a name without a package lands directly in [root] (default package). */
        private fun stubPath(root: Path, qualifiedName: String): Path {
            val packagePath = qualifiedName.substringBeforeLast('.', "").replace('.', '/')
            return root.resolve(packagePath).resolve("${qualifiedName.substringAfterLast('.')}.groovy")
        }
    }
}

/**
 * Write-then-rename so a concurrent reader (another server on the same workspace)
 * never observes a truncated stub when regenerating in place.
 */
private fun writeAtomically(file: Path, content: String) {
    val tmp = Files.createTempFile(file.parent, ".${file.fileName}.", ".tmp")
    try {
        tmp.writeText(content)
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        tmp.deleteIfExists()
    }
}
