// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

import groovy.lang.GroovySystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class FileExecutionProcessTest {
    @TempDir lateinit var directory: Path

    private val runtime: String get() = Path.of(
        GroovySystem::class.java.protectionDomain.codeSource.location.toURI(),
    ).toString()

    @Test
    fun `script with static main receives arguments and stdin in a separate process`() {
        val source = directory.resolve("script with spaces.groovy")
        val content = """
            def helper() { 'unused' }
            static void main(String[] args) {
                println args[0]
                println System.in.newReader().readLine()
            }
        """.trimIndent()
        Files.writeString(source, content)
        val plan = plan(source, content)
        assertEquals("ready", plan.kind)
        val (exit, output) = runProcess(requireNotNull(plan.launch), "input value\n")
        assertEquals(0, exit)
        assertEquals(listOf("hello $() & world", "input value"), output.lines().filter(String::isNotEmpty))
    }

    @Test
    fun `main class is compiled into fresh output first and runs with selected runtime`() {
        val source = directory.resolve("Main.groovy")
        val content = """
            package example
            class Main {
                static void main(String[] args) { println 'class ran' }
            }
        """.trimIndent()
        Files.writeString(source, content)
        val plan = plan(source, content)
        assertEquals("ready", plan.kind)
        val compile = requireNotNull(plan.compile)
        val launch = requireNotNull(plan.launch)
        assertEquals(
            directory.resolve("output").toString(),
            launch.arguments[launch.arguments.indexOf("-cp") + 1].split(File.pathSeparator)[0],
        )
        val (compileExit, compileOutput) = runProcess(compile)
        assertEquals(0, compileExit, compileOutput)
        assertTrue(Files.exists(directory.resolve("output/example/Main.class")))
        val (runExit, runOutput) = runProcess(launch)
        assertEquals(0, runExit, runOutput)
        assertEquals("class ran", runOutput.trim())
    }

    @Test
    fun `syntax errors do not produce a process and system exit does not stop subsequent planning`() {
        val source = directory.resolve("Example.groovy")
        val bad = "class {"
        Files.writeString(source, bad)
        assertEquals("unavailable", plan(source, bad).kind)
        val exits = "System.exit(7)"
        Files.writeString(source, exits)
        val first = plan(source, exits)
        val (exit, _) = runProcess(requireNotNull(first.launch))
        assertEquals(7, exit)
        val next = "println 'alive'"
        Files.writeString(source, next)
        val second = plan(source, next)
        assertNotNull(second.launch)
        assertEquals(0, runProcess(requireNotNull(second.launch)).first)
    }

    @Test
    fun `an infinite program can be stopped without stopping the planner`() {
        val source = directory.resolve("Loop.groovy")
        val content = "while (true) {}"
        Files.writeString(source, content)
        val launch = requireNotNull(plan(source, content).launch)
        val process = ProcessBuilder(listOf(launch.executable) + launch.arguments)
            .directory(Path.of(launch.workingDirectory).toFile())
            .redirectErrorStream(true)
            .start()
        try {
            assertFalse(process.waitFor(1, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        }
        val next = "println 'alive'"
        Files.writeString(source, next)
        assertEquals(0, runProcess(requireNotNull(plan(source, next).launch)).first)
    }

    private fun plan(source: Path, content: String): FileExecutionResponse = FileExecutionPlanner().prepare(
        PrepareFileExecutionParams(
            uri = source.toUri().toString(),
            version = 1,
            action = "run",
            trusted = true,
            options = ExecutionOptions(
                bundledRuntime = listOf(runtime),
                arguments = listOf("hello $() & world"),
                jvmArguments = listOf("-Xmx128m"),
                outputDirectory = directory.resolve("output").toString(),
            ),
        ),
        content,
        emptyList(),
    )

    private fun runProcess(plan: ExecutionProcess, input: String = ""): Pair<Int, String> {
        val process = ProcessBuilder(listOf(plan.executable) + plan.arguments)
            .directory(Path.of(plan.workingDirectory).toFile())
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter().use { it.write(input) }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Execution process timed out")
        }
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }
}
