// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import com.github.albertocavalcante.gvy.build.TestEnvironment
import groovy.lang.GroovySystem
import groovy.xml.XmlUtil
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ant.Groovyc
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Exercises preparation and a fresh process with only locally supplied artifacts. */
class GradleFileExecutionIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `project execution picks up changed dependency resources and runtime only jar offline`() {
        val installationProperty = System.getProperty("gvy.gradle.scoped.test.installation")
        assumeTrue(installationProperty != null || !TestEnvironment.isRunningInBazel, "No Gradle installation in Bazel")
        val installation = Path.of(requireNotNull(installationProperty))
        val userHome = Path.of(requireNotNull(System.getProperty("gvy.gradle.scoped.test.home")))
        val root = Files.createDirectories(temporary.resolve("project")).toRealPath()
        val source = fixture(root)
        val connector = GradleConnector.newConnector().useInstallation(installation.toFile())
            .useGradleUserHomeDir(userHome.toFile()).forProjectDirectory(root.toFile())
        connector.connect().use { connection ->
            val factory = GradleConnectionFactory { _, _ -> connection }
            val producer = GradleScopedDependencyProducer(
                factory,
                Path.of(System.getProperty("java.home")),
                initScriptDirectory = temporary.resolve("init-scripts"),
            )
            val context = context(producer, root, source)
            assertEquals(":app:classes", context.preparationTask)
            assertIs<GradleExecutionRuntime.Established>(context.runtime)
            prepare(connection, context)
            assertEquals("first|resource|runtime", launch(context(producer, root, source), source))

            Files.writeString(root.resolve("lib/src/main/groovy/example/Message.groovy"), messageSource("second"))
            val refreshed = context(producer, root, source)
            prepare(connection, refreshed)
            assertEquals("second|resource|runtime", launch(context(producer, root, source), source))

            Files.writeString(root.resolve("lib/src/main/groovy/example/Message.groovy"), "class {")
            assertFailsWith<GradleConnectionException> { prepare(connection, refreshed) }
        }
    }

    private fun context(producer: GradleScopedDependencyProducer, root: Path, source: Path): GradleExecutionContext {
        val contexts = requireNotNull(runBlocking { producer.resolveExecutionContexts(root) })
        return contexts.single { source in it.sourceFiles }
    }

    private fun prepare(connection: org.gradle.tooling.ProjectConnection, context: GradleExecutionContext) {
        connection.newBuild().forTasks(context.preparationTask).withArguments("--offline", "--max-workers=1").run()
    }

    private fun launch(context: GradleExecutionContext, source: Path): String {
        assertTrue(source in context.sourceFiles)
        val runtime = assertIs<GradleExecutionRuntime.Established>(context.runtime)
        val classpath = (runtime.jars + context.runtimeClasspath).distinct().joinToString(File.pathSeparator)
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", javaName).toString()
        val output = temporary.resolve("launch.log")
        val process = ProcessBuilder(java, "-Xmx128m", "-cp", classpath, "demo.Program")
            .directory(context.projectDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Project process timed out")
        }
        val text = Files.readString(output).trim()
        assertEquals(0, process.exitValue(), text)
        return text
    }

    private fun fixture(root: Path): Path {
        Files.createDirectories(root.resolve("app/src/main/groovy/demo"))
        Files.createDirectories(root.resolve("app/src/main/resources"))
        Files.createDirectories(root.resolve("lib/src/main/groovy/example"))
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'fixture'\ninclude 'app', 'lib'\n")
        Files.writeString(
            root.resolve("gradle.properties"),
            "org.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=256m\n" +
                "org.gradle.workers.max=1\norg.gradle.daemon.idletimeout=1000\n",
        )
        Files.writeString(root.resolve("build.gradle"), "")
        copyCompilerArtifacts(root)
        runtimeOnlyJar(root)
        val common = """
            plugins { id 'groovy' }
            repositories { maven { url = rootProject.file('repo') } }
            dependencies { implementation 'org.apache.groovy:groovy:4.0.33' }
            tasks.withType(GroovyCompile).configureEach {
                groovyClasspath = files(rootProject.file('repo/org/apache/groovy/groovy/4.0.33/groovy-4.0.33.jar'),
                    rootProject.file('groovy-ant.jar'), rootProject.file('groovy-xml.jar'),
                    rootProject.file('ant.jar'), rootProject.file('ant-launcher.jar'))
                groovyOptions.forkOptions.memoryMaximumSize = '128m'
            }
        """.trimIndent()
        Files.writeString(root.resolve("lib/build.gradle"), common)
        Files.writeString(
            root.resolve("app/build.gradle"),
            "$common\ndependencies { implementation project(':lib'); runtimeOnly files('../runtime-only.jar') }\n",
        )
        Files.writeString(root.resolve("lib/src/main/groovy/example/Message.groovy"), messageSource("first"))
        Files.writeString(root.resolve("app/src/main/resources/message.txt"), "resource")
        return Files.writeString(
            root.resolve("app/src/main/groovy/demo/Program.groovy"),
            """
            package demo
            class Program {
                static void main(String[] args) {
                    def value = Class.forName('runtime.Marker').getMethod('value').invoke(null)
                    def resource = Program.getResource('/message.txt').text
                    println(new example.Message().text() + '|' + resource + '|' + value)
                }
            }
            """.trimIndent(),
        )
    }

    private fun copyCompilerArtifacts(root: Path) {
        val groovyJar = Path.of(GroovySystem::class.java.protectionDomain.codeSource.location.toURI())
        val antJar = Path.of(Groovyc::class.java.protectionDomain.codeSource.location.toURI())
        val xmlJar = Path.of(XmlUtil::class.java.protectionDomain.codeSource.location.toURI())
        Files.copy(antJar, root.resolve("groovy-ant.jar"))
        Files.copy(xmlJar, root.resolve("groovy-xml.jar"))
        val antRuntime = Path.of(
            Class.forName("org.apache.tools.ant.Project").protectionDomain.codeSource.location.toURI(),
        )
        val antLauncher = Path.of(
            Class.forName("org.apache.tools.ant.launch.Launcher").protectionDomain.codeSource.location.toURI(),
        )
        Files.copy(antRuntime, root.resolve("ant.jar"))
        Files.copy(antLauncher, root.resolve("ant-launcher.jar"))
        val module = Files.createDirectories(root.resolve("repo/org/apache/groovy/groovy/4.0.33"))
        Files.copy(groovyJar, module.resolve("groovy-4.0.33.jar"), StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(
            module.resolve("groovy-4.0.33.pom"),
            "<project><modelVersion>4.0.0</modelVersion><groupId>org.apache.groovy</groupId>" +
                "<artifactId>groovy</artifactId><version>4.0.33</version></project>",
        )
    }

    private fun messageSource(value: String): String = "package example\nclass Message { String text() { '$value' } }\n"

    private fun runtimeOnlyJar(root: Path) {
        val directory = Files.createDirectories(root.resolve("runtime-src/runtime"))
        val javaSource = Files.writeString(
            directory.resolve("Marker.java"),
            "package runtime; public class Marker { public static String value() { return \"runtime\"; } }",
        )
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, javaSource.toString()))
        JarOutputStream(Files.newOutputStream(root.resolve("runtime-only.jar"))).use { output ->
            output.putNextEntry(JarEntry("runtime/Marker.class"))
            output.write(Files.readAllBytes(directory.resolve("Marker.class")))
            output.closeEntry()
        }
    }
}
