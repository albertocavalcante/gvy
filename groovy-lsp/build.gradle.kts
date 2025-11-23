plugins {
    kotlin("jvm")
    groovy
    id("com.gradleup.shadow")
    application
}

val baseVersion: String by rootProject.extra

version =
    when {
        System.getenv("GITHUB_REF_TYPE") == "tag" -> baseVersion
        System.getenv("GITHUB_HEAD_REF")?.contains("release-please") == true -> baseVersion
        else -> "$baseVersion-SNAPSHOT"
    }

dependencies {
    // LSP4J - Language Server Protocol implementation
    implementation(libs.lsp4j)
    implementation(libs.lsp4j.jsonrpc)

    // Groovy - For AST parsing and analysis
    implementation(libs.groovy.core)
    // Add additional Groovy modules that might be needed for compilation
    implementation(libs.groovy.ant)
    implementation(libs.groovy.console)
    implementation(libs.groovy.json) // Required for CodeNarc JsonSlurper

    // CodeNarc - Static analysis for Groovy (Groovy 4.x compatible version)
    // CodeNarc is now a transitive dependency of the diagnostics module, but kept here for
    // backward compatibility if any direct usages remain during refactoring
    implementation(libs.codenarc)
    implementation(libs.gmetrics) // Required for complexity rules

    // Gradle Tooling API - For dependency resolution
    implementation(libs.gradle.tooling.api)

    // Kotlin Coroutines
    implementation(libs.kotlin.coroutines.core)

    // Kotlin Immutable Collections for functional data structures
    implementation(libs.kotlin.collections.immutable)

    // Logging
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

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
    implementation(project(":groovy-formatter"))
    implementation(project(":groovy-parser"))
    implementation(project(":groovy-diagnostics:api"))
    implementation(project(":groovy-diagnostics:codenarc"))
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
    systemProperty("junit.jupiter.execution.timeout.default", "300s")

    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }
}

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
    archiveBaseName = "groovy-lsp"
    archiveClassifier = "all"

    // Standard shadow JAR configuration - includes all runtimeClasspath by default
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "com.github.albertocavalcante.groovylsp.MainKt"
    }

    // Merge service files for proper SLF4J and other service provider bindings
    mergeServiceFiles()
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

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
