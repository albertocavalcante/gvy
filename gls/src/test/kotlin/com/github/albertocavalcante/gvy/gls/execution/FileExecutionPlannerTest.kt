// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class FileExecutionPlannerTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `forced entry selection offers even a single remaining entry`() {
        val source = directory.resolve("hello.groovy")
        val content = "println 'hello'"
        Files.writeString(source, content)
        val response = FileExecutionPlanner().prepare(
            request(source).copy(forceEntryPoint = true),
            content,
            emptyList(),
        )
        assertEquals("choices", response.kind)
        assertEquals("entryPoint", response.choiceType)
        assertEquals(listOf("script"), response.choices?.map { it.id })
    }

    @Test
    fun `forced project selection includes standalone`() {
        val source = directory.resolve("hello.groovy")
        val content = "println 'hello'"
        Files.writeString(source, content)
        val context = FileExecutionContext(
            "project", "Project", directory, setOf(source), emptyList(), emptyList(),
            null, null, "generation",
            ExecutionProcess("gradle", listOf(":classes"), directory.toString(), emptyMap()),
        )
        val response = FileExecutionPlanner().prepare(
            request(source).copy(forceProject = true),
            content,
            listOf(context),
        )
        assertEquals("choices", response.kind)
        assertEquals(listOf("standalone", "project"), response.choices?.map { it.id })
    }

    @Test
    fun `hosted Gradle and Jenkins files are directed to their host workflow`() {
        for (name in listOf("custom.gradle", "Jenkinsfile.prod", "Jenkinsfile-ci", "test.Jenkinsfile")) {
            val source = directory.resolve(name)
            Files.writeString(source, "println 'hosted'")
            val response = FileExecutionPlanner().prepare(request(source), "println 'hosted'", emptyList())
            assertEquals("unavailable", response.kind, name)
            assertTrue(response.reason!!.contains(if (name.endsWith(".gradle")) "Gradle" else "Jenkins"))
            if (name.endsWith(".gradle")) assertEquals("groovy.gradle.selectTask", response.alternativeCommand)
        }
    }

    @Test
    fun `GDSL metadata is directed to its settings workflow`() {
        val source = directory.resolve("pipeline.gdsl")
        val content = "println 'metadata'"
        Files.writeString(source, content)
        val response = FileExecutionPlanner().prepare(request(source), content, emptyList())
        assertEquals("unavailable", response.kind)
        assertTrue(response.reason!!.contains("groovy.jenkins.gdslPaths"))
        assertEquals(null, response.launch)
    }

    @Test
    fun `class without main offers compile file`() {
        val source = directory.resolve("Plain.groovy")
        val content = "class Plain {}"
        Files.writeString(source, content)
        val response = FileExecutionPlanner().prepare(request(source), content, emptyList())
        assertEquals("unavailable", response.kind)
        assertEquals("groovy.compileFile", response.alternativeCommand)
    }

    @Test
    fun `untrusted workspace never produces a process`() {
        val source = directory.resolve("hello.groovy")
        Files.writeString(source, "println 'hello'")
        val response = FileExecutionPlanner().prepare(request(source, trusted = false), "println 'hello'", emptyList())
        assertEquals("unavailable", response.kind)
    }

    @Test
    fun `standalone script uses dedicated runtime with arguments as separate tokens`() {
        val source = directory.resolve("hello world.groovy")
        val runtime = runtimeJar("groovy.jar", listOf(GROOVY_CORE))
        val content = "println args"
        Files.writeString(source, content)
        val request = request(source).copy(
            options = ExecutionOptions(
                bundledRuntime = listOf(runtime.toString()),
                arguments = listOf("hello world", "$(ignored)"),
                outputDirectory = directory.resolve("out").toString(),
            ),
        )
        val response = FileExecutionPlanner().prepare(request, content, emptyList())
        assertEquals("ready", response.kind)
        val launch = requireNotNull(response.launch)
        assertEquals(listOf(source.toRealPath().toString(), "hello world", "$(ignored)"), launch.arguments.takeLast(3))
        assertTrue(launch.arguments.contains(runtime.toString()))
    }

    @Test
    fun `language server fat jar is never accepted as execution runtime`() {
        val source = Files.writeString(directory.resolve("script.groovy"), "println 1")
        val runtime = runtimeJar("server.jar", listOf(GROOVY_CORE, SERVER_CLASS))
        assertEquals("unavailable", withRuntime(source, listOf(runtime)).kind)
    }

    @Test
    fun `non Groovy and invalid runtime jars are configuration errors`() {
        val source = Files.writeString(directory.resolve("script.groovy"), "println 1")
        val invalid = Files.writeString(directory.resolve("invalid.jar"), "not a jar")
        val unrelated = runtimeJar("unrelated.jar", listOf("example/Other.class"))
        assertEquals("unavailable", withRuntime(source, listOf(invalid)).kind)
        assertEquals("unavailable", withRuntime(source, listOf(unrelated)).kind)
    }

    @Test
    fun `duplicate Groovy cores are rejected`() {
        val source = Files.writeString(directory.resolve("script.groovy"), "println 1")
        val first = runtimeJar("first.jar", listOf(GROOVY_CORE))
        val second = runtimeJar("second.jar", listOf(GROOVY_CORE))
        assertEquals("unavailable", withRuntime(source, listOf(first, second)).kind)
    }

    @Test
    fun `runtime label uses selected runtime version when available`() {
        val source = Files.writeString(directory.resolve("script.groovy"), "println 1")
        val runtime = runtimeJar("selected.jar", listOf(GROOVY_CORE), "2.4.21")
        assertEquals("Groovy 2.4.21", withRuntime(source, listOf(runtime)).runtimeLabel)
    }

    @Test
    fun `changed disk content is rejected`() {
        val source = directory.resolve("hello.groovy")
        Files.writeString(source, "new")
        val response = FileExecutionPlanner().prepare(request(source), "old", emptyList())
        assertEquals("unavailable", response.kind)
    }

    @Test
    fun `changed model rejects a prepared project launch`() {
        val source = directory.resolve("project.groovy")
        val runtime = runtimeJar("groovy.jar", listOf(GROOVY_CORE))
        val content = "println 'hello'"
        Files.writeString(source, content)
        val process = ExecutionProcess("gradle", listOf(":classes"), directory.toString(), emptyMap())
        val context = FileExecutionContext(
            "project", "Project", directory, setOf(source), emptyList(), emptyList(),
            listOf(runtime.toString()), "Groovy", "generation-1", process,
        )
        val planner = FileExecutionPlanner()
        val first = planner.prepare(request(source), content, listOf(context))
        assertEquals("preparation", first.kind)
        val second = planner.prepare(
            request(source).copy(preparationToken = first.token),
            content,
            listOf(context.copy(generation = "generation-2")),
        )
        assertEquals("unavailable", second.kind)
    }

    @Test
    fun `changed project source resource or build configuration rejects prepared launch`() {
        val source = directory.resolve("project.groovy")
        val dependency = directory.resolve("dependency.groovy")
        val resource = directory.resolve("application.properties")
        val config = directory.resolve("build.gradle")
        val runtime = runtimeJar("groovy.jar", listOf(GROOVY_CORE))
        val content = "println 'hello'"
        Files.writeString(source, content)
        Files.writeString(dependency, "class Dependency {}")
        Files.writeString(resource, "value=old")
        Files.writeString(config, "plugins {}")
        val context = FileExecutionContext(
            "project", "Project", directory, setOf(source, dependency), emptyList(), emptyList(),
            listOf(runtime.toString()), "Groovy", "generation",
            ExecutionProcess("gradle", listOf(":classes"), directory.toString(), emptyMap()),
            inputFiles = setOf(source, dependency, resource),
        )
        val planner = FileExecutionPlanner()
        val params = request(source)
        val first = planner.prepare(params, content, listOf(context))
        Files.writeString(dependency, "class Dependency { int value }")
        assertEquals(
            "unavailable",
            planner.prepare(params.copy(preparationToken = first.token), content, listOf(context)).kind,
        )
        Files.writeString(dependency, "class Dependency {}")
        Files.writeString(resource, "value=new")
        assertEquals(
            "unavailable",
            planner.prepare(params.copy(preparationToken = first.token), content, listOf(context)).kind,
        )
        Files.writeString(resource, "value=old")
        Files.writeString(config, "plugins { id 'groovy' }")
        assertEquals(
            "unavailable",
            planner.prepare(params.copy(preparationToken = first.token), content, listOf(context)).kind,
        )
    }

    @Test
    fun `launch revalidation rejects a changed source and accepts fresh compiled output`() {
        val source = directory.resolve("Main.groovy")
        val runtime = runtimeJar("groovy.jar", listOf(GROOVY_CORE))
        val content = "class Main { static void main(String[] args) {} }"
        Files.writeString(source, content)
        val params = request(source).copy(
            options = ExecutionOptions(
                bundledRuntime = listOf(runtime.toString()),
                outputDirectory = directory.resolve("out").toString(),
            ),
        )
        val planner = FileExecutionPlanner()
        val ready = planner.prepare(params, content, emptyList())
        assertEquals("ready", ready.kind)
        Files.createDirectory(directory.resolve("out"))
        Files.writeString(directory.resolve("out/Main.class"), "compiled")
        val revalidated = planner.prepare(params.copy(launchToken = ready.launchToken), content, emptyList())
        assertEquals("ready", revalidated.kind)
        assertEquals(null, revalidated.compile)
        Files.writeString(source, "class Changed {}")
        assertEquals(
            "unavailable",
            planner.prepare(params.copy(launchToken = ready.launchToken), content, emptyList()).kind,
        )
    }

    @Test
    fun `project resources alone do not establish source ownership`() {
        val resource = Files.writeString(directory.resolve("resource.groovy"), "println 1")
        val source = Files.writeString(directory.resolve("source.groovy"), "println 2")
        val runtime = runtimeJar("groovy.jar", listOf(GROOVY_CORE))
        val context = FileExecutionContext(
            "project", "Project", directory, setOf(source), emptyList(), emptyList(),
            listOf(runtime.toString()), "Groovy", "generation",
            ExecutionProcess("gradle", listOf(":classes"), directory.toString(), emptyMap()),
            inputFiles = setOf(source, resource),
        )
        val params = request(resource).copy(
            options = ExecutionOptions(
                bundledRuntime = listOf(runtime.toString()),
                outputDirectory = directory.resolve("out").toString(),
            ),
        )
        val response = FileExecutionPlanner().prepare(params, Files.readString(resource), listOf(context))
        assertEquals("ready", response.kind)
        assertEquals("Standalone", response.contextLabel)
    }

    @Test
    fun `owned file with incomplete project classpath never falls back to standalone`() {
        val source = directory.resolve("project.groovy")
        val runtime = runtimeJar("groovy.jar", listOf(GROOVY_CORE))
        val content = "println 'hello'"
        Files.writeString(source, content)
        val context = FileExecutionContext(
            "project", "Project", directory, setOf(source), emptyList(), emptyList(),
            listOf(runtime.toString()), "Groovy", "generation",
            ExecutionProcess("gradle", listOf(":classes"), directory.toString(), emptyMap()),
            complete = false,
        )
        assertEquals("unavailable", FileExecutionPlanner().prepare(request(source), content, listOf(context)).kind)
    }

    @Test
    fun `unknown project runtime never falls back to bundled runtime`() {
        val source = directory.resolve("project.groovy")
        val runtime = runtimeJar("groovy.jar", listOf(GROOVY_CORE))
        val content = "println 'hello'"
        Files.writeString(source, content)
        val context = FileExecutionContext(
            "project", "Project", directory, setOf(source), emptyList(), emptyList(), null, null, "one",
            ExecutionProcess("gradle", listOf(":classes"), directory.toString(), emptyMap()),
        )
        val params = request(source).copy(
            options = ExecutionOptions(
                bundledRuntime = listOf(runtime.toString()),
                outputDirectory = directory.resolve("out").toString(),
            ),
        )
        assertEquals("unavailable", FileExecutionPlanner().prepare(params, content, listOf(context)).kind)
    }

    private fun withRuntime(source: Path, runtime: List<Path>): FileExecutionResponse {
        val options = ExecutionOptions(
            explicitRuntime = runtime.map(Path::toString),
            outputDirectory = directory.resolve("out").toString(),
        )
        return FileExecutionPlanner().prepare(
            request(source).copy(options = options),
            Files.readString(source),
            emptyList(),
        )
    }

    private fun runtimeJar(name: String, entries: List<String>, version: String = "4.0.33"): Path {
        val path = directory.resolve(name)
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.IMPLEMENTATION_VERSION] = version
        }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jar ->
            for (entry in entries) {
                jar.putNextEntry(JarEntry(entry))
                jar.closeEntry()
            }
        }
        return path
    }

    private companion object {
        const val GROOVY_CORE = "groovy/lang/GroovySystem.class"
        const val SERVER_CLASS = "com/github/albertocavalcante/gvy/gls/GroovyLanguageServer.class"
    }

    private fun request(source: Path, trusted: Boolean = true) = PrepareFileExecutionParams(
        uri = source.toUri().toString(),
        version = 1,
        action = "run",
        trusted = trusted,
        options = ExecutionOptions(outputDirectory = directory.resolve("out").toString()),
    )
}
