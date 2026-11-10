package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A root `Jenkinsfile` made every Groovy file in the workspace use the quiet Jenkins CPS profile, so ordinary
 * sources lost the normal rules (#1236). The profile is chosen per file: Jenkins pipeline files and shared library
 * code get the Jenkins profile, everything else the workspace's build-type profile.
 */
class CodeNarcProfilePerFileTest {

    @TempDir
    lateinit var root: Path

    private val resolver = HierarchicalRulesetResolver()

    private val context get() = object : WorkspaceContext {
        override val root: Path = this@CodeNarcProfilePerFileTest.root
        override fun getConfiguration() = object : DiagnosticConfiguration {
            override val isEnabled = true
            override val propertiesFile: String? = null
            override val autoDetectConfig = true
        }
    }

    private fun file(relative: String, text: String = "class X {}\n"): Path =
        root.resolve(relative).also { it.parent.createDirectories() }.apply { writeText(text) }

    private fun sourceFor(relative: String) = resolver.resolve(context, root.resolve(relative).toUri()).source

    /** A Gradle application with a CI `Jenkinsfile`: the usual shape that triggered #1236. */
    private fun gradleAppWithJenkinsfile() {
        file("build.gradle", "plugins { id 'groovy' }\n")
        file("Jenkinsfile", "pipeline { agent any }\n")
        file("src/main/groovy/App.groovy")
    }

    @Test
    fun `in a Gradle app with a Jenkinsfile, sources get the default profile and the Jenkinsfile the Jenkins one`() {
        gradleAppWithJenkinsfile()

        assertEquals(DEFAULT, sourceFor("src/main/groovy/App.groovy"))
        assertEquals(JENKINS, sourceFor("Jenkinsfile"))
    }

    @Test
    fun `a Jenkinsfile and an ordinary source never share a resolved ruleset`() {
        gradleAppWithJenkinsfile()

        val app = resolver.resolve(context, root.resolve("src/main/groovy/App.groovy").toUri())
        val pipeline = resolver.resolve(context, root.resolve("Jenkinsfile").toUri())
        val appAgain = resolver.resolve(context, root.resolve("src/main/groovy/App.groovy").toUri())

        assertNotEquals(app.rulesetContent, pipeline.rulesetContent)
        assertEquals(app, appAgain, "a cached resolution never carries the other file's profile")
    }

    @Test
    fun `a shared library keeps the Jenkins profile for vars and src, not for build source sets`() {
        file("vars/buildPlugin.groovy", "def call() {}\n")
        file("src/org/example/Helper.groovy")
        file("build.gradle", "plugins { id 'groovy' }\n")
        file("src/test/groovy/HelperTest.groovy")

        assertEquals(JENKINS, sourceFor("vars/buildPlugin.groovy"))
        assertEquals(JENKINS, sourceFor("src/org/example/Helper.groovy"))
        assertEquals(DEFAULT, sourceFor("src/test/groovy/HelperTest.groovy"))
    }

    @Test
    fun `configured pipeline file patterns pick the Jenkins profile`() {
        file("build.gradle", "plugins { id 'groovy' }\n")
        file("ci/deploy.pipeline", "pipeline { agent any }\n")
        val configured = HierarchicalRulesetResolver(isJenkinsPipelineFile = { it.path.endsWith(".pipeline") })

        assertEquals(JENKINS, configured.resolve(context, root.resolve("ci/deploy.pipeline").toUri()).source)
        assertEquals(DEFAULT, configured.resolve(context, root.resolve("Jenkinsfile").toUri()).source)
    }

    @Test
    fun `an explicit workspace ruleset still wins for every file`() {
        gradleAppWithJenkinsfile()
        file(".codenarc", "ruleset { ruleset('rulesets/basic.xml') }\n")

        val app = resolver.resolve(context, root.resolve("src/main/groovy/App.groovy").toUri())
        val pipeline = resolver.resolve(context, root.resolve("Jenkinsfile").toUri())

        assertEquals("workspace:.codenarc", app.source)
        assertEquals(app, pipeline, "the interpreted workspace ruleset is the same for both files")
        assertTrue(app.rulesetContent.contains("<ruleset-ref path=\"rulesets/basic.xml\"/>"))
    }

    @Test
    fun `a rejected workspace ruleset is still reported, with the per-file profile as the fallback`() {
        gradleAppWithJenkinsfile()
        file("codenarc.groovy", "ruleset { evaluate 'println 1' }\n")

        val app = resolver.resolve(context, root.resolve("src/main/groovy/App.groovy").toUri())
        val pipeline = resolver.resolve(context, root.resolve("Jenkinsfile").toUri())

        assertEquals(listOf("codenarc.groovy"), app.ignoredWorkspaceRulesets)
        assertEquals(listOf("codenarc.groovy"), pipeline.ignoredWorkspaceRulesets)
        assertEquals(DEFAULT, app.source)
        assertEquals(JENKINS, pipeline.source)
    }

    @Test
    fun `detection is cached per workspace root and refreshed when the root changes`() {
        file("Jenkinsfile", "pipeline { agent any }\n")
        val detections = AtomicInteger()
        val detector = object : ProjectTypeDetector {
            private val real = DefaultProjectTypeDetector()
            override fun detect(workspaceRoot: Path): ProjectType = real.detect(workspaceRoot)
                .also { detections.incrementAndGet() }
            override fun detectBuildType(workspaceRoot: Path) = real.detectBuildType(workspaceRoot)
            override fun isSharedLibrary(workspaceRoot: Path) = real.isSharedLibrary(workspaceRoot)
        }
        val counting = HierarchicalRulesetResolver(projectTypeDetector = detector)
        val app = root.resolve("src/main/groovy/App.groovy").toUri()

        repeat(5) { counting.resolve(context, app) }
        val afterRepeats = detections.get()
        file("build.gradle", "plugins { id 'groovy' }\n")
        Files.setLastModifiedTime(root, FileTime.from(Instant.now().plusSeconds(60))) // a coarse clock
        counting.resolve(context, app)

        assertEquals(1, afterRepeats, "repeated analyses reuse the detection")
        assertEquals(2, detections.get(), "a new build file is detected")
    }

    @Test
    fun `end to end, an ordinary source keeps the normal rules next to a Jenkinsfile`() = runBlocking {
        gradleAppWithJenkinsfile()
        val code = "import java.util.List\n\nclass App {\n    def run() { return 1 }\n}\n"
        val provider = CodeNarcDiagnosticProvider(context)

        val onApp = provider.analyze(code, file("src/main/groovy/App.groovy", code).toUri())
        val onPipeline = provider.analyze(code, root.resolve("Jenkinsfile").toUri())

        assertTrue(onApp.any { it.code?.left == "UnusedImport" }, "App.groovy: ${onApp.map { it.code?.left }}")
        assertFalse(
            onPipeline.any {
                it.code?.left == "UnusedImport"
            },
            "Jenkinsfile: ${onPipeline.map { it.code?.left }}",
        )
        assertTrue(onApp.none { it.severity == DiagnosticSeverity.Information && "ignored" in it.message })
    }

    private companion object {
        const val DEFAULT = "resource:codenarc/rulesets/base/default.groovy"
        const val JENKINS = "resource:codenarc/rulesets/frameworks/jenkins.groovy"
    }
}
