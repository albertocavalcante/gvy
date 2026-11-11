package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import com.github.albertocavalcante.gvy.diagnostics.codenarc.WorkspaceRulesetInterpreter.Result
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Configuration for a resolved ruleset.
 */
data class RulesetConfiguration(
    val rulesetContent: String,
    val propertiesFile: String? = null,
    val source: String = "unknown",
    /** Workspace ruleset files that exist but were deliberately not loaded (see [HierarchicalRulesetResolver]). */
    val ignoredWorkspaceRulesets: List<String> = emptyList(),
)

/**
 * Interface for resolving rulesets based on workspace configuration.
 */
interface RulesetResolver {
    /**
     * Resolves the appropriate ruleset configuration for the given context.
     *
     * @param context The workspace configuration context
     * @return The resolved ruleset configuration
     */
    fun resolve(context: WorkspaceContext): RulesetConfiguration

    /**
     * The ruleset for [file] in [context]'s workspace. A workspace can mix Jenkins pipeline sources with ordinary
     * Groovy sources, which need different default profiles (#1236). Resolvers without per-file rules use the
     * workspace's ruleset.
     */
    fun resolve(context: WorkspaceContext, file: URI?): RulesetConfiguration = resolve(context)
}

/**
 * Loads resources from a given path.
 * Extracted for testability - allows mocking resource loading in tests.
 */
fun interface ResourceLoader {
    /**
     * Loads content from the specified resource path.
     * @param resourcePath The path to the resource
     * @return The content as a string, or null if not found
     */
    fun load(resourcePath: String): String?
}

/**
 * Default implementation that loads resources from the classpath.
 */
class ClasspathResourceLoader : ResourceLoader {
    override fun load(resourcePath: String): String? =
        this::class.java.classLoader.getResourceAsStream(resourcePath)?.use { stream ->
            stream.reader().readText()
        }
}

/**
 * Default implementation that resolves rulesets hierarchically:
 * 1. Explicit workspace config file
 * 2. Server configuration override
 * 3. Project-type defaults
 * 4. Built-in defaults
 */
@Suppress("TooGenericExceptionCaught") // Ruleset resolution needs robust error handling
class HierarchicalRulesetResolver(
    private val projectTypeDetector: ProjectTypeDetector = DefaultProjectTypeDetector(),
    private val resourceLoader: ResourceLoader = ClasspathResourceLoader(),
    /** True for Jenkins pipeline files (the server passes its configured `jenkins.filePatterns`). */
    private val isJenkinsPipelineFile: (URI) -> Boolean = ::hasDefaultJenkinsFileName,
    private val openWorkspaceRuleset: (Path) -> InputStream = { Files.newInputStream(it) },
) : RulesetResolver {

    /**
     * Clears cached project detection and default rulesets, forcing re-resolution on the next call. Workspace
     * ruleset files are read on every call anyway (their interpretation is cached by content).
     */
    fun reloadRulesets() {
        logger.info { "Reload requested for CodeNarc rulesets" }
        detections.clear()
        defaults.clear()
    }

    /** What a workspace root looks like to ruleset selection, and the directory times it was detected at. */
    private data class Detection(
        val fingerprint: List<Long>,
        val workspaceType: ProjectType,
        val buildType: ProjectType,
        val sharedLibrary: Boolean,
    )

    /**
     * Detection per workspace root, instead of walking the tree on every analysis. Re-detected when the root, its
     * `vars/` directory or a file detection reads changes ([DETECTION_INPUTS]: a build or settings file's
     * contents, a `Jenkinsfile` or `vars` script appearing or disappearing), or on [reloadRulesets].
     */
    private val detections = java.util.concurrent.ConcurrentHashMap<Path, Detection>()

    /** Default ruleset per profile resource; the content of a classpath resource never changes. */
    private val defaults = java.util.concurrent.ConcurrentHashMap<ProjectType, ResolvedRuleset>()

    companion object {
        private val logger = KotlinLogging.logger {}

        private const val MAX_CACHED_INTERPRETATIONS = 32
        private const val VARS_DIR = "vars"
        private const val SRC_DIR = "src"

        /** `src/` children that are Gradle or Maven source sets, not shared library code. */
        private val BUILD_SOURCE_SETS = setOf("main", "test", "integrationTest", "testFixtures")

        /** Language directories that mark `src/<name>` as a source set ([isSourceSet]). */
        private val SOURCE_LANGUAGES = listOf("groovy", "java", "kotlin")

        /** Root files and directories project detection reads; a change to any re-detects ([detect]). */
        private val DETECTION_INPUTS = listOf(
            "vars",
            "Jenkinsfile",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "pom.xml",
            "grails-app",
        )
        private const val LOAD_FACTOR = 0.75f

        /** Interpretation outcomes by content hash; shared so rebuilding a resolver does not re-parse rulesets. */
        private val interpretations = object :
            LinkedHashMap<String, Result>(MAX_CACHED_INTERPRETATIONS, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>) =
                size > MAX_CACHED_INTERPRETATIONS
        }

        // Workspace configuration files in order of precedence
        private val WORKSPACE_CONFIG_FILES = listOf(
            ".codenarc",
            "config/codenarc/rules.groovy",
            "codenarc.groovy",
        )

        // Properties file names in order of precedence
        private val PROPERTIES_FILENAMES = listOf(
            "codenarc.properties",
            ".codenarc/codenarc.properties",
            "config/codenarc/codenarc.properties",
        )
    }

    /** Workspace ruleset files already reported, so the warning is logged once rather than on every analysis. */
    private val warned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun resolve(context: WorkspaceContext): RulesetConfiguration = resolve(context, null)

    /**
     * Explicit workspace rulesets win for every file. Otherwise [file] gets the Jenkins profile when it is a
     * Jenkins pipeline file, a shared library `vars/` script or shared library `src/` code, and the workspace's
     * build-type profile otherwise, even when a root `Jenkinsfile` makes the workspace look like a Jenkins one
     * (#1236). Without a [file], the workspace's profile.
     */
    override fun resolve(context: WorkspaceContext, file: URI?): RulesetConfiguration {
        logger.debug { "Resolving ruleset configuration for $file in context: $context" }

        // Find properties file first
        val propertiesFile = resolvePropertiesFile(context)

        // Workspace rulesets are interpreted (never evaluated); fall back to the project defaults
        val (workspaceRuleset, ignored) = context.root?.let(::interpretWorkspaceRulesets) ?: (null to emptyList())
        val rulesetContent = workspaceRuleset ?: loadProjectTypeDefault(context, file)

        return RulesetConfiguration(
            rulesetContent = rulesetContent.content,
            propertiesFile = propertiesFile,
            source = rulesetContent.source,
            ignoredWorkspaceRulesets = ignored,
        ).also {
            logger.info { "Resolved ruleset: source=${it.source}, properties=${it.propertiesFile}" }
        }
    }

    /**
     * Resolves the properties file to use.
     */
    private fun resolvePropertiesFile(context: WorkspaceContext): String? {
        val config = context.getConfiguration()

        // Check if auto-detection is disabled
        if (!config.autoDetectConfig) {
            return null
        }

        // Check for explicit override in server configuration
        config.propertiesFile?.let { explicitPath ->
            return resolveExplicitPropertiesPath(explicitPath, context.root)
        }

        // Fall back to auto-detection in workspace
        return if (context.root != null) {
            findPropertiesFile(context.root!!)
        } else {
            null
        }
    }

    /**
     * Resolves an explicit properties file path from server configuration.
     */
    private fun resolveExplicitPropertiesPath(explicitPath: String, workspaceRoot: Path?): String? =
        if (explicitPath.startsWith("file:")) {
            // Absolute path with file: prefix
            try {
                val path = Paths.get(URI.create(explicitPath))
                if (path.exists()) {
                    logger.info { "Using explicit CodeNarc properties file: $explicitPath" }
                    path.toString()
                } else {
                    logger.warn { "Explicit CodeNarc properties file not found: $explicitPath" }
                    null
                }
            } catch (e: Exception) {
                logger.warn(e) { "Invalid explicit CodeNarc properties file URI: $explicitPath" }
                null
            }
        } else if (workspaceRoot != null) {
            // Relative path from workspace root
            val path = workspaceRoot.resolve(explicitPath)
            if (path.exists()) {
                logger.info { "Using explicit CodeNarc properties file: $explicitPath" }
                path.toString()
            } else {
                logger.warn { "Explicit CodeNarc properties file not found: $explicitPath" }
                null
            }
        } else {
            logger.warn { "Cannot resolve relative properties path without workspace: $explicitPath" }
            null
        }

    /**
     * Finds a codenarc.properties file in the workspace.
     */
    private fun findPropertiesFile(workspaceRoot: Path): String? {
        for (propertiesFileName in PROPERTIES_FILENAMES) {
            val propertiesFile = workspaceRoot.resolve(propertiesFileName)
            if (propertiesFile.exists()) {
                logger.debug { "Found CodeNarc properties file: $propertiesFileName" }
                return propertiesFile.toString()
            }
        }
        return null
    }

    /**
     * Workspace Groovy-DSL ruleset files are never handed to CodeNarc as Groovy (it would evaluate them, including
     * `evaluate`, `ruleset('file:x.groovy')` and `rule('file:x.groovy')`). [WorkspaceRulesetInterpreter] reads the
     * declarative subset and produces an equivalent XML ruleset instead; a file outside that subset is ignored.
     */
    private fun interpretWorkspaceRulesets(workspaceRoot: Path): Pair<ResolvedRuleset?, List<String>> {
        var accepted: ResolvedRuleset? = null
        val ignored = mutableListOf<String>()
        for (name in WORKSPACE_CONFIG_FILES) {
            val file = workspaceRoot.resolve(name)
            if (!file.isRegularFile()) continue
            val result = interpretCached(file)
            when (result) {
                is WorkspaceRulesetInterpreter.Result.Accepted -> {
                    if (accepted == null) accepted = ResolvedRuleset(result.xml, "workspace:$name")
                }
                is WorkspaceRulesetInterpreter.Result.Rejected -> {
                    ignored.add(name)
                    if (warned.add("$workspaceRoot/$name")) {
                        logger.warn { "Ignoring workspace CodeNarc ruleset $name: ${result.reason}" }
                    }
                }
            }
        }
        return accepted to ignored
    }

    /**
     * Interprets [file], checking size before opening and bounding the read if the file grows, and
     * remembering the outcome by content hash so an unchanged ruleset is not parsed again on every analysis.
     */
    private fun interpretCached(file: Path): Result {
        if (Files.size(file) > WorkspaceRulesetInterpreter.MAX_FILE_BYTES) {
            return Result.Rejected("larger than ${WorkspaceRulesetInterpreter.MAX_FILE_BYTES} bytes")
        }
        val bytes = openWorkspaceRuleset(file).use {
            it.readNBytes(WorkspaceRulesetInterpreter.MAX_FILE_BYTES.toInt() + 1)
        }
        if (bytes.size > WorkspaceRulesetInterpreter.MAX_FILE_BYTES) {
            return Result.Rejected("larger than ${WorkspaceRulesetInterpreter.MAX_FILE_BYTES} bytes")
        }
        val text = bytes.toString(Charsets.UTF_8)
        val key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
        return synchronized(interpretations) {
            interpretations.getOrPut(key) { WorkspaceRulesetInterpreter.interpret(text) }
        }
    }

    /**
     * Loads the appropriate default ruleset based on project type.
     *
     * Fallback chain:
     * 1. Try custom DSL ruleset for project type
     * 2. Try default DSL ruleset
     * 3. Fall back to CodeNarc's bundled XML rulesets (wrapped in minimal DSL)
     */
    private fun loadProjectTypeDefault(context: WorkspaceContext, file: URI?): ResolvedRuleset {
        val root = context.root ?: return defaultFor(ProjectType.PlainGroovy)
        val detection = detect(root)
        val projectType = when {
            file == null -> detection.workspaceType
            isJenkinsSource(root, file, detection) -> ProjectType.JenkinsLibrary
            else -> detection.buildType
        }
        return defaultFor(projectType)
    }

    private fun defaultFor(projectType: ProjectType): ResolvedRuleset =
        defaults.computeIfAbsent(projectType, ::loadDefault)

    private fun detect(root: Path): Detection {
        val fingerprint = (listOf(root) + DETECTION_INPUTS.map(root::resolve)).map { input ->
            runCatching { Files.getLastModifiedTime(input).toMillis() }.getOrDefault(-1L)
        }
        detections[root]?.takeIf { it.fingerprint == fingerprint }?.let { return it }
        return Detection(
            fingerprint = fingerprint,
            workspaceType = projectTypeDetector.detect(root),
            buildType = projectTypeDetector.detectBuildType(root),
            sharedLibrary = projectTypeDetector.isSharedLibrary(root),
        ).also { detections[root] = it }
    }

    /**
     * Pipeline files anywhere, and in a shared library its `vars/` scripts and `src/` code (but not Gradle or
     * Maven source sets such as `src/main` and `src/test`), which Jenkins runs under CPS.
     */
    private fun isJenkinsSource(root: Path, file: URI, detection: Detection): Boolean {
        if (isJenkinsPipelineFile(file)) return true
        if (!detection.sharedLibrary) return false
        val relative = relativeToRoot(root, file) ?: return false
        return when (relative.firstOrNull()) {
            VARS_DIR -> true
            SRC_DIR -> relative.size > 2 && !isSourceSet(root.resolve(SRC_DIR).resolve(relative[1]))
            else -> false
        }
    }

    /**
     * [file]'s path segments under [root], or null outside it. Real paths on both sides, so a symlinked root
     * (`/Volumes/T9/...` reached through another path) still matches; the plain paths when a file is unsaved.
     */
    private fun relativeToRoot(root: Path, file: URI): List<String>? {
        val path = runCatching { Paths.get(file) }.getOrNull() ?: return null
        fun Path.canonical(): Path = runCatching { toRealPath() }.getOrElse { toAbsolutePath().normalize() }
        val canonicalRoot = root.canonical()
        val canonicalFile = path.parent?.canonical()?.resolve(path.fileName) ?: path.canonical()
        if (!canonicalFile.startsWith(canonicalRoot)) return null
        return canonicalRoot.relativize(canonicalFile).map { it.toString() }
    }

    /**
     * `src/<name>` is a Gradle or Maven source set (`src/main`, `src/functionalTest`, `src/jmh`, Maven `src/it`),
     * not shared library package code, when it is a known source set or holds a `groovy/`, `java/` or `kotlin/`
     * language directory.
     */
    private fun isSourceSet(dir: Path): Boolean =
        dir.fileName.toString() in BUILD_SOURCE_SETS || SOURCE_LANGUAGES.any { Files.isDirectory(dir.resolve(it)) }

    private fun loadDefault(projectType: ProjectType): ResolvedRuleset {
        // Try custom DSL rulesets first
        val customResourcePath = when (projectType) {
            is ProjectType.JenkinsLibrary -> "codenarc/rulesets/frameworks/jenkins.groovy"
            is ProjectType.GrailsApplication -> "codenarc/rulesets/frameworks/grails.groovy" // Future
            is ProjectType.SpringBootProject -> "codenarc/rulesets/frameworks/spring-boot.groovy" // Future
            is ProjectType.GradleProject -> {
                if (projectType.hasSpock) {
                    "codenarc/rulesets/frameworks/gradle-spock.groovy" // Future
                } else {
                    "codenarc/rulesets/base/default.groovy"
                }
            }
            else -> "codenarc/rulesets/base/default.groovy"
        }

        // Try custom ruleset
        loadRulesetFromResource(customResourcePath)?.let {
            return ResolvedRuleset(it, "resource:$customResourcePath")
        }

        // Try default ruleset as fallback
        val defaultResourcePath = "codenarc/rulesets/base/default.groovy"
        loadRulesetFromResource(defaultResourcePath)?.let {
            logger.info { "Falling back to default ruleset: $defaultResourcePath" }
            return ResolvedRuleset(it, "resource:$defaultResourcePath")
        }

        // Final fallback: Use CodeNarc's bundled XML rulesets
        // Generate minimal DSL wrapper that references bundled rulesets
        val bundledRulesetPath = when (projectType) {
            is ProjectType.JenkinsLibrary -> "rulesets/jenkins.xml"
            else -> "rulesets/basic.xml"
        }

        logger.warn {
            "Custom rulesets not found in classpath. Falling back to CodeNarc bundled ruleset: $bundledRulesetPath"
        }

        // Generate minimal DSL wrapper for bundled XML ruleset
        val bundledWrapper = """
            ruleset {
                description 'Fallback to CodeNarc bundled ruleset'
                ruleset('$bundledRulesetPath')
            }
        """.trimIndent()

        return ResolvedRuleset(bundledWrapper, "bundled:$bundledRulesetPath")
    }

    /**
     * Loads ruleset content from a classpath resource using the injected ResourceLoader.
     */
    private fun loadRulesetFromResource(resourcePath: String): String? = resourceLoader.load(resourcePath)?.also {
        logger.debug { "Successfully loaded ruleset from $resourcePath (${it.length} characters)" }
    }

    /**
     * Internal data class for resolved ruleset content.
     */
    private data class ResolvedRuleset(val content: String, val source: String)
}

/**
 * The built-in Jenkins pipeline file names (`Jenkinsfile`, `Jenkinsfile.*`, `Jenkinsfile-*`, `Jenkinsfile_*`,
 * `*.jenkinsfile`, `*.Jenkinsfile`), for use without the server's configured `jenkins.filePatterns`.
 */
internal fun hasDefaultJenkinsFileName(file: URI): Boolean {
    val name = runCatching { Paths.get(file).fileName?.toString() }.getOrNull() ?: return false
    return name == JENKINSFILE ||
        listOf('.', '-', '_').any { name.startsWith("$JENKINSFILE$it") } ||
        name.endsWith(".jenkinsfile", ignoreCase = true)
}

private const val JENKINSFILE = "Jenkinsfile"
