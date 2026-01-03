import org.gradle.process.CommandLineArgumentProvider

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    groovy
}

repositories {
    mavenCentral()
}

// Ensure groovy-lsp is evaluated so we can access its tasks
evaluationDependsOn(":groovy-lsp")

// Exclude e2eTest from Kover instrumentation and coverage collection.
// This prevents root koverHtmlReport/koverXmlReport from triggering e2eTest.
// E2E tests are slow integration tests that don't contribute to unit test coverage.
// Reference: https://kotlin.github.io/kotlinx-kover/gradle-plugin/#exclusion-of-test-tasks
kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("e2eTest")
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
                implementation(project(":groovy-lsp"))
                implementation(project(":groovy-formatter"))
                implementation(project(":groovy-jenkins"))

                implementation(libs.lsp4j)
                implementation(platform(libs.jackson.bom))
                implementation(libs.jackson.dataformat.yaml)
                implementation(libs.jackson.databind)
                implementation(libs.jackson.module.kotlin)
                implementation(libs.json.path)
                implementation(libs.kotlin.coroutines.core)
                implementation(libs.slf4j.api)
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

                        val groovyLspProject = project(":groovy-lsp")
                        val shadowJarFileProvider =
                            groovyLspProject.tasks
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
                                    groovyLspProject.layout.buildDirectory
                                        .dir("install/groovy-lsp/bin/groovy-lsp")
                                        .map { it.asFile.absolutePath }

                                override fun asArguments() =
                                    listOf(
                                        "-Dgroovy.lsp.e2e.execJar=${jarFileProvider.get().absolutePath}",
                                        "-Dgroovy.lsp.e2e.scenarioDir=$scenarios",
                                        "-Dgroovy.lsp.binary=${installDistBin.get()}",
                                    )
                            },
                        )

                        dependsOn(groovyLspProject.tasks.named("shadowJar"))
                        dependsOn(groovyLspProject.tasks.named("installDist"))

                        // E2E tests are heavy, give them more memory
                        maxHeapSize = "2G"

                        // Run sequentially by default to avoid memory exhaustion on CI runners.
                        // Each test spawns a separate LSP server JVM (~512MB-2GB each).
                        // Override via: ./gradlew e2eTest -Pe2eParallelForks=4
                        // NOTE: Uses stdio (not ports), so no port conflicts. Memory is the constraint.
                        val parallelForks = project.findProperty("e2eParallelForks")?.toString()?.toIntOrNull()
                            ?: if (System.getenv("GITHUB_ACTIONS") == "true") {
                                // GitHub Actions runners: macOS has 7GB RAM, Ubuntu has 16GB RAM
                                // Each fork uses ~1-1.5GB. Conservative: 2 on macOS, 3 on Ubuntu
                                when (System.getenv("RUNNER_OS")) {
                                    "macOS" -> 2
                                    "Linux" -> 3
                                    "Windows" -> 2  // Windows runners have 7GB
                                    else -> 1
                                }
                            } else {
                                // Local development: default to 1 to avoid OOM on smaller machines
                                1
                            }
                        maxParallelForks = parallelForks

                        // Fail tests that take too long (5 minutes default)
                        systemProperty("junit.jupiter.execution.timeout.default", "300s")
                        systemProperty("groovy.lsp.e2e.filter", System.getProperty("groovy.lsp.e2e.filter"))
                        systemProperty("groovy.lsp.e2e.updateGolden", System.getProperty("groovy.lsp.e2e.updateGolden"))
                        systemProperty(
                            "groovy.lsp.e2e.goldenDir",
                            layout.projectDirectory
                                .dir("e2e/resources/golden")
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
