// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import com.github.albertocavalcante.gvy.build.TargetDependencyState
import com.github.albertocavalcante.gvy.build.TestEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Mandatory offline transport test under Gradle, using the distribution already running this test task.
 * Bazel's sandbox supplies no Gradle installation, so the Bazel target reports it as skipped.
 */
class GradleScopedDependencyProducerIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path
    private val connections = CopyOnWriteArrayList<ProjectConnection>()

    @AfterEach
    fun closeConnections() {
        connections.forEach(ProjectConnection::close)
    }

    private fun producer(gradleVersion: String? = null): GradleScopedDependencyProducer {
        val property = System.getProperty(INSTALLATION_PROPERTY)
        // Gradle's test task always supplies the installation; only Bazel's sandbox lacks one.
        assumeTrue(property != null || !TestEnvironment.isRunningInBazel, "Skipped in Bazel: no Gradle installation")
        val installation = Path.of(requireNotNull(property) { "$INSTALLATION_PROPERTY is required under Gradle" })
        require(Files.isDirectory(installation.resolve("lib"))) { "Running Gradle installation is required" }
        val userHome = Files.createDirectories(
            Path.of(requireNotNull(System.getProperty("gvy.gradle.scoped.test.home"))),
        )
        val factory = GradleConnectionFactory { directory, _ ->
            val connector = GradleConnector.newConnector()
            when (gradleVersion) {
                null -> connector.useInstallation(installation.toFile())
                else -> connector.useGradleVersion(gradleVersion)
            }
            connector.useGradleUserHomeDir(userHome.toFile()).forProjectDirectory(directory.toFile())
                .connect().also(connections::add)
        }
        val scratch = Files.createDirectories(temporaryDirectory.resolve("producer"))
        return GradleScopedDependencyProducer(
            factory,
            Path.of(System.getProperty("java.home")),
            initScriptDirectory = scratch.resolve("init-scripts"),
        )
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = ["", PARALLEL, CONFIGURATION_CACHE])
    fun `observes real Groovy source sets effective order coordinates and isolated failures offline`(mode: String) {
        val producer = producer()
        val root = Files.createDirectories(temporaryDirectory.resolve("fixture")).toRealPath()
        fixture(root, mode)
        val provenance = DependencyProvenance("offline-integration-observation", 7)
        val result = assertIs<TargetDependencyEvidence.Scoped>(runBlocking { producer.resolve(root, provenance) })
        assertTrue(result.enumerationComplete)
        val main = result.target(root, "main")
        val test = result.target(root, "test")
        val integration = result.target(root.resolve("sub"), "integrationTest")
        val broken = result.target(root.resolve("sub"), "broken")
        val localOnly = result.target(root.resolve("sub"), "localOnly")

        assertTrue(result.snapshots.all { it.provenance === provenance })
        assertEquals("2.4-groovy-4.0", main.artifacts.single().coordinates?.version)
        assertEquals("org.spockframework", main.artifacts.single().coordinates?.group)
        assertEquals("spock-core", main.artifacts.single().coordinates?.module)
        assertEquals("compileClasspath", main.target.configuration)
        assertEquals("2.3-groovy-4.0", integration.artifacts[2].coordinates?.version)
        assertEquals(
            listOf(
                root.resolve("sub/local-first"),
                root.resolve("sub/local-second"),
                root.resolve("repo/org/spockframework/spock-core/2.3-groovy-4.0/spock-core-2.3-groovy-4.0.jar"),
                root.resolve("sub/local-tail"),
            ),
            integration.artifacts.map { it.path },
        )
        assertNull(integration.target.configuration)
        assertNull(integration.artifacts.first().coordinates)
        assertTrue(root.resolve("sub/custom/specifications") in integration.sourceRoots)
        assertNotEquals(main.target.targetId, integration.target.targetId)
        assertTrue(test.artifacts.any { it.coordinates == null && it.path.startsWith(root.resolve("build")) })
        assertTrue(test.artifacts.filter { it.coordinates == null }.all { !Files.exists(it.path) })
        assertEquals(TargetDependencyState.UNRESOLVED, broken.state)
        assertEquals(TargetDependencyState.COMPLETE, localOnly.state)
        assertEquals(
            listOf(root.resolve("sub/only-before"), root.resolve("sub/only-after")),
            localOnly.artifacts.map {
                it.path
            },
        )
        assertTrue(localOnly.artifacts.all { it.coordinates == null })
        assertNull(localOnly.target.configuration)
        assertSame(provenance, localOnly.provenance)
        val badRoots = result.target(root.resolve("sub"), "badRoots")
        assertEquals(TargetDependencyState.UNRESOLVED, badRoots.state)
        assertTrue(badRoots.sourceRoots.isEmpty())
        assertFalse(Files.exists(root.resolve("build/classes")), "The query must not compile the fixture")
    }

    @Test
    fun `observes included files and Gradle exclusions`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("membership")).toRealPath()
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'membership'")
        Files.writeString(
            root.resolve("build.gradle"),
            """
            plugins { id 'groovy' }
            sourceSets.main.groovy.exclude 'excluded/**'
            sourceSets.main.resources.srcDir 'src/main/groovy'
            sourceSets.main.resources.srcDir 'resourceOnly'
            tasks.named('compileGroovy') { exclude 'taskExcluded/**' }
            """.trimIndent(),
        )
        val sources = Files.createDirectories(root.resolve("src/main/groovy"))
        val included = Files.writeString(sources.resolve("Included.groovy"), "class Included {}")
        val excludedDir = Files.createDirectories(sources.resolve("excluded"))
        val excluded = Files.writeString(excludedDir.resolve("Broken.groovy"), "class Broken {}")
        val taskExcludedDir = Files.createDirectories(sources.resolve("taskExcluded"))
        val taskExcluded = Files.writeString(taskExcludedDir.resolve("TaskExcluded.groovy"), "class TaskExcluded {}")

        val result = assertIs<TargetDependencyEvidence.Scoped>(
            runBlocking { producer().resolve(root, DependencyProvenance("membership-test", 1)) },
        )
        val files = requireNotNull(result.target(root, "main").sourceFiles)
        assertTrue(included in files)
        assertFalse(excluded in files)
        assertFalse(taskExcluded in files)
        assertFalse(root.resolve("resourceOnly") in result.target(root, "main").sourceRoots)

        val added = Files.writeString(sources.resolve("Added.groovy"), "class Added {}")
        Files.writeString(
            root.resolve("build.gradle"),
            """
            plugins { id 'groovy' }
            sourceSets.main.groovy.exclude 'excluded/**', 'Included.groovy'
            sourceSets.main.resources.srcDir 'src/main/groovy'
            sourceSets.main.resources.srcDir 'resourceOnly'
            tasks.named('compileGroovy') { exclude 'taskExcluded/**' }
            """.trimIndent(),
        )
        val refreshed = assertIs<TargetDependencyEvidence.Scoped>(
            runBlocking { producer().resolve(root, DependencyProvenance("membership-test", 2)) },
        )
        val updatedFiles = requireNotNull(refreshed.target(root, "main").sourceFiles)
        assertTrue(added in updatedFiles)
        assertFalse(included in updatedFiles)
        assertFalse(excluded in updatedFiles)
        assertFalse(taskExcluded in updatedFiles)
    }

    @Test
    fun `mixed Java and Groovy projects retain authoritative source membership`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("mixed-projects")).toRealPath()
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'mixed'; include 'javaLib'")
        Files.writeString(
            root.resolve("build.gradle"),
            """
            plugins { id 'groovy' }
            sourceSets.main.groovy.exclude 'Broken.groovy'
            """.trimIndent(),
        )
        val groovy = Files.createDirectories(root.resolve("src/main/groovy"))
        val included = Files.writeString(groovy.resolve("Included.groovy"), "class Included {}")
        val excluded = Files.writeString(groovy.resolve("Broken.groovy"), "class Broken {}")
        val javaRoot = Files.createDirectories(root.resolve("javaLib"))
        Files.writeString(javaRoot.resolve("build.gradle"), "plugins { id 'java' }")
        val javaDir = Files.createDirectories(javaRoot.resolve("src/main/java"))
        val javaFile = Files.writeString(javaDir.resolve("Library.java"), "class Library {}")

        val result = assertIs<TargetDependencyEvidence.Scoped>(
            runBlocking { producer().resolve(root, DependencyProvenance("mixed-test", 1)) },
        )
        assertTrue(result.enumerationComplete)
        val groovyFiles = requireNotNull(result.target(root, "main").sourceFiles)
        assertTrue(included in groovyFiles)
        assertFalse(excluded in groovyFiles)
        assertTrue(javaFile in requireNotNull(result.target(javaRoot, "main").sourceFiles))
    }

    @Test
    fun `unmodeled Groovy compile task makes source set enumeration incomplete`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("extra-compiler")).toRealPath()
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'extra-compiler'")
        Files.writeString(
            root.resolve("build.gradle"),
            """
            plugins { id 'groovy' }
            tasks.register('customCompile', GroovyCompile) { source 'extra' }
            """.trimIndent(),
        )
        Files.createDirectories(root.resolve("src/main/groovy"))
        val extraDir = Files.createDirectories(root.resolve("extra"))
        Files.writeString(extraDir.resolve("Extra.groovy"), "class Extra {}")

        val result = assertIs<TargetDependencyEvidence.Scoped>(
            runBlocking { producer().resolve(root, DependencyProvenance("extra-task-test", 1)) },
        )
        assertFalse(result.enumerationComplete)
        assertNotNull(result.target(root, "main").sourceFiles)
    }

    @Test
    fun `unmodeled Java compile task makes source set enumeration incomplete`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("extra-java-compiler")).toRealPath()
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'extra-java-compiler'")
        Files.writeString(
            root.resolve("build.gradle"),
            """
            plugins { id 'groovy' }
            sourceSets.main.java.exclude 'Extra.java'
            tasks.register('customJavaCompile', JavaCompile) { source 'src/main/java/Extra.java' }
            """.trimIndent(),
        )
        val javaDir = Files.createDirectories(root.resolve("src/main/java"))
        val extra = Files.writeString(javaDir.resolve("Extra.java"), "class Extra {}")

        val result = assertIs<TargetDependencyEvidence.Scoped>(
            runBlocking { producer().resolve(root, DependencyProvenance("extra-java-task-test", 1)) },
        )
        assertFalse(result.enumerationComplete)
        assertFalse(extra in requireNotNull(result.target(root, "main").sourceFiles))
    }

    @Test
    fun `task sources outside source set roots are reported`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("extra-membership")).toRealPath()
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'extra-membership'")
        Files.writeString(
            root.resolve("build.gradle"),
            """
            plugins { id 'groovy' }
            tasks.named('compileGroovy') { source 'extra' }
            """.trimIndent(),
        )
        val extra = Files.createDirectories(root.resolve("extra"))
        val file = Files.writeString(extra.resolve("Extra.groovy"), "class Extra {}")
        val result = assertIs<TargetDependencyEvidence.Scoped>(
            runBlocking { producer().resolve(root, DependencyProvenance("extra-test", 1)) },
        )
        assertTrue(file in requireNotNull(result.target(root, "main").sourceFiles))
    }

    @Test
    fun `execution observation uses exact membership and ordered runtime paths for each source set`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("execution")).toRealPath()
        Files.createDirectories(root.resolve("app/custom"))
        Files.createDirectories(root.resolve("app/extra"))
        Files.createDirectories(root.resolve("app/src/custom/resources"))
        Files.createDirectories(root.resolve("lib/src/main/groovy"))
        Files.writeString(
            root.resolve("settings.gradle"),
            "gradle.startParameter.offline = true\ninclude 'app', 'lib'\n",
        )
        Files.writeString(root.resolve("gradle.properties"), GRADLE_PROPERTIES)
        Files.writeString(root.resolve("build.gradle"), "")
        Files.writeString(root.resolve("lib/build.gradle"), "plugins { id 'groovy' }\n")
        Files.writeString(
            root.resolve("app/build.gradle"),
            """
            plugins { id 'groovy' }
            sourceSets { custom }
            sourceSets.custom.groovy.srcDirs = ['custom']
            sourceSets.custom.groovy.exclude '**/Excluded.groovy'
            tasks.named('compileCustomGroovy') { exclude '**/TaskExcluded.groovy'; source 'extra' }
            sourceSets.custom.compileClasspath = files('compile-first', 'compile-last')
            sourceSets.custom.runtimeClasspath = files('runtime-first', 'runtime-only')
            """.trimIndent(),
        )
        val resource = Files.writeString(root.resolve("app/src/custom/resources/message.txt"), "hello")
        val taskExcluded = Files.writeString(root.resolve("app/custom/TaskExcluded.groovy"), "class TaskExcluded {}")
        val outsideRoot = Files.writeString(root.resolve("app/extra/Extra.groovy"), "class Extra {}")
        val included = Files.writeString(root.resolve("app/custom/Included.groovy"), "class Included {}")
        val excluded = Files.writeString(root.resolve("app/custom/Excluded.groovy"), "class Excluded {}")
        val lib = Files.writeString(root.resolve("lib/src/main/groovy/Lib.groovy"), "class Lib {}")

        val contexts = requireNotNull(runBlocking { producer().resolveExecutionContexts(root) })
        val custom = contexts.single { it.projectDirectory == root.resolve("app") && it.sourceSet == "custom" }
        assertEquals(":app", custom.projectPath)
        assertTrue(included in custom.sourceFiles)
        assertTrue(resource in custom.inputFiles)
        assertFalse(resource in custom.sourceFiles)
        assertFalse(taskExcluded in custom.sourceFiles)
        assertTrue(outsideRoot in custom.sourceFiles)
        assertTrue(outsideRoot in custom.inputFiles)
        assertFalse(excluded in custom.sourceFiles)
        assertFalse(lib in custom.sourceFiles)
        assertEquals(
            listOf(root.resolve("app/compile-first"), root.resolve("app/compile-last")),
            custom.compileClasspath,
        )
        assertEquals(
            listOf(root.resolve("app/runtime-first"), root.resolve("app/runtime-only")),
            custom.runtimeClasspath,
        )
        assertEquals(":app:customClasses", custom.preparationTask)
        assertTrue(root.resolve("app/build/classes/groovy/custom") in custom.outputDirectories)
        assertTrue(root.resolve("app/build/resources/custom") in custom.outputDirectories)
        assertEquals(GradleExecutionRuntime.Unknown, custom.runtime)
        assertTrue(contexts.any { lib in it.sourceFiles && it.projectDirectory == root.resolve("lib") })
    }

    @Test
    fun `unmodeled compiler tasks make execution membership unavailable`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("execution-extra-compiler")).toRealPath()
        Files.writeString(root.resolve("settings.gradle"), "gradle.startParameter.offline = true\n")
        Files.writeString(root.resolve("gradle.properties"), GRADLE_PROPERTIES)
        Files.writeString(
            root.resolve("build.gradle"),
            "plugins { id 'groovy' }\ntasks.register('extraCompile', GroovyCompile) { source 'extra' }\n",
        )

        assertNull(runBlocking { producer().resolveExecutionContexts(root) })
    }

    @Test
    fun `an unresolved test classpath does not erase main or its own membership`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("execution-partial")).toRealPath()
        Files.createDirectories(root.resolve("src/main/groovy"))
        Files.createDirectories(root.resolve("src/test/groovy"))
        Files.writeString(root.resolve("settings.gradle"), "gradle.startParameter.offline = true\n")
        Files.writeString(root.resolve("gradle.properties"), GRADLE_PROPERTIES)
        Files.writeString(
            root.resolve("build.gradle"),
            "plugins { id 'groovy' }\n" +
                "repositories { maven { url = file('repo') } }\n" +
                "dependencies { testImplementation 'org.example:missing:1' }\n",
        )
        val mainFile = Files.writeString(root.resolve("src/main/groovy/Main.groovy"), "class Main {}")
        val testFile = Files.writeString(root.resolve("src/test/groovy/Spec.groovy"), "class Spec {}")

        val contexts = requireNotNull(runBlocking { producer().resolveExecutionContexts(root) })
        val main = contexts.single { it.sourceSet == "main" }
        val test = contexts.single { it.sourceSet == "test" }
        assertTrue(main.complete)
        assertTrue(mainFile in main.sourceFiles)
        assertFalse(test.complete)
        assertTrue(testFile in test.sourceFiles)
        assertEquals(GradleExecutionRuntime.Unknown, test.runtime)
    }

    @Test
    fun `runtime identity includes only resolved Groovy modules and preserves runtime-only dependencies`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("execution-runtime")).toRealPath()
        Files.writeString(root.resolve("settings.gradle"), "gradle.startParameter.offline = true\n")
        Files.writeString(root.resolve("gradle.properties"), GRADLE_PROPERTIES)
        Files.writeString(
            root.resolve("build.gradle"),
            """
            plugins { id 'groovy' }
            repositories { maven { url = file('repo') } }
            dependencies {
                implementation 'org.apache.groovy:groovy:4.0.33'
                runtimeOnly 'org.example:extra:1'
            }
            """.trimIndent(),
        )
        val groovy = executionModule(root, "org.apache.groovy", "groovy", "4.0.33", true)
        val extra = executionModule(root, "org.example", "extra", "1", false)

        val contexts = requireNotNull(runBlocking { producer().resolveExecutionContexts(root) })
        val main = contexts.single { it.sourceSet == "main" }
        val runtime = assertIs<GradleExecutionRuntime.Established>(main.runtime)
        assertEquals("4.0.33", runtime.version)
        assertEquals(listOf(groovy), runtime.jars)
        assertTrue(extra in main.runtimeClasspath)
        assertFalse(extra in main.compileClasspath)
    }

    @Test
    fun `conflicting Groovy cores do not select a runtime`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("execution-conflict")).toRealPath()
        Files.writeString(root.resolve("settings.gradle"), "gradle.startParameter.offline = true\n")
        Files.writeString(root.resolve("gradle.properties"), GRADLE_PROPERTIES)
        Files.writeString(
            root.resolve("build.gradle"),
            """
            plugins { id 'groovy' }
            repositories { maven { url = file('repo') } }
            dependencies {
                implementation 'org.apache.groovy:groovy:4.0.33'
                implementation 'org.codehaus.groovy:groovy:2.4.21'
            }
            """.trimIndent(),
        )
        executionModule(root, "org.apache.groovy", "groovy", "4.0.33", true)
        executionModule(root, "org.codehaus.groovy", "groovy", "2.4.21", true)

        val contexts = requireNotNull(runBlocking { producer().resolveExecutionContexts(root) })
        assertEquals(GradleExecutionRuntime.Conflict, contexts.single { it.sourceSet == "main" }.runtime)
    }

    @Test
    fun `different compile and runtime Groovy cores are rejected`() {
        val root = Files.createDirectories(temporaryDirectory.resolve("execution-classpath-conflict")).toRealPath()
        Files.writeString(root.resolve("settings.gradle"), "gradle.startParameter.offline = true\n")
        Files.writeString(root.resolve("gradle.properties"), GRADLE_PROPERTIES)
        Files.writeString(
            root.resolve("build.gradle"),
            """
            plugins { id 'groovy' }
            repositories { maven { url = file('repo') } }
            dependencies {
                compileOnly 'org.codehaus.groovy:groovy:2.4.21'
                runtimeOnly 'org.apache.groovy:groovy:4.0.33'
            }
            """.trimIndent(),
        )
        executionModule(root, "org.apache.groovy", "groovy", "4.0.33", true)
        executionModule(root, "org.codehaus.groovy", "groovy", "2.4.21", true)

        val contexts = requireNotNull(runBlocking { producer().resolveExecutionContexts(root) })
        assertEquals(GradleExecutionRuntime.Conflict, contexts.single { it.sourceSet == "main" }.runtime)
    }

    private fun executionModule(root: Path, group: String, name: String, version: String, core: Boolean): Path {
        val directory = Files.createDirectories(root.resolve("repo/${group.replace('.', '/')}/$name/$version"))
        val jar = directory.resolve("$name-$version.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { output ->
            if (core) {
                output.putNextEntry(ZipEntry("groovy/lang/GroovySystem.class"))
                output.write(byteArrayOf(0))
                output.closeEntry()
            }
        }
        Files.writeString(
            directory.resolve("$name-$version.pom"),
            "<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId>" +
                "<artifactId>$name</artifactId><version>$version</version></project>",
        )
        return jar
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = [PARALLEL, "$PARALLEL\n$ISOLATED_PROJECTS"])
    fun `a project dependency resolves under parallel configuration (#1363)`(mode: String) {
        assertProjectDependencyObserved(producer(), mode)
    }

    /**
     * Older Gradle versions download their distribution, so they run only when requested, for example
     * `GVY_SCOPED_TEST_GRADLE_VERSIONS=7.3.3,8.14.6`. The Gradle Compatibility workflow sets it weekly to 7.3.3,
     * the latest 8.14.x and the latest release (#1376). Set but naming no version is a broken job, not a skip.
     */
    @Test
    fun `a project dependency resolves on the requested older Gradle versions`() {
        val requested = System.getenv(VERSIONS_VARIABLE)
        assumeTrue(requested != null, "Set $VERSIONS_VARIABLE to run")
        val versions = requireNotNull(requested).split(',').map(String::trim).filter(String::isNotEmpty)
        assertTrue(versions.isNotEmpty(), "$VERSIONS_VARIABLE is set but names no Gradle version: '$requested'")
        versions.forEach { version -> assertProjectDependencyObserved(producer(version), PARALLEL, version) }
    }

    private fun assertProjectDependencyObserved(
        producer: GradleScopedDependencyProducer,
        mode: String,
        tag: String = "",
    ) {
        val root = Files.createDirectories(temporaryDirectory.resolve("project-dependency$tag")).toRealPath()
        projectDependencyFixture(root, mode)

        val result = assertIs<TargetDependencyEvidence.Scoped>(runBlocking { producer.resolve(root, PROVENANCE) })

        val app = result.target(root.resolve("app"), "main")
        val lib = result.target(root.resolve("lib"), "main")
        assertEquals(
            TargetDependencyState.COMPLETE,
            app.state,
            "$tag: a subproject target must resolve in parallel mode",
        )
        assertEquals(TargetDependencyState.COMPLETE, lib.state, tag)
        assertEquals(
            listOf(
                root.resolve("lib/build/libs/lib.jar"),
                root.resolve("repo/org/spockframework/spock-core/2.4-groovy-4.0/spock-core-2.4-groovy-4.0.jar"),
            ),
            app.artifacts.map { it.path },
            "$tag: the project output first, then the external jar, in resolution order",
        )
        assertEquals("2.4-groovy-4.0", app.artifacts[1].coordinates?.version)
        assertFalse(Files.exists(root.resolve("lib/build")), "$tag: the query must not build the dependency")
    }

    private fun projectDependencyFixture(root: Path, mode: String) {
        Files.createDirectories(root.resolve("app/src/main/groovy"))
        Files.createDirectories(root.resolve("lib/src/main/groovy"))
        Files.writeString(
            root.resolve("settings.gradle"),
            "gradle.startParameter.offline = true\nrootProject.name = 'project-dependency'\ninclude 'app', 'lib'\n",
        )
        Files.writeString(root.resolve("gradle.properties"), "$GRADLE_PROPERTIES\n$mode\n")
        // No cross-project configuration, so the same fixture is valid under isolated projects
        Files.writeString(root.resolve("build.gradle"), "")
        val repository = "repositories { maven { url = rootProject.file('repo') } }"
        Files.writeString(root.resolve("lib/build.gradle"), "plugins { id 'groovy' }\n")
        Files.writeString(
            root.resolve("app/build.gradle"),
            """
            plugins { id 'groovy' }
            ${repository.replace("rootProject.file('repo')", "file('../repo')")}
            dependencies {
                implementation project(':lib')
                implementation 'org.spockframework:spock-core:2.4-groovy-4.0'
            }
            """.trimIndent(),
        )
        module(root, "2.4-groovy-4.0")
    }

    @Test
    fun `deadline cancels a real blocked build and yields legacy`() {
        val producer = producer()
        val root = blockingFixture()
        val started = System.nanoTime()

        val result = runBlocking { producer.resolve(root, PROVENANCE, timeout = 3.seconds) }

        assertSame(TargetDependencyEvidence.Legacy, result)
        assertTrue(System.nanoTime() - started < BLOCKING_MILLIS * 1_000_000, "Deadline must not wait for the build")
    }

    @Test
    fun `caller cancellation of a real blocked build propagates promptly`() {
        val producer = producer()
        val root = blockingFixture()
        val started = System.nanoTime()

        assertFailsWith<CancellationException> {
            runBlocking {
                val pending = async { producer.resolve(root, PROVENANCE) }
                delay(3.seconds)
                pending.cancel()
                pending.await()
            }
        }
        assertTrue(System.nanoTime() - started < BLOCKING_MILLIS * 1_000_000, "Cancellation must not wait")
    }

    private fun blockingFixture(): Path {
        val root = Files.createDirectories(temporaryDirectory.resolve("blocking")).toRealPath()
        Files.writeString(
            root.resolve("settings.gradle"),
            "gradle.startParameter.offline = true\nrootProject.name = 'blocking'\nThread.sleep($BLOCKING_MILLIS)\n",
        )
        Files.writeString(root.resolve("gradle.properties"), GRADLE_PROPERTIES)
        Files.writeString(root.resolve("build.gradle"), "apply plugin: 'groovy'\n")
        return root
    }

    private fun TargetDependencyEvidence.Scoped.target(directory: Path, sourceSet: String): TargetDependencySnapshot =
        snapshots.single { it.target.projectDirectory == directory && it.target.sourceSet == sourceSet }

    private fun fixture(root: Path, mode: String = "") {
        Files.createDirectories(root.resolve("sub"))
        Files.writeString(
            root.resolve("settings.gradle"),
            """
            gradle.startParameter.offline = true
            rootProject.name = 'scoped-groovy-fixture'
            include 'sub'
            """.trimIndent(),
        )
        Files.writeString(root.resolve("gradle.properties"), "$GRADLE_PROPERTIES\n$mode\n")
        Files.writeString(
            root.resolve("build.gradle"),
            """
            allprojects {
                apply plugin: 'groovy'
                repositories { maven { url = rootProject.file('repo') } }
            }
            dependencies {
                implementation 'org.spockframework:spock-core:2.3-groovy-4.0'
                constraints { implementation 'org.spockframework:spock-core:2.4-groovy-4.0' }
            }
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("sub/build.gradle"),
            """
            sourceSets { integrationTest; broken; localOnly; badRoots }
            sourceSets.badRoots.groovy.srcDir({ throw new GradleException('lazy root failure') })
            sourceSets.integrationTest.groovy.srcDirs = ['custom/specifications']
            sourceSets.integrationTest.compileClasspath = files('local-first', 'local-second') +
                configurations.integrationTestCompileClasspath + files('local-tail')
            sourceSets.localOnly.compileClasspath = files('only-before', 'only-after')
            dependencies {
                integrationTestImplementation 'org.spockframework:spock-core:2.3-groovy-4.0'
                brokenImplementation 'org.spockframework:missing:1'
                localOnlyImplementation 'org.spockframework:unused-missing:1'
            }
            """.trimIndent(),
        )
        module(root, "2.3-groovy-4.0")
        module(root, "2.4-groovy-4.0")
    }

    private fun module(root: Path, version: String) {
        val directory = Files.createDirectories(root.resolve("repo/org/spockframework/spock-core/$version"))
        ZipOutputStream(Files.newOutputStream(directory.resolve("spock-core-$version.jar"))).use { output ->
            output.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            output.write("Manifest-Version: 1.0\n".toByteArray())
            output.closeEntry()
        }
        Files.writeString(
            directory.resolve("spock-core-$version.pom"),
            """
            <project><modelVersion>4.0.0</modelVersion><groupId>org.spockframework</groupId>
            <artifactId>spock-core</artifactId><version>$version</version></project>
            """.trimIndent(),
        )
    }

    private companion object {
        const val INSTALLATION_PROPERTY = "gvy.gradle.scoped.test.installation"
        const val BLOCKING_MILLIS = 10_000L
        const val PARALLEL = "org.gradle.parallel=true"
        const val VERSIONS_VARIABLE = "GVY_SCOPED_TEST_GRADLE_VERSIONS"
        const val CONFIGURATION_CACHE = "org.gradle.configuration-cache=true"
        const val ISOLATED_PROJECTS = "org.gradle.unsafe.isolated-projects=true"
        val PROVENANCE = DependencyProvenance("offline-integration-observation", 8)
        val GRADLE_PROPERTIES =
            """
            org.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=256m
            org.gradle.daemon.idletimeout=1000
            """.trimIndent()
    }
}
