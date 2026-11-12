import org.gradle.process.CommandLineArgumentProvider

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    groovy
}

repositories {
    mavenCentral()
}

// Ensure gls is evaluated so we can access its tasks
evaluationDependsOn(":gls")

// Keep harness and protocol checks outside production coverage collection.
// Root coverage reports must not trigger either task.
// Reference: https://kotlin.github.io/kotlinx-kover/gradle-plugin/#exclusion-of-test-tasks
kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("e2eTest")
            disabledForTestTasks.add("harnessTest")
        }
    }
}

// Disable koverVerify for this module - it only contains e2e test code, no production code to cover
tasks.matching { it.name == "koverVerify" }.configureEach {
    enabled = false
}

dependencies {
    "detektPlugins"(libs.detekt.formatting)
}

testing {
    suites {
        register<JvmTestSuite>("e2eTest") {
            useJUnitJupiter()

            sources {
                kotlin {
                    setSrcDirs(listOf("e2e/kotlin", "lsp-client/kotlin"))
                }
                resources {
                    setSrcDirs(listOf("e2e/resources", "lsp-client/resources"))
                }
            }

            dependencies {
                implementation(project(":gls"))
                implementation(project(":fmt"))
                implementation(project(":jenkins"))

                implementation(libs.lsp4j)
                implementation(libs.groovy.core)
                implementation(libs.jna)
                implementation(platform(libs.jackson.bom))
                implementation(libs.jackson.dataformat.yaml)
                implementation(libs.jackson.databind)
                implementation(libs.jackson.module.kotlin)
                implementation(libs.json.path)
                implementation(libs.kotlin.coroutines.core)
                implementation(libs.kotlin.logging)
                implementation(libs.logback.classic)

                implementation(libs.kotlin.test)
                implementation(libs.junit.jupiter)
                implementation(libs.assertj.core)
                implementation(libs.kotlin.coroutines.test)
                implementation(libs.mockk)
                implementation(libs.spock.core)

                // Jackson for YAML parsing (via ScenarioParser abstraction)
                // TODO: Replace Jackson with kotlinx.serialization when official YAML support is added
                // Track: https://github.com/Kotlin/kotlinx.serialization/issues/1836

                // kotlinx.serialization-json for runtime JSON operations (multiplatform, stable)
                // This is kept separate from YAML parsing and can remain even after YAML migration
                implementation(libs.kotlin.serialization.json)
            }

            targets {
                all {
                    testTask.configure {
                        description = "Runs end-to-end LSP scenarios."
                        shouldRunAfter(tasks.test)

                        val glsProject = project(":gls")
                        val shadowJarFileProvider =
                            glsProject.tasks
                                .named(
                                    "shadowJar",
                                ).map { it.outputs.files.singleFile }

                        val scenarioDirPath =
                            layout.projectDirectory
                                .dir("e2e/resources/scenarios")
                                .asFile.absolutePath
                        val e2eSourceSet = sourceSets.named("e2eTest")
                        val runtimeClasspath = e2eSourceSet.map { it.runtimeClasspath }

                        jvmArgumentProviders.add(
                            object : CommandLineArgumentProvider {
                                @get:InputFile
                                @get:PathSensitive(PathSensitivity.NAME_ONLY)
                                val jarFileProvider = shadowJarFileProvider

                                @get:Input
                                val scenarios = scenarioDirPath

                                @get:Input
                                val installDistBin =
                                    glsProject.layout.buildDirectory
                                        .dir("install/gls/bin/gls")
                                        .map { it.asFile.absolutePath }

                                override fun asArguments() =
                                    listOf(
                                        "-Dgroovy.lsp.e2e.execJar=${jarFileProvider.get().absolutePath}",
                                        "-Dgroovy.lsp.e2e.scenarioDir=$scenarios",
                                        "-Dgroovy.lsp.binary=${installDistBin.get()}",
                                    )
                            },
                        )

                        dependsOn(glsProject.tasks.named("shadowJar"))
                        dependsOn(glsProject.tasks.named("installDist"))

                        // E2E tests are heavy, give them more memory
                        maxHeapSize = "2G"

                        // Run sequentially by default to avoid memory exhaustion on CI runners.
                        // Each test spawns a separate LSP server JVM (~512MB-2GB each).
                        // Override via: ./gradlew e2eTest -Pe2eParallelForks=4
                        // NOTE: Uses stdio (not ports), so no port conflicts. Memory is the constraint.
                        val parallelForks =
                            project.findProperty("e2eParallelForks")?.toString()?.toIntOrNull()
                                ?: if (System.getenv("GITHUB_ACTIONS") == "true") {
                                    // GitHub Actions runners: Linux and Windows have ~16GB RAM, while macOS varies—
                                    // ARM64 (M1/M-style) runners ≈7GB, Intel macOS (macos-13/macos-15-intel) ≈14GB.
                                    // Each fork uses ~1-1.5GB, so the fork counts (2 on macOS/Windows, 3 on Ubuntu) remain conservative.
                                    when (System.getenv("RUNNER_OS")) {
                                        "macOS" -> 2
                                        "Linux" -> 3
                                        "Windows" -> 2 // Windows runners have ~16GB
                                        else -> 1
                                    }
                                } else {
                                    // Local development: default to 1 to avoid OOM on smaller machines
                                    1
                                }
                        maxParallelForks = parallelForks

                        // JUnit bounds factories and ordinary tests; ScenarioExecutor owns each dynamic test deadline.
                        systemProperty("junit.jupiter.execution.timeout.default", "300s")
                        System.getProperty("groovy.lsp.e2e.scenarioTimeoutMs")?.let { timeout ->
                            systemProperty("groovy.lsp.e2e.scenarioTimeoutMs", timeout)
                        }
                        System.getProperty("groovy.lsp.e2e.cliOutputLimitBytes")?.let { limit ->
                            systemProperty("groovy.lsp.e2e.cliOutputLimitBytes", limit)
                        }
                        systemProperty("groovy.lsp.e2e.filter", System.getProperty("groovy.lsp.e2e.filter"))
                        systemProperty("groovy.lsp.e2e.shard", System.getProperty("groovy.lsp.e2e.shard"))
                        // Shards split only the scenario suite; the other e2e classes run once, in shard 0.
                        val shardIndex = System.getProperty("groovy.lsp.e2e.shard")?.substringBefore('/')?.trim()
                        if (shardIndex != null && shardIndex != "0") {
                            filter.includeTestsMatching("*.ScenarioRunnerTest")
                        }
                        systemProperty("groovy.lsp.e2e.updateGolden", System.getProperty("groovy.lsp.e2e.updateGolden"))
                        // Local-only: rewrites snapshot files (refused on CI and under Bazel). Re-run with --rerun,
                        // because Gradle may serve the test task from its build cache.
                        systemProperty(
                            "groovy.lsp.e2e.updateSnapshots",
                            System.getProperty("groovy.lsp.e2e.updateSnapshots"),
                        )
                        // Lets the isolated E2E Gradle home link fixture distributions this build installed.
                        systemProperty("groovy.lsp.e2e.hostGradleUserHome", gradle.gradleUserHomeDir.absolutePath)
                        systemProperty(
                            "groovy.lsp.e2e.repoWrapperProperties",
                            rootProject.layout.projectDirectory
                                .file("gradle/wrapper/gradle-wrapper.properties")
                                .asFile.absolutePath,
                        )
                        systemProperty(
                            "groovy.lsp.e2e.goldenDir",
                            layout.projectDirectory
                                .dir("e2e/resources/golden")
                                .asFile.absolutePath,
                        )

                        systemProperty(
                            "groovy.lsp.e2e.snapshotDir",
                            layout.projectDirectory
                                .dir("e2e/resources/snapshots")
                                .asFile.absolutePath,
                        )

                        testLogging {
                            showStandardStreams = true
                            events("passed", "skipped", "failed")
                            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                            showStackTraces = true
                        }
                    }
                }
            }
        }
    }
}

val harnessSuite = testing.suites.named<JvmTestSuite>("e2eTest")

val harnessTest =
    tasks.register<Test>("harnessTest") {
        description = "Runs focused harness controls without assembling the fat server JAR or distribution."
        group = "verification"
        testClassesDirs = files(harnessSuite.map { it.sources.output.classesDirs })
        classpath = files(harnessSuite.map { it.sources.runtimeClasspath })
        useJUnitPlatform()
        systemProperty("junit.jupiter.execution.timeout.default", "300s")

        // This allowlist keeps new artifact-dependent tests in the full suite until explicitly inspected.
        listOf(
            "XfailOutcomeTest",
            "ScenarioExecutorResourcesTest",
            "ScenarioExecutionTest",
            "ScenarioContextTest",
            "ScenarioDslTest",
            "ScenarioParserTest",
            "ScenarioSchemaTest",
            "ScenarioCatalogTest",
        ).forEach { filter.includeTestsMatching("*.$it") }

        jvmArgumentProviders.add(
            object : CommandLineArgumentProvider {
                @get:InputDirectory
                @get:PathSensitive(PathSensitivity.RELATIVE)
                val scenarios = layout.projectDirectory.dir("e2e/resources/scenarios")

                override fun asArguments() = listOf("-Dgroovy.lsp.e2e.scenarioDir=${scenarios.asFile.absolutePath}")
            },
        )
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

// Apply the focused-task limits after the root project's general test defaults.
afterEvaluate {
    harnessTest.configure {
        minHeapSize = "128m"
        maxHeapSize = "1g"
        maxParallelForks = 1
    }
}
