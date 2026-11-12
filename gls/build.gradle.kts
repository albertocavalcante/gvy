import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.process.ExecOperations
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

plugins {
    kotlin("jvm")
    groovy
    id("com.gradleup.shadow")
    application
    kotlin("plugin.serialization")
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // ProGuard (non-Android) Gradle integration per Guardsquare docs:
        // https://www.guardsquare.com/manual/setup/gradle
        // There is no Gradle Plugin Portal plugin id for this artifact; it must be added to the buildscript classpath.
        classpath("com.guardsquare:proguard-gradle:${libs.versions.proguardGradle.get()}")
    }
}

val baseVersion: String by rootProject.extra

version =
    when {
        System.getenv("GITHUB_REF_TYPE") == "tag" -> baseVersion
        System.getenv("GITHUB_HEAD_REF")?.contains("release-please") == true -> baseVersion
        else -> "$baseVersion-SNAPSHOT"
    }

val proguardLibrary by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // LSP4J - Language Server Protocol implementation
    implementation(libs.lsp4j)
    implementation(libs.lsp4j.jsonrpc)

    // AST Visualization Model
    implementation(project(":viz:ast-model"))

    // Groovy - For AST parsing and analysis
    implementation(libs.groovy.core)
    // Additional Groovy modules needed by runtime features
    implementation(libs.groovy.groovydoc)

    // Gradle Tooling API - For dependency resolution
    implementation(libs.gradle.tooling.api)

    // Kotlin Coroutines
    implementation(libs.kotlin.coroutines.core)

    // Kotlin Immutable Collections for functional data structures
    implementation(libs.kotlin.collections.immutable)

    // Functional Programming - Arrow-kt for Either type
    implementation(libs.arrow.core)

    // Serialization
    implementation(libs.kotlin.serialization.json)

    // Logging
    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)

    // CLI
    implementation(libs.clikt)
    implementation(libs.mordant)

    // Classpath Scanning - For indexing all classes in the project and dependencies
    implementation(libs.classgraph)

    // Java Source Parsing - For extracting Javadoc and line numbers from Java source files
    implementation(libs.javaparser.core)

    // ProGuard analysis-only library jars (not packaged): used to complete class hierarchies for excluded optional deps.
    add("proguardLibrary", libs.openrewrite.jgit)
    add("proguardLibrary", libs.jna)
    add("proguardLibrary", libs.jna.platform)

    // Testing - Kotlin/Java
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Testing - Groovy (Spock Framework) - JUnit 5 platform native
    testImplementation(libs.spock.core)
    testImplementation(libs.jqwik)

    // Code Quality Tools
    detektPlugins(libs.detekt.formatting)

    // Local Modules
    implementation(project(":common"))
    implementation(project(":source"))
    implementation(project(":fmt"))
    implementation(project(":parser:native"))
    implementation(project(":parser:core"))
    implementation(project(":diagnostics:api"))
    implementation(project(":diagnostics:codenarc"))
    implementation(project(":diagnostics:sarif"))
    implementation(project(":jenkins"))
    implementation(project(":build-tool"))
    implementation(project(":ext:spock"))
    implementation(project(":ext:testing"))
    implementation(project(":ext:junit"))
    implementation(project(":markdown"))
    implementation(project(":indexer:core"))
    implementation(project(":indexer:scip"))
    implementation(project(":indexer:lsif"))
    implementation(project(":parser:rewrite"))
    implementation(project(":semantics:core"))
    implementation(project(":semantics-native"))
    implementation(project(":reports:coverage"))
    implementation(project(":reports:results"))
}

// Avoid the older Groovy jars that Gradle's groovy plugin adds implicitly;
// we pin Groovy 4.0.33 above and want that version to win consistently.
configurations.configureEach {
    exclude(group = "org.codehaus.groovy")
}

tasks.test {
    useJUnitPlatform()
    // execute tests in parallel to speed up the build
    // Smarter choice: 1 fork per 1GB of max heap, but capped by CPU and at least 1.
    // This avoids OOMs on memory-constrained machines like 3GB Mac Minis.
    maxParallelForks =
        run {
            val maxHeapGb = Runtime.getRuntime().maxMemory() / (1024 * 1024 * 1024)
            val processors = Runtime.getRuntime().availableProcessors()
            // Reserve 1GB of daemon heap for overhead, then 1 fork per remaining GB.
            val forksByMemory = (maxHeapGb - 1).coerceAtLeast(1).toInt()
            val forksByCpu = (processors / 2).coerceAtLeast(1)
            minOf(forksByMemory, forksByCpu)
        }

    // Set memory limits to avoid OOMs and ensure consistent environment
    maxHeapSize = "1G"

    // Fail tests that take too long (5 minutes default)
    val junitTimeoutProperty = "junit.jupiter.execution.timeout.default"
    systemProperty(junitTimeoutProperty, "300s")

    // ============================================================================
    // TEST MONITORING & DEADLOCK DETECTION
    // ============================================================================
    // Hard timeout for entire test task (20 minutes) - safety net for hangs
    // Individual tests should timeout first at 5m via JUnit timeout above
    // If this fires, it means we have a serious deadlock or infinite loop
    timeout.set(Duration.ofMinutes(20))

    // JVM arguments for enhanced diagnostics and thread dump generation
    jvmArgs(
        "-XX:+PrintConcurrentLocks", // Show lock info in thread dumps
        "-XX:+UnlockDiagnosticVMOptions", // Enable diagnostic options
        "-XX:+LogVMOutput", // Enable JVM logging
        "-XX:LogFile=build/test-jvm.log", // Log JVM events to file for post-mortem analysis
    )

    // TODO: Tier 2 - Enable JVM Flight Recorder for continuous profiling
    // Uncomment to capture detailed performance data (CPU, memory, locks, I/O)
    // jvmArgs(
    //     "-XX:StartFlightRecording=duration=20m,filename=build/test-recording.jfr",
    //     "-XX:FlightRecorderOptions=stackdepth=128"
    // )
    // Analysis: Use JDK Mission Control or upload to fastThread.io

    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed", "standardOut", "standardError")
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
        showExceptions = true
        showCauses = true
        displayGranularity = 2 // Show individual test methods for hang identification
    }

    // Detect and warn about slow tests (potential deadlock indicators)
    val slowTestThresholdMs = 60_000L
    val verySlowTestThresholdMs = 180_000L

    afterTest(
        KotlinClosure2<TestDescriptor, TestResult, Unit>({
            descriptor,
            result,
            ->
            val duration = result.endTime - result.startTime
            if (duration > verySlowTestThresholdMs) { // Error if test took >3 minutes
                logger.error(
                    "❌ VERY SLOW TEST: ${descriptor.className}.${descriptor.name} took ${duration}ms - possible deadlock?",
                )
            } else if (duration > slowTestThresholdMs) { // Warn if test took >1 minute
                logger.warn("⚠️  SLOW TEST: ${descriptor.className}.${descriptor.name} took ${duration}ms")
            }
        }),
    )

    // Log test environment on start - helps diagnose Runner-specific issues
    doFirst {
        logger.lifecycle("╔════════════════════════════════════════╗")
        logger.lifecycle("║      Test Monitoring Active            ║")
        logger.lifecycle("╠════════════════════════════════════════╣")
        logger.lifecycle("║ Task timeout:        ${timeout.get().toMinutes()} minutes")
        logger.lifecycle("║ JUnit timeout:       ${systemProperties[junitTimeoutProperty]}")
        logger.lifecycle("║ Max parallel forks:  $maxParallelForks")
        logger.lifecycle("║ CPU cores:           ${Runtime.getRuntime().availableProcessors()}")
        logger.lifecycle("║ Max heap:            $maxHeapSize")
        logger.lifecycle("║ JVM log:             build/test-jvm.log")
        logger.lifecycle("╚════════════════════════════════════════╝")
    }

    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }
}

// TODO: Tier 3 - Create custom Gradle task to analyze JVM logs for deadlocks
// This would parse build/test-jvm.log and detect patterns like:
// - "deadlock" keyword
// - High number of BLOCKED threads
// - Circular lock dependencies
// Example:
// tasks.register("analyzeTestHangs") {
//     group = "verification"
//     description = "Analyze JVM logs and test reports for hangs/deadlocks"
//     doLast {
//         val jvmLog = file("build/test-jvm.log")
//         if (jvmLog.exists()) {
//             val content = jvmLog.readText()
//             if (content.contains("deadlock") || content.contains("BLOCKED")) {
//                 logger.error("⚠️ POTENTIAL DEADLOCK - Check build/test-jvm.log")
//             }
//         }
//     }
// }

// TODO: Tier 3 - Add custom Detekt rule to catch unsafe blocking patterns
// This would detect code like: async(Dispatchers.Default) { future.get() }
// and suggest using Dispatchers.IO or .await() instead
// See: buildSrc/src/main/kotlin/detekt/UnsafeBlockingInCoroutineRule.kt

val mainSourceSet = sourceSets.named("main")

// Configure Kotlin-Groovy interop: Groovy compiles first, then Kotlin
tasks.named<org.gradle.api.tasks.compile.GroovyCompile>("compileGroovy") {
    // Groovy compiles with declared dependencies only
    classpath = sourceSets.main.get().compileClasspath
    groovyOptions.encoding = "UTF-8"
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    // Kotlin compilation depends on Groovy output and includes it on classpath
    dependsOn(tasks.compileGroovy)
    libraries.from(tasks.compileGroovy.get().destinationDirectory)
}

// Ensure proper compilation order for tests too
tasks.named<org.gradle.api.tasks.compile.GroovyCompile>("compileTestGroovy") {
    classpath = sourceSets.test.get().compileClasspath
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    dependsOn(tasks.compileTestGroovy)
    libraries.from(tasks.compileTestGroovy.get().destinationDirectory)
}

// Fix Kover task dependencies for Gradle 9
afterEvaluate {
    tasks.findByName("koverGenerateArtifactJvm")?.dependsOn(tasks.compileGroovy)
    // Use lifecycle logging for build information
    logger.lifecycle("Configured version for gls: ${project.version}")
}

application {
    mainClass = "com.github.albertocavalcante.gvy.gls.MainKt"
}

abstract class LogbackArtifactMetadataTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val classicJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val coreJar: RegularFileProperty

    @get:Input
    abstract val artifactVersions: MapProperty<String, String>

    protected fun checkedVersions(): Map<String, String> {
        val versions = artifactVersions.get()
        val modules = setOf("logback-classic", "logback-core")
        if (versions.keys != modules || versions.values.any { it.isBlank() || it == "?" }) {
            throw GradleException("Expected resolved module versions for logback-classic and logback-core: $versions")
        }
        if (versions.getValue("logback-classic") != versions.getValue("logback-core")) {
            throw GradleException("Resolved Logback classic/core versions differ: $versions")
        }
        return versions
    }

    protected fun packageVersions(): Map<String, String> {
        val versions = checkedVersions()
        val packages = sortedMapOf<String, String>()
        for ((module, jar) in listOf("logback-classic" to classicJar, "logback-core" to coreJar)) {
            val prefix = "ch/qos/logback/" + module.removePrefix("logback-") + "/"
            val artifactPackages =
                JarFile(jar.get().asFile).use { source ->
                    source
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .map { it.name.replaceFirst(Regex("^META-INF/versions/[0-9]+/"), "") }
                        .filter { it.startsWith(prefix) }
                        .map { it.substringBeforeLast('/') + "/" }
                        .toSortedSet()
                }
            if (artifactPackages.isEmpty()) {
                throw GradleException("Resolved $module artifact contains no classes under $prefix")
            }
            for (name in artifactPackages) packages[name] = versions.getValue(module)
        }
        return packages
    }
}

@CacheableTask
abstract class GenerateLogbackPackageManifestTask : LogbackArtifactMetadataTask() {
    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        for ((name, version) in packageVersions()) {
            manifest.entries[name] =
                Attributes().apply {
                    put(Attributes.Name.IMPLEMENTATION_VERSION, version)
                }
        }
        val output = manifestFile.get().asFile
        output.parentFile.mkdirs()
        output.outputStream().use(manifest::write)
    }
}

@CacheableTask
abstract class VerifyLogbackPackageMetadataTask : LogbackArtifactMetadataTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jarFile: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val versions = checkedVersions()
        val packages = packageVersions()
        val jar = jarFile.get().asFile
        JarFile(jar).use { shaded ->
            val manifest = shaded.manifest ?: throw GradleException("Shaded JAR has no manifest")
            for ((name, expected) in packages) {
                val actual = manifest.entries[name]?.getValue(Attributes.Name.IMPLEMENTATION_VERSION)
                if (actual != expected) {
                    throw GradleException(
                        "Shaded Logback package $name requires resolved version $expected; manifest contains $actual",
                    )
                }
            }
        }

        // Isolate the shaded archive from Gradle's own logging libraries.
        URLClassLoader(arrayOf(jar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val versionUtil = loader.loadClass("ch.qos.logback.core.util.VersionUtil")
            val lookup = versionUtil.getMethod("getVersionOfArtifact", Class::class.java)
            for ((module, anchor) in listOf(
                "logback-classic" to "ch.qos.logback.classic.util.ContextInitializer",
                "logback-core" to "ch.qos.logback.core.util.VersionUtil",
            )) {
                val type = loader.loadClass(anchor)
                val expected = versions.getValue(module)
                if (type.getPackage().implementationVersion != expected || lookup.invoke(null, type) != expected) {
                    throw GradleException("Shaded $module runtime version lookup does not match resolved $expected")
                }
            }
        }
        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                versions.toSortedMap().entries.joinToString("\n", postfix = "\n") { (module, version) ->
                    "$module=$version"
                } + "packageSections=" + packages.size + "\nruntimeVersionLookup=PASS\n",
            )
        }
    }
}

val resolvedLogbackArtifacts =
    configurations.named("runtimeClasspath").flatMap { runtime ->
        runtime.incoming
            .artifactView {
                componentFilter { component ->
                    component is ModuleComponentIdentifier &&
                        component.group == "ch.qos.logback" &&
                        component.module in setOf("logback-classic", "logback-core")
                }
            }.artifacts.resolvedArtifacts
    }
val resolvedLogbackVersions =
    resolvedLogbackArtifacts.map { artifacts ->
        artifacts.associate { artifact ->
            val component = artifact.id.componentIdentifier as ModuleComponentIdentifier
            component.module to component.version
        }
    }
val resolvedLogbackClassicJar =
    layout.file(
        resolvedLogbackArtifacts.map { artifacts ->
            artifacts
                .single { (it.id.componentIdentifier as ModuleComponentIdentifier).module == "logback-classic" }
                .file
        },
    )
val resolvedLogbackCoreJar =
    layout.file(
        resolvedLogbackArtifacts.map { artifacts ->
            artifacts.single { (it.id.componentIdentifier as ModuleComponentIdentifier).module == "logback-core" }.file
        },
    )

val generateLogbackPackageManifest =
    tasks.register<GenerateLogbackPackageManifestTask>("generateLogbackPackageManifest") {
        description = "Preserve actual resolved Logback versions as named package manifest sections."
        classicJar.set(resolvedLogbackClassicJar)
        coreJar.set(resolvedLogbackCoreJar)
        artifactVersions.set(resolvedLogbackVersions)
        manifestFile.set(layout.buildDirectory.file("generated/logback/package-manifest.mf"))
    }

val verifyLogbackPackageMetadata =
    tasks.register<VerifyLogbackPackageMetadataTask>("verifyLogbackPackageMetadata") {
        group = "verification"
        description = "Verify shaded Logback package versions against the actual resolved artifacts."
        dependsOn(tasks.shadowJar)
        classicJar.set(resolvedLogbackClassicJar)
        coreJar.set(resolvedLogbackCoreJar)
        artifactVersions.set(resolvedLogbackVersions)
        jarFile.set(tasks.shadowJar.flatMap { it.archiveFile })
        reportFile.set(layout.buildDirectory.file("reports/jar/logback-package-metadata.txt"))
    }

// Shadow 9.3.1 delegates its manifest to the plain JAR, so both are consumers of this generated file.
val logbackPackageManifest = generateLogbackPackageManifest.flatMap { it.manifestFile }
listOf(tasks.jar, tasks.shadowJar).forEach { archive ->
    archive.configure {
        dependsOn(generateLogbackPackageManifest)
        inputs
            .file(logbackPackageManifest)
            .withPropertyName("logbackPackageManifest")
            .withPathSensitivity(PathSensitivity.NONE)
    }
}

// Keep dependency notices separate: Shadow otherwise drops repeated META-INF/LICENSE paths.
object DependencyNoticeStaging {
    fun stage(
        archives: Collection<File>,
        output: File,
    ) {
        check(!output.exists() || output.deleteRecursively()) { "Cannot clear notice directory: $output" }
        check(output.mkdirs() || output.isDirectory) { "Cannot create notice directory: $output" }
        val index = sortedSetOf<String>()
        for (archive in archives.sortedBy { it.name }) {
            require(archive.isFile && archive.name.endsWith(".jar", ignoreCase = true)) {
                "Unsupported runtime artifact for notice preservation: $archive"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            archive.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            // The content digest distinguishes different artifacts even when their filenames collide.
            val artifact = archive.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            index.add("$artifact/$hash\t${archive.name}\tSHA-256:$hash")
            val seen = mutableSetOf<String>()
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val parts = entry.name.split('/')
                    val base = parts.last().uppercase(Locale.ROOT)
                    val notice =
                        !base.endsWith(".CLASS") &&
                            (listOf("LICENSE", "LICENCE", "NOTICE", "COPYING", "COPYRIGHT").any {
                                base == it || base.startsWith("$it.") || base.startsWith("$it-")
                            } || parts.dropLast(1).any {
                                it.equals("licenses", ignoreCase = true) || it.equals("license", ignoreCase = true)
                            })
                    if (!notice) continue
                    require(
                        parts.none { it.isEmpty() || it == "." || it == ".." || '\\' in it || ':' in it },
                    ) { "Unsafe notice path in ${archive.name}: ${entry.name}" }
                    check(seen.add(entry.name.lowercase(Locale.ROOT))) {
                        "Duplicate notice path in ${archive.name}: ${entry.name}"
                    }
                    val destination = output.resolve("$artifact/$hash/${entry.name}")
                    check(destination.parentFile.mkdirs() || destination.parentFile.isDirectory)
                    zip.getInputStream(entry).use { input -> destination.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        output.resolve("INDEX.txt").writeText(index.joinToString("\n", postfix = "\n"))
    }
}

abstract class StageDependencyNotices : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val archives: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        DependencyNoticeStaging.stage(archives.files, outputDirectory.get().asFile)
    }
}

val stageDependencyNotices = tasks.register<StageDependencyNotices>("stageDependencyNotices") {
    archives.from(configurations.named("runtimeClasspath"))
    outputDirectory.set(layout.buildDirectory.dir("generated/dependency-notices"))
}

abstract class VerifyDependencyNotices : DefaultTask() {
    @TaskAction
    fun verify() {
        val root = temporaryDir.resolve("fixtures")
        check(!root.exists() || root.deleteRecursively())
        check(root.mkdirs())
        fun jar(
            directory: String,
            entries: List<Pair<String, String>>,
        ): File {
            val file = root.resolve("$directory/dependency.jar")
            check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((name, contents) in entries) {
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    zip.write(contents.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            return file
        }
        fun files(directory: File): Map<String, List<Byte>> =
            directory.walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(directory).invariantSeparatorsPath to it.readBytes().toList()
            }.toSortedMap()
        fun expectFailure(
            archive: File,
            message: String,
        ) {
            val failure =
                runCatching { DependencyNoticeStaging.stage(listOf(archive), root.resolve("rejected")) }.exceptionOrNull()
            check(failure != null && failure.message.orEmpty().contains(message, ignoreCase = true)) {
                "Expected $message failure for ${archive.name}, got $failure"
            }
        }

        val first =
            jar(
                "first",
                listOf("META-INF/LICENSE" to "first", "org/example/NOTICE" to "nested", "LICENSE.class" to "bytecode"),
            )
        val second = jar("second", listOf("META-INF/LICENSE" to "second", "about_files/LICENSE-2.0.txt" to "about"))
        val output = root.resolve("output")
        DependencyNoticeStaging.stage(listOf(first, second), output)
        val snapshot = files(output)
        val licenses =
            snapshot.filterKeys { it.endsWith("/META-INF/LICENSE") }.values
                .map { it.toByteArray().toString(Charsets.UTF_8) }
                .toSet()
        check(licenses == setOf("first", "second")) { "Matching names must preserve both archives" }
        check(snapshot.keys.any { it.endsWith("/org/example/NOTICE") })
        check(snapshot.keys.any { it.endsWith("/about_files/LICENSE-2.0.txt") })
        check(snapshot.keys.none { it.endsWith("LICENSE.class") })
        val index = output.resolve("INDEX.txt").readLines()
        check(index.size == 2 && index.toSet().size == 2)
        check(index.all { line ->
            val fields = line.split('\t')
            fields.size == 3 && fields[1] == "dependency.jar" &&
                fields[2].startsWith("SHA-256:") && output.resolve(fields[0]).isDirectory
        }) { "The index must identify both namespaced archives" }
        output.resolve("stale").writeText("stale")
        DependencyNoticeStaging.stage(listOf(second, first), output)
        check(snapshot == files(output)) { "Staging must be deterministic and remove stale files" }

        expectFailure(jar("traversal", listOf("../LICENSE" to "unsafe")), "unsafe notice path")
        expectFailure(
            jar("duplicate", listOf("META-INF/LICENSE" to "one", "META-INF/license" to "two")),
            "duplicate notice path",
        )
        val corrupt = root.resolve("corrupt.jar").apply { writeText("not a zip") }
        val failure =
            runCatching { DependencyNoticeStaging.stage(listOf(corrupt), root.resolve("rejected")) }.exceptionOrNull()
        check(failure is java.util.zip.ZipException) { "Corrupt archives must fail: $failure" }
    }
}

abstract class VerifyShadowDependencyNotices : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val archives: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val shadowJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val projectLicense: RegularFileProperty

    @TaskAction
    fun verify() {
        val expected = temporaryDir.resolve("expected-notices")
        DependencyNoticeStaging.stage(archives.files, expected)
        val expectedFiles = expected.walkTopDown().filter { it.isFile }.associate {
            "META-INF/third-party/" + it.relativeTo(expected).invariantSeparatorsPath to it
        }
        ZipFile(shadowJar.get().asFile).use { zip ->
            val entries = zip.entries().toList().filterNot { it.isDirectory }
            val notices = entries.filter { it.name.startsWith("META-INF/third-party/") }
            check(notices.map { it.name }.toSet().size == notices.size) { "Duplicate shaded notice entries" }
            check(notices.map { it.name }.toSet() == expectedFiles.keys) { "Shaded notice paths differ from runtime inputs" }
            for (entry in notices) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                check(bytes.contentEquals(expectedFiles.getValue(entry.name).readBytes())) {
                    "Shaded notice differs: ${entry.name}"
                }
            }
            val projectEntry = zip.getEntry("META-INF/gvy/LICENSE") ?: error("Project license missing from shaded JAR")
            check(zip.getInputStream(projectEntry).use { it.readBytes() }.contentEquals(projectLicense.get().asFile.readBytes()))
        }
    }
}

val verifyDependencyNotices = tasks.register<VerifyDependencyNotices>("verifyDependencyNotices")
val verifyShadowDependencyNotices = tasks.register<VerifyShadowDependencyNotices>("verifyShadowDependencyNotices") {
    archives.from(configurations.named("runtimeClasspath"))
    shadowJar.set(tasks.shadowJar.flatMap { it.archiveFile })
    projectLicense.set(rootProject.layout.projectDirectory.file("LICENSE"))
}
tasks.named("check") {
    dependsOn(verifyDependencyNotices, verifyShadowDependencyNotices)
}

tasks.shadowJar {
    archiveBaseName = "gls"
    archiveClassifier = "all"

    from(rootProject.file("LICENSE")) { into("META-INF/gvy") }
    from(stageDependencyNotices) { into("META-INF/third-party") }

    // Standard shadow JAR configuration - includes all runtimeClasspath by default
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "com.github.albertocavalcante.gvy.gls.MainKt"
        from(logbackPackageManifest)
    }
    finalizedBy(verifyLogbackPackageMetadata)

    // Merge service files for proper SLF4J and other service provider bindings
    mergeServiceFiles()

    val minimizeEnabled =
        providers
            .gradleProperty("shadowMinimize")
            .map { it.toBooleanStrict() }
            .orElse(true)

    if (minimizeEnabled.get()) {
        // Reduce jar size while keeping reflection-heavy libraries intact.
        minimize {
            exclude(dependency("org.apache.groovy:.*"))
            exclude(dependency("org.codehaus.groovy:.*"))
            exclude(dependency("org.codenarc:.*"))
            exclude(dependency("org.gmetrics:.*"))
            exclude(dependency("org.gradle:gradle-tooling-api"))
            exclude(dependency("org.eclipse.lsp4j:.*"))
            exclude(dependency("io.github.classgraph:classgraph"))
            exclude(dependency("ch.qos.logback:.*"))
            // CLI libraries need to be preserved (uses service providers)
            exclude(dependency("com.github.ajalt.clikt:.*"))
            exclude(dependency("com.github.ajalt.mordant:.*"))
            exclude(dependency("net.java.dev.jna:.*"))
            // Ktor and kotlinx-serialization use ServiceLoader for runtime providers
            exclude(dependency("io.ktor:.*"))
            exclude(dependency("org.jetbrains.kotlinx:kotlinx-serialization-.*"))
        }
    }
}

// Fix task dependencies for Gradle 9
tasks.distZip {
    dependsOn(tasks.shadowJar)
}

tasks.distTar {
    dependsOn(tasks.shadowJar)
}

tasks.startScripts {
    dependsOn(tasks.shadowJar)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Fix shadow plugin task dependencies
tasks.named("startShadowScripts") {
    dependsOn(tasks.shadowJar, tasks.jar)
}

// Build provenance: which commit (and working-tree state) produced this jar.
// Read through a ValueSource: Gradle evaluates it lazily when the task graph needs it (never at configuration
// time for tasks that don't), and the result is a task input, so the task re-runs only when HEAD or the dirty
// state changes. Same commit + same branch + clean tree gives a byte-identical version.properties; gitBranch is
// deliberately included for humans, so the same commit built on another branch or worktree differs.
abstract class GitProvenanceSource : ValueSource<Map<String, String>, GitProvenanceSource.Params> {
    interface Params : ValueSourceParameters {
        val rootDir: DirectoryProperty
        val baseVersion: Property<String>
        val release: Property<Boolean>
        val fallbackBranch: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): Map<String, String> {
        val base = parameters.baseVersion.get()
        val release = parameters.release.get()
        // Kept in the legacy shape: jar file names and `Version.isSnapshot` consumers depend on it.
        val legacyVersion = if (release) base else "$base-SNAPSHOT"
        val sha = trustedHead()
        if (sha == null) {
            return linkedMapOf(
                "version" to legacyVersion,
                "baseVersion" to base,
                "displayVersion" to if (release) base else "$base-dev+unknown",
                "release" to release.toString(),
                "gitSha" to UNKNOWN,
                "gitShaShort" to UNKNOWN,
                "gitDirty" to UNKNOWN,
                "gitDirtyHash" to "",
                "gitBranch" to UNKNOWN,
                "commitTime" to UNKNOWN,
                "buildTime" to "",
            )
        }
        val short = git("rev-parse", "--short=7", "HEAD")?.trim().orEmpty().ifEmpty { sha.take(SHORT_SHA_LENGTH) }
        val untracked =
            git("ls-files", "--others", "--exclude-standard", "-z")
                ?.split('\u0000')
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        // Fixed options so user config (diff.noprefix, external diff, abbrev, renames) cannot change the digest.
        val diff =
            gitBytes(
                "-c",
                "core.abbrev=40",
                "-c",
                "diff.noprefix=false",
                "-c",
                "diff.mnemonicPrefix=false",
                "diff",
                "HEAD",
                "--full-index",
                "--binary",
                "--no-renames",
                "--no-ext-diff",
                "--no-textconv",
                "--no-color",
                "--src-prefix=a/",
                "--dst-prefix=b/",
            ) ?: ByteArray(0)
        val dirty = diff.isNotEmpty() || untracked.isNotEmpty() || !git("status", "--porcelain")?.trim().isNullOrEmpty()
        val commitTime = git("log", "-1", "--format=%cI", "HEAD")?.trim().orEmpty().ifEmpty { UNKNOWN }
        val branch =
            git("rev-parse", "--abbrev-ref", "HEAD")?.trim().orEmpty().let {
                if (it.isEmpty() || it == "HEAD") parameters.fallbackBranch.getOrElse("detached") else it
            }
        val display =
            when {
                release -> base
                dirty -> "$base-dev+g$short.dirty"
                else -> "$base-dev+g$short"
            }
        return linkedMapOf(
            "version" to legacyVersion,
            "baseVersion" to base,
            "displayVersion" to display,
            "release" to release.toString(),
            "gitSha" to sha,
            "gitShaShort" to short,
            "gitDirty" to dirty.toString(),
            "gitDirtyHash" to if (dirty) dirtyHash(diff, untracked) else "",
            "gitBranch" to branch,
            "commitTime" to commitTime,
            // Clean builds stay reproducible (buildTime == commitTime); the task stamps wall-clock when dirty.
            "buildTime" to if (dirty) "" else commitTime,
        )
    }

    /** HEAD of the repository rooted exactly at rootDir, or null (with a warning) when git can't vouch for it. */
    private fun trustedHead(): String? {
        val root =
            parameters.rootDir
                .get()
                .asFile.canonicalFile
        val top =
            git(
                "rev-parse",
                "--show-toplevel",
            )?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it).canonicalFile }
        val sha = git("rev-parse", "HEAD")?.trim().orEmpty()
        val reason =
            when {
                top == null || sha.isEmpty() ->
                    "git is unavailable, this is not a git checkout, or git refused the repository (e.g. dubious ownership)"
                top != root -> "the sources are inside another repository ($top), not their own"
                else -> return sha
            }
        Logging
            .getLogger(
                GitProvenanceSource::class.java,
            ).warn("Git provenance unavailable: $reason; stamping 'unknown'.")
        return null
    }

    private fun dirtyHash(
        diff: ByteArray,
        untracked: List<String>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(diff)
        val root = parameters.rootDir.get().asFile
        // Hash untracked files by content through git (streamed, any size). The batch form is all-or-nothing and
        // cannot carry names with newlines or a leading quote (git unquotes them), so those names are excluded
        // from it; if the batch call still fails (e.g. an unreadable file), fall back to one call per file.
        val sorted = untracked.sorted()
        val hashable = sorted.filter { '\n' !in it && !it.startsWith("\"") && File(root, it).isFile }
        val batch =
            if (hashable.isEmpty()) {
                emptyList()
            } else {
                gitBytes(
                    "hash-object",
                    "--no-filters",
                    "--stdin-paths",
                    stdin = hashable.joinToString("\n", postfix = "\n").toByteArray(),
                )?.toString(Charsets.UTF_8)
                    ?.lines()
                    ?.filter { it.isNotEmpty() }
            }
        val ids =
            if (batch != null && batch.size == hashable.size && hashable.size == sorted.size) {
                hashable.zip(batch).toMap()
            } else {
                // Includes names the batch form can't carry; unreadable files simply contribute their name.
                sorted
                    .filter { File(root, it).isFile }
                    .mapNotNull { name ->
                        git(
                            "hash-object",
                            "--no-filters",
                            "--",
                            name,
                        )?.trim()?.takeIf { it.isNotEmpty() }?.let { name to it }
                    }.toMap()
            }
        untracked.sorted().forEach { name ->
            digest.update(name.toByteArray())
            digest.update(0)
            digest.update((ids[name] ?: "-").toByteArray())
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(DIRTY_HASH_LENGTH)
    }

    private fun git(vararg args: String): String? = gitBytes(*args)?.toString(Charsets.UTF_8)

    private fun gitBytes(
        vararg args: String,
        stdin: ByteArray? = null,
    ): ByteArray? =
        try {
            val out = ByteArrayOutputStream()
            val result =
                execOperations.exec {
                    commandLine("git", *args)
                    workingDir = parameters.rootDir.get().asFile
                    standardOutput = out
                    errorOutput = ByteArrayOutputStream()
                    if (stdin != null) standardInput = ByteArrayInputStream(stdin)
                    isIgnoreExitValue = true
                }
            if (result.exitValue == 0) out.toByteArray() else null
        } catch (_: Exception) {
            null // git missing (source tarball, minimal container): degrade to "unknown"
        }

    private companion object {
        const val UNKNOWN = "unknown"
        const val SHORT_SHA_LENGTH = 7
        const val DIRTY_HASH_LENGTH = 12
    }
}

// Generate version properties file for runtime access
tasks.register("generateVersionProperties") {
    description = "Generate version.properties (version + git provenance) for runtime access"
    group = "build"

    val outputDir = layout.buildDirectory.dir("generated/resources")
    val propertiesFile = outputDir.map { it.file("version.properties") }

    val isRelease =
        providers
            .environmentVariable("GITHUB_REF_TYPE")
            .map { it == "tag" }
            .orElse(false)
            .zip(
                providers.environmentVariable("GITHUB_HEAD_REF").map { it.contains("release-please") }.orElse(false),
            ) { a, b ->
                a ||
                    b
            }
    val provenance =
        providers.of(GitProvenanceSource::class) {
            parameters.rootDir.set(rootProject.layout.projectDirectory)
            parameters.baseVersion.set(baseVersion)
            parameters.release.set(isRelease)
            parameters.fallbackBranch.set(
                providers
                    .environmentVariable("GITHUB_HEAD_REF")
                    .orElse(providers.environmentVariable("GITHUB_REF_NAME"))
                    .orElse("detached"),
            )
        }
    val ciRunUrl =
        providers
            .environmentVariable("GITHUB_SERVER_URL")
            .zip(
                providers.environmentVariable("GITHUB_REPOSITORY"),
            ) { server, repo -> "$server/$repo" }
            .zip(providers.environmentVariable("GITHUB_RUN_ID")) { repo, run ->
                "$repo/actions/runs/$run"
            }

    inputs.property("provenance", provenance)
    inputs.property("ciRunUrl", ciRunUrl.orElse(""))
    outputs.file(propertiesFile)
    // Dirty builds embed wall-clock time, so only clean (reproducible) results may be cached.
    outputs.cacheIf { provenance.get()["gitDirty"] != "true" }

    doLast {
        val props = LinkedHashMap(provenance.get())
        if (props["buildTime"].isNullOrEmpty()) props["buildTime"] = Instant.now().toString()
        ciRunUrl.orNull?.let { props["ciRunUrl"] = it }

        fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
        val outFile = propertiesFile.get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(props.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k=${escape(v)}" })
        logger.lifecycle("Generated version.properties: {}", props.entries.joinToString(", ") { (k, v) -> "$k=$v" })
    }
}

// version.properties changes on every commit and edit, but tests assert against the file they load (and its
// `release` flag), so a cached result can't be wrong; ignoring it keeps unrelated edits from rerunning all tests.
normalization {
    runtimeClasspath {
        ignore("version.properties")
    }
}

// Make processResources depend on version properties generation
tasks.processResources {
    dependsOn("generateVersionProperties")
    from(layout.buildDirectory.dir("generated/resources"))
}

abstract class ReportJarCompositionTask : DefaultTask() {
    @get:InputFile
    abstract val jarFile: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val jar = jarFile.get().asFile
        ZipFile(jar).use { zip ->
            val entries = zip.entries().toList().filterNot { it.isDirectory }

            fun prefix(
                name: String,
                segments: Int,
            ): String {
                val parts = name.split('/').filter { it.isNotBlank() }
                if (parts.isEmpty()) return name
                return parts.take(segments).joinToString("/")
            }

            val byPrefix1Compressed = mutableMapOf<String, Long>()
            val byPrefix3Compressed = mutableMapOf<String, Long>()
            val byPrefix1Uncompressed = mutableMapOf<String, Long>()
            val byPrefix3Uncompressed = mutableMapOf<String, Long>()

            val largestByUncompressed =
                entries
                    .map { entry -> entry.size.coerceAtLeast(0) to entry.name }
                    .sortedByDescending { it.first }
                    .take(60)

            val largestByCompressed =
                entries
                    .map { entry -> entry.compressedSize.coerceAtLeast(0) to entry.name }
                    .sortedByDescending { it.first }
                    .take(60)

            var totalUncompressed = 0L
            var totalCompressed = 0L

            for (entry in entries) {
                val uncompressed = entry.size.coerceAtLeast(0)
                val compressed = entry.compressedSize.coerceAtLeast(0)
                totalUncompressed += uncompressed
                totalCompressed += compressed

                val p1 = prefix(entry.name, 1)
                val p3 = prefix(entry.name, 3)
                byPrefix1Uncompressed[p1] = (byPrefix1Uncompressed[p1] ?: 0L) + uncompressed
                byPrefix3Uncompressed[p3] = (byPrefix3Uncompressed[p3] ?: 0L) + uncompressed
                byPrefix1Compressed[p1] = (byPrefix1Compressed[p1] ?: 0L) + compressed
                byPrefix3Compressed[p3] = (byPrefix3Compressed[p3] ?: 0L) + compressed
            }

            fun formatBytes(bytes: Long): String = "%,d".format(bytes)

            fun top(
                map: Map<String, Long>,
                limit: Int,
            ): List<Pair<String, Long>> =
                map.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                    .take(limit)
                    .map { it.key to it.value }

            val out = reportFile.get().asFile
            out.parentFile.mkdirs()
            out.writeText(
                buildString {
                    appendLine("Jar: ${jar.name}")
                    appendLine("JarSizeBytes: ${formatBytes(jar.length())}")
                    appendLine("Entries: ${entries.size}")
                    appendLine("TotalUncompressedBytes: ${formatBytes(totalUncompressed)}")
                    appendLine("TotalCompressedBytes: ${formatBytes(totalCompressed)}")
                    appendLine()

                    appendLine("TopPrefix1CompressedBytes:")
                    for ((p, b) in top(byPrefix1Compressed, 40)) appendLine("${formatBytes(b)}\t$p")
                    appendLine()

                    appendLine("TopPrefix3CompressedBytes:")
                    for ((p, b) in top(byPrefix3Compressed, 60)) appendLine("${formatBytes(b)}\t$p")
                    appendLine()

                    appendLine("TopPrefix1UncompressedBytes:")
                    for ((p, b) in top(byPrefix1Uncompressed, 40)) appendLine("${formatBytes(b)}\t$p")
                    appendLine()

                    appendLine("TopPrefix3UncompressedBytes:")
                    for ((p, b) in top(byPrefix3Uncompressed, 60)) appendLine("${formatBytes(b)}\t$p")
                    appendLine()

                    appendLine("LargestEntriesUncompressedBytes:")
                    for ((b, name) in largestByUncompressed) appendLine("${formatBytes(b)}\t$name")
                    appendLine()

                    appendLine("LargestEntriesCompressedBytes:")
                    for ((b, name) in largestByCompressed) appendLine("${formatBytes(b)}\t$name")
                },
            )
        }
    }
}

abstract class ReportRuntimeClasspathArtifactsTask : DefaultTask() {
    @get:InputFiles
    abstract val runtimeClasspathFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val out = reportFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("file,sizeBytes")
                for (file in runtimeClasspathFiles.files.sortedBy { it.name }) {
                    appendLine("${file.name},${file.length()}")
                }
            },
        )
    }
}

// Helper task to print classpath for debugging
tasks.register("printClasspath") {
    doLast {
        println(configurations.compileClasspath.get().asPath)
    }
}

// Debug task to print version information
tasks.register("printVersion") {
    val currentBaseVersion = baseVersion
    val currentVersion = version.toString()
    val refType = System.getenv("GITHUB_REF_TYPE")
    val headRef = System.getenv("GITHUB_HEAD_REF")

    doLast {
        println("Base version: $currentBaseVersion")
        println("Final version: $currentVersion")
        println("GITHUB_REF_TYPE: ${refType ?: "not set"}")
        println("GITHUB_HEAD_REF: ${headRef ?: "not set"}")
        println("Is release build: ${currentVersion == currentBaseVersion}")
    }
}

val shadowJarTask = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")

tasks.register<Copy>("stageEditorServer") {
    group = "distribution"
    description = "Stage the current server at a stable path for editor integration tests and packaging."
    dependsOn(shadowJarTask)
    from(shadowJarTask.flatMap { it.archiveFile })
    into(layout.buildDirectory.dir("editor-server"))
    rename { "gls.jar" }
}

tasks.register<ReportJarCompositionTask>("reportShadowJarComposition") {
    group = "reporting"
    description = "Report size breakdown of the shaded gls uber jar."
    dependsOn(shadowJarTask)
    jarFile.set(shadowJarTask.flatMap { it.archiveFile })
    reportFile.set(layout.buildDirectory.file("reports/jar/gls-shadow-composition.txt"))
}

tasks.register<ReportRuntimeClasspathArtifactsTask>("reportRuntimeClasspathArtifacts") {
    group = "reporting"
    description = "Report resolved runtimeClasspath artifacts with file sizes."
    runtimeClasspathFiles.from(configurations.named("runtimeClasspath"))
    reportFile.set(layout.buildDirectory.file("reports/dependencies/runtimeClasspath-artifacts.csv"))
}

tasks.register("jarReports") {
    group = "reporting"
    description = "Generate jar and dependency size reports."
    dependsOn("reportShadowJarComposition", "reportRuntimeClasspathArtifacts")
}

abstract class SmokeShadowJarTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
    ) : DefaultTask() {
        @get:InputFile
        abstract val jarFile: RegularFileProperty

        @get:OutputDirectory
        abstract val smokeDir: DirectoryProperty

        @TaskAction
        fun run() {
            val jar = jarFile.get().asFile
            val dir = smokeDir.get().asFile
            dir.mkdirs()

            val smokeFile = dir.resolve("Smoke.groovy")
            smokeFile.writeText(
                """
                // Minimal Groovy file used for jar smoke checks
                // Missing blank lines produce informational diagnostics without invalid syntax.
                class Smoke {
                  static void main(String[] args) {
                    println "ok"
                  }
                }
                """.trimIndent(),
            )

            fun runJar(vararg args: String): String {
                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                val result =
                    execOperations.exec {
                        isIgnoreExitValue = true
                        commandLine(listOf("java", "-Xmx256m", "-jar", jar.absolutePath) + args)
                        standardOutput = stdout
                        errorOutput = stderr
                        // Disable colored output for reliable assertion checking
                        // Compliant with https://no-color.org/
                        environment("NO_COLOR", "1")
                    }
                if (result.exitValue != 0) {
                    throw GradleException("Smoke command ${args.first()} exited ${result.exitValue}:\n$stdout\n$stderr")
                }
                val stderrStr = stderr.toString(Charsets.UTF_8)
                val combinedOutput = stdout.toString(Charsets.UTF_8) + stderrStr
                if (combinedOutput.contains("Versions of logback-core and logback-classic are different!")) {
                    throw GradleException(
                        "Smoke found missing or inconsistent Logback version metadata:\n$combinedOutput",
                    )
                }
                val fatalMarkers =
                    listOf(
                        "NoClassDefFoundError",
                        "ClassNotFoundException",
                        "NoSuchMethodError",
                        "VerifyError",
                        "Exception in thread",
                    )
                if (fatalMarkers.any(stderrStr::contains)) {
                    throw GradleException("Smoke failed; stderr contained fatal markers:\n$stderrStr")
                }
                if (stderrStr.isNotBlank()) {
                    logger.warn("Smoke produced stderr:\n{}", stderrStr)
                }
                return stdout.toString(Charsets.UTF_8)
            }

            val versionOut = runJar("version")
            if (!versionOut.contains("gls") || !versionOut.contains("version")) {
                throw GradleException(
                    "Smoke check failed: 'version' output did not contain expected marker. Output=$versionOut",
                )
            }

            runJar("format", smokeFile.absolutePath)

            val checkOut = runJar("check", smokeFile.absolutePath)
            if (checkOut.isBlank()) {
                throw GradleException("Smoke check failed: 'check' produced no diagnostics output.")
            }
        }
    }

tasks.register<SmokeShadowJarTask>("smokeShadowJar") {
    group = "verification"
    description = "Build and smoke-test the shaded uber jar (version + check)."
    dependsOn(shadowJarTask)
    jarFile.set(shadowJarTask.flatMap { it.archiveFile })
    smokeDir.set(layout.buildDirectory.dir("tmp/smoke"))
}

val proguardEnabled =
    providers
        .gradleProperty("proguard")
        .map { it.toBooleanStrict() }
        .orElse(false)

val proguardOutJar =
    layout.buildDirectory.file("libs/gls-${project.version}-all-proguard.jar")

tasks.register<proguard.gradle.ProGuardTask>("proguardShadowJar") {
    group = "build"
    description = "Shrink the shaded uber jar with ProGuard (enable with -Pproguard=true)."
    dependsOn(shadowJarTask)
    enabled = proguardEnabled.get()
    notCompatibleWithConfigurationCache("ProGuard task is not configuration-cache compatible.")

    val jar = shadowJarTask.flatMap { it.archiveFile }.get().asFile
    val outJar = proguardOutJar.get().asFile
    outJar.parentFile.mkdirs()

    injars(jar)
    outjars(outJar)

    val javaHome = file(System.getProperty("java.home"))
    val jmodsDir =
        when {
            javaHome.resolve("jmods").isDirectory -> javaHome.resolve("jmods")
            javaHome.parentFile?.resolve("jmods")?.isDirectory == true -> javaHome.parentFile.resolve("jmods")
            else -> error("Could not locate JDK 'jmods' under java.home=$javaHome")
        }

    libraryjars(fileTree(jmodsDir) { include("*.jmod") })
    libraryjars(proguardLibrary)
    configuration(file("proguard-rules.pro"))
}

tasks.register<ReportJarCompositionTask>("reportProguardJarComposition") {
    group = "reporting"
    description = "Report size breakdown of the ProGuard-shrunk uber jar."
    dependsOn("proguardShadowJar")
    enabled = proguardEnabled.get()
    jarFile.set(proguardOutJar)
    reportFile.set(layout.buildDirectory.file("reports/jar/gls-proguard-composition.txt"))
}

tasks.register<SmokeShadowJarTask>("smokeProguardJar") {
    group = "verification"
    description = "Smoke-test the ProGuard-shrunk uber jar."
    dependsOn("proguardShadowJar")
    enabled = proguardEnabled.get()
    jarFile.set(proguardOutJar)
    smokeDir.set(layout.buildDirectory.dir("tmp/smoke-proguard"))
}
