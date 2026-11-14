// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import com.diffplug.gradle.spotless.SpotlessTask
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
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
val baseVersion = "0.5.4"
// x-release-please-end

version =
    when {
        System.getenv("GITHUB_REF_TYPE") == "tag" -> baseVersion
        System.getenv("GITHUB_HEAD_REF")?.contains("release-please") == true -> baseVersion
        else -> "$baseVersion-SNAPSHOT"
    }

extra["baseVersion"] = baseVersion

// A separate configuration keeps language-server dependencies out of user JVMs.
val fileExecutionRuntime = configurations.create("fileExecutionRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    add(fileExecutionRuntime.name, libs.groovy.core)
    add(fileExecutionRuntime.name, libs.ivy)
}
tasks.register<Sync>("stageFileExecutionRuntime") {
    group = "distribution"
    description = "Stage the dedicated runtime; editor packaging verifies its pinned checksums and notices."
    from(fileExecutionRuntime)
    into(project(":gls").layout.buildDirectory.dir("editor-server/runtime"))
}

val testJavaVersion = providers.gradleProperty("testJavaVersion").orElse("17").map { JavaLanguageVersion.of(it) }
val testJavaLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(testJavaVersion)
}

tasks.named<Test>("test") {
    javaLauncher.set(testJavaLauncher)
    dependsOn(
        subprojects.map { project ->
            project.tasks.withType<Test>().matching { it.name == "test" || it.name == "jvmTest" }
        },
    )
}

val licenseCheck = tasks.register<Exec>("licenseCheck") {
    description = "Verify repository licensing metadata and maintained source headers"
    group = "verification"
    workingDir(rootProject.layout.projectDirectory)
    commandLine(
        "uv",
        "run",
        "--offline",
        "--locked",
        "--no-dev",
        "--project",
        rootProject.file(".reuse").absolutePath,
        "python",
        rootProject.file(".reuse/check.py").absolutePath,
        "--root",
        rootProject.layout.projectDirectory.asFile.absolutePath,
    )
}

allprojects {
    tasks.configureEach {
        // Scan licensing before requested work can create ignored output trees.
        if (path != ":licenseCheck") {
            mustRunAfter(licenseCheck)
        }

        if (name in setOf("check", "lint", "quality") || name.startsWith("publish")) {
            dependsOn(licenseCheck)
        }
    }
}

subprojects {
    apply(plugin = "com.autonomousapps.dependency-analysis")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    repositories {
        mavenCentral()
        mavenLocal()
        google()
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

    val generatedKotlin = rootProject.files(
        rootProject.allprojects.map { owner ->
            fileTree(owner.layout.buildDirectory) { include("**/*.kt") }
        },
    )
    spotless {
        val ktlintVersion = "1.7.1"
        kotlin {
            target("**/*.kt")
            // Parent projects also target child sources. Exclude every actual Gradle output tree.
            targetExclude(generatedKotlin)
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
                    minBound(if (project.path == ":viz:desktop") 70 else 54)
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
        // Same per-module baseline as the `detekt` check; without it lintFix fails on findings
        // that `lint` deliberately tolerates.
        file("detekt-baseline.xml").takeIf { it.exists() }?.let { baseline.set(it) }
        // Fix exactly what `detekt` checks (its configured source dirs), not all of src/ (e.g. jmh).
        setSource(detekt.source)
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

    // On GitHub Actions: two test JVMs with modest heaps (runner-sized)
    // Otherwise, use parallel execution and generous memory locally
    afterEvaluate {
        tasks.withType<Test>().configureEach {
            javaLauncher.set(testJavaLauncher)
            if (System.getenv("GITHUB_ACTIONS") == "true") {
                // Hosted runners have 4 vCPUs and 16 GB; two 1 GB test JVMs fit. Forks are separate
                // JVMs, so they share no static state.
                maxParallelForks = 2
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

            // MockK also attaches bootstrap dispatchers in other modules' tests. Select a shared
            // non-sharing test JVM policy instead of mapping a CDS archive that attachment invalidates.
            // This changes archive reuse, not instrumentation or warning reporting.
            // https://github.com/mockk/mockk/blob/1.14.12/doc/md/jep451-dynamic-agent-loading.md
            jvmArgs("-Xshare:off")

            // Fail on discovery warnings. JUnit silently skips a @Test that returns a value (e.g. a Kotlin
            // `fun x() = runBlocking { ...; assertNotNull(y) }`); this turns that into a build failure.
            systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")

            // Keep generated Jenkins stubs out of the real ~/.groovy-lsp cache during tests.
            // Mirrors JenkinsStubsLocation.CACHE_ROOT_PROPERTY.
            val stubsCacheRoot = layout.buildDirectory.dir("tmp/jenkins-stubs-cache/$name")
            jvmArgumentProviders.add(
                object : CommandLineArgumentProvider {
                    @get:LocalState
                    val cacheRoot = stubsCacheRoot

                    override fun asArguments() =
                        listOf("-Dgroovy.lsp.jenkins.stubsCacheRoot=${cacheRoot.get().asFile.absolutePath}")
                },
            )
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
data class KotlinFormattingTargetCase(
    val name: String,
    val sources: Set<File>,
    val generated: File,
    val targets: FileCollection,
)

val verifyKotlinFormattingTargets = tasks.register("verifyKotlinFormattingTargets") {
    description = "Verify Kotlin source layouts are formatted while generated output is excluded"
    group = "verification"
    val cases = mapOf(
        ":build-tool" to listOf(
            "src/main/kotlin/com/github/albertocavalcante/gvy/build/BuildToolManager.kt",
            "src/test/kotlin/com/github/albertocavalcante/gvy/build/GradleBuildStrategyTest.kt",
        ),
        ":common" to listOf("src/main/kotlin/com/github/albertocavalcante/gvy/common/text/TextIndex.kt"),
        ":viz:ast-model" to listOf(
            "src/commonMain/kotlin/com/github/albertocavalcante/gvy/viz/model/AstNodeDto.kt",
        ),
        ":parser" to listOf(
            "native/src/main/kotlin/com/github/albertocavalcante/groovyparser/GroovyParserFacade.kt",
        ),
        ":semantics" to listOf(
            "native/src/main/kotlin/com/github/albertocavalcante/gvy/semantics/native/NativeScope.kt",
        ),
        ":viz" to listOf(
            "ast-model/src/commonMain/kotlin/com/github/albertocavalcante/gvy/viz/model/AstNodeDto.kt",
        ),
        ":tests" to listOf(
            "e2e/kotlin/com/github/albertocavalcante/gvy/gls/e2e/JsonBridge.kt",
            "lsp-client/kotlin/com/github/albertocavalcante/gvy/gls/testing/api/GroovyLspApi.kt",
        ),
    ).flatMap { (path, sources) ->
        val owner = project(path)
        val targets = owner.tasks.named<SpotlessTask>("spotlessKotlin").get().target
        rootProject.allprojects
            .filter { it.projectDir.toPath().startsWith(owner.projectDir.toPath()) }
            .map { outputOwner ->
                KotlinFormattingTargetCase(
                    "$path -> ${outputOwner.path}",
                    sources.map { owner.file(it) }.toSet(),
                    outputOwner.layout.buildDirectory.file("format-target-probe/Generated.kt").get().asFile,
                    targets,
                )
            }
    }
    doLast {
        for (case in cases) {
            check(case.sources.all { it.isFile }) { "Missing formatter regression source for ${case.name}" }
            check(!case.generated.exists()) { "Refusing to overwrite formatter probe ${case.generated}" }
            case.generated.parentFile.mkdirs()
            try {
                case.generated.writeText("val generated=1\n")
                val targets = case.targets.files
                check(targets.containsAll(case.sources)) { "Formatter excludes tracked sources for ${case.name}" }
                check(case.generated !in targets) { "Formatter includes generated output for ${case.name}" }
            } finally {
                check(case.generated.delete() || !case.generated.exists()) {
                    "Could not remove formatter probe ${case.generated}"
                }
                case.generated.parentFile.delete()
            }
        }
        logger.lifecycle("Verified Kotlin formatter source and generated-output targets for ${cases.size} layouts")
    }
}

tasks.register("lint") {
    description = "Run all linting tasks"
    group = "verification"
    dependsOn(subprojects.map { it.tasks.named("lint") })
    dependsOn(verifyKotlinFormattingTargets)
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
