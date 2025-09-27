plugins {
    kotlin("jvm") version "2.0.20"
    id("com.gradleup.shadow") version "9.0.0-beta2"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("com.diffplug.spotless") version "7.2.1"
    id("org.jetbrains.kotlinx.kover") version "0.9.2"
    id("org.sonarqube") version "6.3.1.5724"
    application
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

repositories {
    mavenCentral()
    // Gradle repository for Tooling API
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
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

    // Gradle Tooling API - For dependency resolution
    implementation("org.gradle:gradle-tooling-api:9.1.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Kotlin Immutable Collections for functional data structures
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.15")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Code Quality Tools
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}

tasks.test {
    useJUnitPlatform()
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
            outputLocation.set(file("build/reports/detekt/detekt.html"))
        }
        xml {
            required.set(true)
            outputLocation.set(file("build/reports/detekt/detekt.xml"))
        }
        sarif {
            required.set(true)
            outputLocation.set(file("build/reports/detekt/detekt.sarif"))
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
    yaml {
        target("**/*.yml", "**/*.yaml")
        targetExclude("**/build/**/*.yml", "**/build/**/*.yaml", "**/target/**/*.yml", "**/target/**/*.yaml")
        jackson()
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

application {
    mainClass = "com.github.albertocavalcante.groovylsp.MainKt"
}

tasks.shadowJar {
    archiveBaseName = "groovy-lsp"
    archiveClassifier = ""
    manifest {
        attributes["Main-Class"] = "com.github.albertocavalcante.groovylsp.MainKt"
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
