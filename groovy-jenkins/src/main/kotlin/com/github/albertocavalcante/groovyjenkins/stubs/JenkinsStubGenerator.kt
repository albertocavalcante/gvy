package com.github.albertocavalcante.groovyjenkins.stubs

import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata
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

    fun generateStubs(metadata: BundledJenkinsMetadata, outputDir: Path) {
        logger.info("Generating Jenkins stubs in $outputDir")
        outputDir.createDirectories()

        // 1. Generate CpsScript for 'pipeline' support
        // This is critical for Declarative Pipeline
        generateCpsScript(outputDir)

        // 2. Generate other common types if needed
        // For now, we mainly need the base types that global variables rely on
        generateEnvAction(outputDir)
        generateRunWrapper(outputDir)
        generateDocker(outputDir)
    }

    private fun generateCpsScript(outputDir: Path) {
        val className = "org.jenkinsci.plugins.workflow.cps.CpsScript"
        val content = """
            package org.jenkinsci.plugins.workflow.cps

            import groovy.lang.Closure

            /**
             * Stub for Jenkins CpsScript.
             * Defines the 'pipeline' method to support Declarative Pipeline syntax.
             */
            class CpsScript implements Serializable {
                // Support 'pipeline { ... }' syntax via call operator on the 'pipeline' variable
                // OR as a direct method if the script extends this class (which it often does in CPS)
                
                // Expose methods for standard steps to allow valid resolution within the script body
                
                def pipeline(Closure body) {}
                
                def node(String label = null, Closure body) {}
                
                def stage(String name, Closure body) {}
                
                // Allow dynamic method invocation for other steps
                def methodMissing(String name, args) {}
                
                // Allow property access
                def propertyMissing(String name) {}
            }
        """.trimIndent()

        writeStub(outputDir, className, content)
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
