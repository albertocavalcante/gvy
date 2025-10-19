import org.gradle.api.tasks.compile.GroovyCompile

plugins {
    kotlin("jvm") version "2.2.20"
    groovy
    id("com.gradleup.shadow") version "9.2.2"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("com.diffplug.spotless") version "8.0.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.2"
    id("org.sonarqube") version "6.3.1.5724"
    application
}

tasks.withType<org.gradle.api.tasks.compile.GroovyCompile>().configureEach {
    // Keep Groovy sources aligned with the Java 17 toolchain
    groovyOptions.encoding = "UTF-8"
}

group = "com.github.albertocavalcante"
// x-release-please-start-version
val baseVersion = "0.1.0"
// x-release-please-end

// Smart version detection: SNAPSHOT for development, clean for releases
version =
    when {
        // GitHub releases (tagged)
        System.getenv("GITHUB_REF_TYPE") == "tag" -> baseVersion

        // Release-please PR builds
        System.getenv("GITHUB_HEAD_REF")?.contains("release-please") == true -> baseVersion

        // All other builds (development)
        else -> "$baseVersion-SNAPSHOT"
    }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    // LSP4J - Language Server Protocol implementation
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.24.0")

    // Groovy - For AST parsing and analysis
    implementation("org.apache.groovy:groovy:4.0.28")
    // Add additional Groovy modules that might be needed for compilation
    implementation("org.apache.groovy:groovy-ant:4.0.28")
    implementation("org.apache.groovy:groovy-console:4.0.28")
    implementation("org.apache.groovy:groovy-json:4.0.28") // Required for CodeNarc JsonSlurper

    // CodeNarc - Static analysis for Groovy (Groovy 4.x compatible version)
    implementation("org.codenarc:CodeNarc:3.6.0-groovy-4.0")
    implementation("org.gmetrics:GMetrics-Groovy4:2.1.0") // Required for complexity rules

    // Gradle Tooling API - For dependency resolution
    implementation("org.gradle:gradle-tooling-api:9.1.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Kotlin Immutable Collections for functional data structures
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // Testing - Kotlin/Java
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.14.6")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Testing - Groovy (Spock Framework) - JUnit 5 platform native
    testImplementation("org.spockframework:spock-core:2.3-groovy-4.0")

    // Code Quality Tools
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")

    // Local Modules
    implementation(project(":groovy-formatter"))
}

// Avoid the older Groovy jars that Gradle's groovy plugin adds implicitly;
// we pin Groovy 4.0.28 above and want that version to win consistently.
configurations.configureEach {
    exclude(group = "org.codehaus.groovy")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
    }
}

// Code Quality Configuration
detekt {
    config.setFrom(files("detekt.yml"))
    buildUponDefaultConfig = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html {
            required.set(true)
            outputLocation.set(layout.buildDirectory.file("reports/detekt/detekt.html"))
        }
        xml {
            required.set(true)
            outputLocation.set(layout.buildDirectory.file("reports/detekt/detekt.xml"))
        }
        sarif {
            required.set(true)
            outputLocation.set(layout.buildDirectory.file("reports/detekt/detekt.sarif"))
        }
    }
}

spotless {
    val ktlintVersion = "1.7.1"
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**/*.kt")
        ktlint(ktlintVersion)
            .editorConfigOverride(
                mapOf(
                    "max_line_length" to "120",
                    "indent_size" to "4",
                    "ij_kotlin_packages_to_use_import_on_demand" to "unset",
                ),
            )
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion)
    }
    format("markdown") {
        target("**/*.md")
        targetExclude("**/build/**/*.md", "**/target/**/*.md")
        prettier()
            .config(
                mapOf(
                    "parser" to "markdown",
                    "printWidth" to 120,
                    "tabWidth" to 2,
                    "useTabs" to false,
                    "proseWrap" to "always",
                ),
            )
    }

    groovy {
        target("src/**/*.groovy")
        targetExclude("**/build/**/*.groovy", "src/test/resources/**/*.groovy")
        // Use simple indentation-based formatting for Groovy
        leadingTabsToSpaces()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

kover {
    reports {
        verify {
            rule {
                minBound(50) // Minimum line coverage: 50% (current: 51.117% - TODO: incrementally improve to 65%+)
            }
        }
    }
}

// Configure Kotlin-Groovy interop: Groovy compiles first, then Kotlin
tasks.named<GroovyCompile>("compileGroovy") {
    // Groovy compiles with declared dependencies only
    classpath = sourceSets.main.get().compileClasspath
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    // Kotlin compilation depends on Groovy output and includes it on classpath
    dependsOn(tasks.compileGroovy)
    libraries.from(tasks.compileGroovy.get().destinationDirectory)
}

// Ensure proper compilation order for tests too
tasks.named<GroovyCompile>("compileTestGroovy") {
    classpath = sourceSets.test.get().compileClasspath
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    dependsOn(tasks.compileTestGroovy)
    libraries.from(tasks.compileTestGroovy.get().destinationDirectory)
}

// Fix Kover task dependencies for Gradle 9
afterEvaluate {
    tasks.findByName("koverGenerateArtifactJvm")?.dependsOn(tasks.compileGroovy)
}

application {
    mainClass = "com.github.albertocavalcante.groovylsp.MainKt"
}

tasks.shadowJar {
    archiveBaseName = "groovy-lsp"
    archiveClassifier = ""

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
    doLast {
        println("Base version: $baseVersion")
        println("Final version: $version")
        println("GITHUB_REF_TYPE: ${System.getenv("GITHUB_REF_TYPE") ?: "not set"}")
        println("GITHUB_HEAD_REF: ${System.getenv("GITHUB_HEAD_REF") ?: "not set"}")
        println("Is release build: ${version == baseVersion}")
    }
}

// Code Quality Convenience Tasks
tasks.register("lint") {
    description = "Run all linting tasks"
    group = "verification"
    dependsOn("detekt", "spotlessCheck")
}

tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektAutoCorrect") {
    description = "Run detekt with auto-correct enabled"
    parallel = true
    config.setFrom(files("detekt.yml"))
    buildUponDefaultConfig = true
    setSource(files("src"))
    autoCorrect = true
    reports {
        // Disable reports for auto-correct run to keep output clean
        html.required.set(false)
        xml.required.set(false)
        sarif.required.set(false)
    }
}

tasks.register("lintFix") {
    description = "Fix all auto-correctable lint and formatting issues"
    group = "formatting"
    dependsOn("spotlessApply", "detektAutoCorrect")
}

tasks.named("detektAutoCorrect").configure { mustRunAfter("spotlessApply") }

tasks.register("format") {
    description = "Format all source code"
    group = "formatting"
    dependsOn("spotlessApply")
}

tasks.register("quality") {
    description = "Run all code quality checks including coverage"
    group = "verification"
    dependsOn("lint", "koverVerify")
}

// Make check task depend on quality checks
tasks.check {
    dependsOn("detekt", "spotlessCheck")
}

sonar {
    properties {
        property("sonar.projectKey", "albertocavalcante_groovy-lsp")
        property("sonar.organization", "albertocavalcante")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/kover/report.xml")
    }
}
