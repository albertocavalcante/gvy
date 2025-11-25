import org.gradle.process.CommandLineArgumentProvider

plugins {
    kotlin("jvm")
    groovy
}

repositories {
    mavenCentral()
}

// Ensure groovy-lsp is evaluated so we can access its tasks
evaluationDependsOn(":groovy-lsp")

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

                                override fun asArguments() =
                                    listOf(
                                        "-Dgroovy.lsp.e2e.execJar=${jarFileProvider.get().absolutePath}",
                                        "-Dgroovy.lsp.e2e.scenarioDir=$scenarios",
                                    )
                            },
                        )

                        dependsOn(groovyLspProject.tasks.named("shadowJar"))

                        // E2E tests are heavy, give them more memory
                        maxHeapSize = "2G"

                        // Run sequentially to avoid port conflicts and resource contention
                        maxParallelForks = 1

                        // Fail tests that take too long (5 minutes default)
                        systemProperty("junit.jupiter.execution.timeout.default", "300s")

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
