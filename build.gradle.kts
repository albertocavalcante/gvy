import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.api.tasks.testing.Test
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.dependency.analysis) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    alias(libs.plugins.kover)
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.gradle.versions)
    application
}

tasks.withType<org.gradle.api.tasks.compile.GroovyCompile>().configureEach {
    // Keep Groovy sources aligned with the Java 17 toolchain
    groovyOptions.encoding = "UTF-8"
}

group = "com.github.albertocavalcante"
// x-release-please-start-version
val baseVersion = "0.4.8"
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

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies {
        detektPlugins(libs.findLibrary("detekt-formatting").get())
    }

    // Configure Java Toolchain for all subprojects to ensure hermetic builds
    // Only apply to projects that have the 'java' plugin (or derived plugins like 'groovy', 'kotlin-jvm')
    pluginManager.withPlugin("java") {
        configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }

    // Ensure bytecode compatibility is limited to Java 17 despite using JDK 21 toolchain
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    tasks.withType<GroovyCompile>().configureEach {
        options.release.set(17)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Code Quality Configuration
    detekt {
        config.setFrom(rootProject.files("tools/lint/detekt.yml"))
        buildUponDefaultConfig = true
        // Use per-module baseline if it exists, otherwise no baseline
        val moduleBaseline = file("detekt-baseline.xml")
        if (moduleBaseline.exists()) {
            baseline = moduleBaseline
        }
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
        // NOTE: Markdown formatting disabled - requires npm which is not available on self-hosted runner
        // To re-enable, install npm on the runner and uncomment this block
        // format("markdown") {
        //     target("**/*.md")
        //     targetExclude(
        //         "**/build/**/*.md",
        //         "**/target/**/*.md",
        //         "**/src/test/resources/**/*.md",
        //         "**/BUNDLED_STUBS_TODO.md",
        //     )
        //     prettier()
        //         .config(
        //             mapOf(
        //                 "parser" to "markdown",
        //                 "printWidth" to 120,
        //                 "tabWidth" to 2,
        //                 "useTabs" to false,
        //                 "proseWrap" to "always",
        //             ),
        //         )
        // }

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
        config.setFrom(rootProject.files("tools/lint/detekt.yml"))
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
    // Otherwise, use parallel execution and generous memory locally
    afterEvaluate {
        tasks.withType<Test>().configureEach {
            if (System.getenv("GITHUB_ACTIONS") == "true") {
                maxParallelForks = 1
                minHeapSize = "256m"
                maxHeapSize = "1g"
            } else {
                // Local development optimization
                maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
                minHeapSize = "512m"
                maxHeapSize = "4g"
                
                // Improve console output
                testLogging {
                    events("passed", "skipped", "failed")
                    showStandardStreams = false // Reduce noise, only show failures usually
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
            
            // Shared JVM args for better test performance
            jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=512m")
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

tasks.register<Exec>("installLefthook") {
    description = "Install Git hooks using Lefthook"
    group = "help"
    
    // Use layout to get project directory in a configuration-cache friendly way
    val projectDir = layout.projectDirectory
    onlyIf("Git repository must exist") {
        projectDir.dir(".git").asFile.exists()
    }

    executable = "lefthook"
    args("install", "-f")
    // Build fails if lefthook is not installed - intentional to ensure consistent hooks
}

// Ensure hooks are installed when building or checking
tasks.named("build") { dependsOn("installLefthook") }
tasks.named("check") { dependsOn("installLefthook") }

