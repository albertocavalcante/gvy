import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.dependency.analysis) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    alias(libs.plugins.kover)
    alias(libs.plugins.sonarqube)
    application
}

tasks.withType<org.gradle.api.tasks.compile.GroovyCompile>().configureEach {
    // Keep Groovy sources aligned with the Java 17 toolchain
    groovyOptions.encoding = "UTF-8"
}

group = "com.github.albertocavalcante"
// x-release-please-start-version
val baseVersion = "0.3.0"
// x-release-please-end

version =
    when {
        System.getenv("GITHUB_REF_TYPE") == "tag" -> baseVersion
        System.getenv("GITHUB_HEAD_REF")?.contains("release-please") == true -> baseVersion
        else -> "$baseVersion-SNAPSHOT"
    }

extra["baseVersion"] = baseVersion

subprojects {
    apply(plugin = "com.autonomousapps.dependency-analysis")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    repositories {
        mavenCentral()
        mavenLocal()
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }

    // Code Quality Configuration
    detekt {
        config.setFrom(rootProject.files("detekt.yml"))
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
                    minBound(54)
                }
            }
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
        config.setFrom(rootProject.files("detekt.yml"))
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

    // Force sequential test execution on GitHub Actions to avoid resource contention/flakiness
    afterEvaluate {
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            tasks.withType<Test>().configureEach {
                maxParallelForks = 1
            }
        }
    }
}

sonar {
    properties {
        property("sonar.projectKey", "albertocavalcante_groovy-lsp")
        property("sonar.organization", "albertocavalcante")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/kover/report.xml")
    }
}

tasks.named("sonar") {
    notCompatibleWithConfigurationCache(
        "SonarQube task resolves configurations outside of configuration cache-safe boundaries",
    )
}

// Root level convenience tasks that trigger subprojects
tasks.register("lint") {
    description = "Run all linting tasks"
    group = "verification"
    dependsOn(subprojects.map { it.tasks.named("lint") })
}

tasks.register("lintFix") {
    description = "Fix all auto-correctable lint and formatting issues"
    group = "formatting"
    dependsOn(subprojects.map { it.tasks.named("lintFix") })
}

tasks.register("format") {
    description = "Format all source code"
    group = "formatting"
    dependsOn(subprojects.map { it.tasks.named("format") })
}

tasks.register("quality") {
    description = "Run all code quality checks including coverage"
    group = "verification"
    dependsOn(subprojects.map { it.tasks.named("quality") })
}

