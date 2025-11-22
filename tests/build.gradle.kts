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

                implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
                implementation(platform("com.fasterxml.jackson:jackson-bom:2.17.3"))
                implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
                implementation("com.fasterxml.jackson.core:jackson-databind")
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
                implementation("com.jayway.jsonpath:json-path:2.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.slf4j:slf4j-api:2.0.17")
                implementation("ch.qos.logback:logback-classic:1.5.18")

                implementation("org.jetbrains.kotlin:kotlin-test")
                implementation("org.junit.jupiter:junit-jupiter:5.14.0")
                implementation("org.assertj:assertj-core:3.26.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation("io.mockk:mockk:1.14.6")
                implementation("org.spockframework:spock-core:2.3-groovy-4.0")
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

                                @get:InputFiles
                                @get:PathSensitive(PathSensitivity.RELATIVE)
                                val classpathProvider = runtimeClasspath

                                @get:Input
                                val main = "com.github.albertocavalcante.groovylsp.MainKt"

                                override fun asArguments() =
                                    listOf(
                                        "-Dgroovy.lsp.e2e.execJar=${jarFileProvider.get().absolutePath}",
                                        "-Dgroovy.lsp.e2e.scenarioDir=$scenarios",
                                        "-Dgroovy.lsp.e2e.serverClasspath=${classpathProvider.get().asPath}",
                                        "-Dgroovy.lsp.e2e.mainClass=$main",
                                    )
                            },
                        )

                        dependsOn(groovyLspProject.tasks.named("shadowJar"))
                    }
                }
            }
        }
    }
}
