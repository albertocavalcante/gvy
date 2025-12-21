plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":groovy-jenkins"))
    implementation(project(":groovy-gdsl"))

    // Kotlin serialization for JSON output
    implementation(libs.kotlin.serialization.json)

    // Logging
    implementation(libs.slf4j.api)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Detekt formatting
    detektPlugins(libs.detekt.formatting)
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

/**
 * Converts extracted GDSL to JSON format.
 *
 * Usage:
 *   ./gradlew :tools:jenkins-extractor:convertGdsl \
 *     --args="input.gdsl output-dir/ jenkins-version plugin-id plugin-version"
 */
tasks.register<JavaExec>("convertGdsl") {
    group = "jenkins"
    description = "Convert GDSL to plugin metadata JSON"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.github.albertocavalcante.groovyjenkins.extractor.GdslToJsonKt")

    workingDir = rootProject.projectDir

    abstract class ConvertGdslArgs : CommandLineArgumentProvider {
        @get:Input abstract val gdslFile: Property<String>

        @get:Input abstract val outputDir: Property<String>

        @get:Input abstract val jenkinsVersion: Property<String>

        @get:Input abstract val pluginId: Property<String>

        @get:Input abstract val pluginVersion: Property<String>

        @get:Input @get:Optional
        abstract val pluginDisplayName: Property<String>

        override fun asArguments(): Iterable<String> {
            val required =
                mapOf(
                    "gdslFile" to gdslFile.orNull,
                    "outputDir" to outputDir.orNull,
                    "jenkinsVersion" to jenkinsVersion.orNull,
                    "pluginId" to pluginId.orNull,
                    "pluginVersion" to pluginVersion.orNull,
                )

            val missing = required.filterValues { it.isNullOrBlank() }.keys
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Missing required properties for :tools:jenkins-extractor:convertGdsl: " +
                        missing.joinToString(separator = ", ") +
                        ". Example:\n" +
                        "  ./gradlew :tools:jenkins-extractor:convertGdsl " +
                        "-PgdslFile=output/gdsl-output.groovy -PoutputDir=output/json " +
                        "-PjenkinsVersion=2.426.3 -PpluginId=jenkins -PpluginVersion=2.426.3",
                )
            }

            val argsList =
                mutableListOf(
                    required.getValue("gdslFile")!!,
                    required.getValue("outputDir")!!,
                    required.getValue("jenkinsVersion")!!,
                    required.getValue("pluginId")!!,
                    required.getValue("pluginVersion")!!,
                )

            val displayName = pluginDisplayName.orNull
            if (!displayName.isNullOrBlank()) {
                argsList.add(displayName)
            }

            return argsList
        }
    }

    val argProvider =
        objects.newInstance(ConvertGdslArgs::class.java).apply {
            gdslFile.set(providers.gradleProperty("gdslFile"))
            outputDir.set(providers.gradleProperty("outputDir"))
            jenkinsVersion.set(providers.gradleProperty("jenkinsVersion"))
            pluginId.set(providers.gradleProperty("pluginId"))
            pluginVersion.set(providers.gradleProperty("pluginVersion"))
            pluginDisplayName.set(providers.gradleProperty("pluginDisplayName"))
        }

    argumentProviders.add(argProvider)
}
