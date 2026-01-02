import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.zip.ZipFile
import javax.inject.Inject

plugins {
    kotlin("jvm")
    groovy
    id("com.gradleup.shadow")
    application
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

    // Logging
    implementation(libs.slf4j.api)
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
    implementation(project(":groovy-common"))
    implementation(project(":groovy-formatter"))
    implementation(project(":parser:native"))
    implementation(project(":parser:core"))
    implementation(project(":groovy-diagnostics:api"))
    implementation(project(":groovy-diagnostics:codenarc"))
    implementation(project(":groovy-jenkins"))
    implementation(project(":groovy-build-tool"))
    implementation(project(":groovy-spock"))
    implementation(project(":groovy-testing"))
    implementation(project(":groovy-junit"))
    implementation(project(":markdown"))
}

// Avoid the older Groovy jars that Gradle's groovy plugin adds implicitly;
// we pin Groovy 4.0.28 above and want that version to win consistently.
configurations.configureEach {
    exclude(group = "org.codehaus.groovy")
}

tasks.test {
    useJUnitPlatform()
    // execute tests in parallel to speed up the build
    // Use half of available processors to avoid resource contention, but at least 1
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)

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
    logger.lifecycle("Configured version for groovy-lsp: ${project.version}")
}

application {
    mainClass = "com.github.albertocavalcante.groovylsp.MainKt"
}

tasks.shadowJar {
    archiveBaseName = "gls"
    archiveClassifier = "all"

    // Standard shadow JAR configuration - includes all runtimeClasspath by default
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "com.github.albertocavalcante.groovylsp.MainKt"
    }

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

// Generate version properties file for runtime access
tasks.register("generateVersionProperties") {
    description = "Generate version.properties file from build version"
    group = "build"

    val outputDir = layout.buildDirectory.dir("generated/resources")
    val propertiesFile = outputDir.map { it.file("version.properties") }

    val versionProvider = providers.provider { project.version.toString() }
    val baseVersionProvider = providers.provider { baseVersion }

    inputs.property("version", versionProvider)
    inputs.property("baseVersion", baseVersionProvider)
    outputs.file(propertiesFile)

    doLast {
        val outDirFile = outputDir.get().asFile
        val propsFile = propertiesFile.get().asFile
        outDirFile.mkdirs()
        propsFile.writeText(
            """
            version=${versionProvider.get()}
            baseVersion=${baseVersionProvider.get()}
            """.trimIndent(),
        )
        logger.lifecycle(
            "Generated version.properties: version={}, baseVersion={}",
            versionProvider.get(),
            baseVersionProvider.get(),
        )
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
                // Intentionally includes trailing whitespace so `check` reliably emits at least one diagnostic.
                class Smoke {  \u0020
                  static void main(String[] args) {
                    println "ok"  \u0020
                  }
                }
                """.trimIndent(),
            )

            fun runJar(vararg args: String): String {
                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                execOperations.exec {
                    commandLine(listOf("java", "-jar", jar.absolutePath) + args)
                    standardOutput = stdout
                    errorOutput = stderr
                    // Disable colored output for reliable assertion checking
                    // Compliant with https://no-color.org/
                    environment("NO_COLOR", "1")
                }
                val stderrStr = stderr.toString(Charsets.UTF_8)
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
