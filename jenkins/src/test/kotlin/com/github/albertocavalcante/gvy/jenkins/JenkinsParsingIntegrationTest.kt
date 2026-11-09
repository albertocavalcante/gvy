package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.jenkins.stubs.JenkinsStubsLocation
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.control.CompilerConfiguration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class JenkinsParsingIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `stubs allow parsing valid Jenkinsfile with declarative syntax`() {
        val config = JenkinsConfiguration()
        val workspace = tempDir.resolve("workspace").also { it.toFile().mkdirs() }
        val stubsCacheRoot = tempDir.resolve("stubs-cache")
        val context = JenkinsContext(config, workspace, stubsCacheRoot = stubsCacheRoot)

        // Trigger classpath build and stub generation (outside the workspace)
        val classpath = context.buildClasspath(emptyList())

        // Verify stubs existence
        val stubsDir = JenkinsStubsLocation.directoryFor(stubsCacheRoot, workspace)
        assertTrue(classpath.contains(stubsDir), "Classpath should contain stubs dir")
        assertTrue(
            stubsDir.resolve("org/jenkinsci/plugins/workflow/cps/CpsScript.groovy").toFile().exists(),
            "CpsScript stub missing",
        )

        // 1. Compile stubs to bytecode
        // We do this to avoid AST transform issues that occur when using source-based base classes with generics
        val classesDir = tempDir.resolve("classes")
        classesDir.toFile().mkdirs()

        val stubCompilerConfig = CompilerConfiguration()
        stubCompilerConfig.targetDirectory = classesDir.toFile()

        val stubLoader = GroovyClassLoader(this.javaClass.classLoader, stubCompilerConfig)
        // Add stubs source path
        stubLoader.addURL(stubsDir.toUri().toURL())

        // Force compilation of CpsScript and other necessary stubs
        // Loading the class through the loader configured with targetDirectory triggers compilation to disk
        try {
            stubLoader.loadClass("org.jenkinsci.plugins.workflow.cps.CpsScript")
        } catch (e: Exception) {
            throw AssertionError("Failed to compile stubs: ${e.message}", e)
        }

        // 2. Compile the script using the compiled stubs
        val script = """
            pipeline {
                agent any
                stages {
                    stage('Build') {
                        steps {
                            sh 'echo hello'
                            echo "world"
                        }
                    }
                }
            }
        """

        val scriptConfig = CompilerConfiguration()
        scriptConfig.scriptBaseClass = "org.jenkinsci.plugins.workflow.cps.CpsScript"

        // Use a new loader that sees the compiled classes on classpath
        val scriptLoader = GroovyClassLoader(this.javaClass.classLoader, scriptConfig)
        scriptLoader.addClasspath(classesDir.toString())

        try {
            scriptLoader.parseClass(script)
        } catch (e: Exception) {
            throw AssertionError("Failed to parse Jenkinsfile with generated stubs: ${e.message}", e)
        }
    }
}
