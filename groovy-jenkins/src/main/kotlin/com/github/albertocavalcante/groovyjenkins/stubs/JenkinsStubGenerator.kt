package com.github.albertocavalcante.groovyjenkins.stubs

import com.github.albertocavalcante.groovyjenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.MergedStepMetadata
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Generates lightweight Groovy stubs for Jenkins types defined in metadata.
 * This allows the Groovy parser to resolve types and methods without requiring
 * heavy plugin JARs on the classpath.
 */
class JenkinsStubGenerator {
    private val logger = LoggerFactory.getLogger(JenkinsStubGenerator::class.java)

    fun generateStubs(metadata: MergedJenkinsMetadata, outputDir: Path) {
        logger.info("Generating Jenkins stubs in $outputDir")
        outputDir.createDirectories()

        // 1. Generate CpsScript for 'pipeline' support
        // This is critical for Declarative Pipeline and Scripted Pipeline
        generateCpsScript(metadata, outputDir)

        // 2. Generate other common types if needed
        // For now, we mainly need the base types that global variables rely on
        generateEnvAction(outputDir)
        generateRunWrapper(outputDir)
        generateDocker(outputDir)
    }

    private fun generateCpsScript(metadata: MergedJenkinsMetadata, outputDir: Path) {
        val className = "org.jenkinsci.plugins.workflow.cps.CpsScript"
        val methodStubs = StringBuilder()

        metadata.steps.values.sortedBy { it.name }.forEach { step ->
            generateStepMethods(step, methodStubs)
        }

        val content = """
            package org.jenkinsci.plugins.workflow.cps

            import groovy.lang.Closure
            import groovy.lang.Script

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

        writeStub(outputDir, className, content)
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

    private fun generateEnvAction(outputDir: Path) {
        val className = "org.jenkinsci.plugins.workflow.cps.EnvActionImpl"
        val content = """
            package org.jenkinsci.plugins.workflow.cps
            
            class EnvActionImpl {
                // Allow array access: env['VAR']
                Object getAt(String key) { return null }
                void putAt(String key, Object value) {}
                
                // Allow property access: env.VAR
                def propertyMissing(String name) { return null }
            }
        """.trimIndent()
        writeStub(outputDir, className, content)
    }

    private fun generateRunWrapper(outputDir: Path) {
        val className = "org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper"
        val content = """
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
        writeStub(outputDir, className, content)
    }

    private fun generateDocker(outputDir: Path) {
        val className = "org.jenkinsci.plugins.docker.workflow.Docker"
        val content = """
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
        writeStub(outputDir, className, content)
    }

    private fun writeStub(root: Path, qualifiedName: String, content: String) {
        val packagePath = qualifiedName.substringBeforeLast('.').replace('.', '/')
        val simpleName = qualifiedName.substringAfterLast('.')
        val dir = root.resolve(packagePath)
        dir.createDirectories()
        val file = dir.resolve("$simpleName.groovy")
        file.writeText(content)
    }
}
