// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Kotlin
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation(libs.kotlin.stdlib)

    // Logging
    implementation(libs.kotlin.logging)

    // Coroutines
    implementation(libs.kotlin.coroutines.core)

    // Gradle Tooling API
    implementation(libs.gradle.tooling.api)

    // Build Server Protocol (for Bazel, sbt, Mill support)
    implementation(libs.bsp4j)

    // JSON parsing for BSP connection files
    implementation(libs.kotlin.serialization.json)

    // BSP core module
    implementation(project(":bsp:bsp-core"))

    // Maven Embedder (for programmatic dependency resolution)
    implementation(libs.maven.embedder)
    implementation(libs.maven.compat)
    implementation(libs.maven.resolver.connector.basic)
    implementation(libs.maven.resolver.transport.http)
    implementation(libs.maven.resolver.supplier)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)

    // Detekt Formatting
    detektPlugins(libs.detekt.formatting)
}

// Exclude BSP client from coverage - requires real BSP server (integration test)
// NOTE: BspConnectionDetails and BspBuildTool are tested via unit tests.
// BspClient requires a running BSP server process to test properly.
kover {
    reports {
        filters {
            excludes {
                classes("*BspClient*")
            }
        }
    }
}

/** Machine-specific paths for the real Tooling API test; internal so they never affect cache keys. */
abstract class ScopedGradleTestArguments : CommandLineArgumentProvider {
    @get:Internal
    abstract val installation: DirectoryProperty

    @get:Internal
    abstract val userHome: DirectoryProperty

    override fun asArguments(): List<String> =
        listOfNotNull(
            installation.orNull?.let { "-Dgvy.gradle.scoped.test.installation=${it.asFile.absolutePath}" },
            "-Dgvy.gradle.scoped.test.home=${userHome.get().asFile.absolutePath}",
        )
}

tasks.test {
    useJUnitPlatform()
    // The older-Gradle check reads this variable; as an input it keeps a skipped run's cached result from
    // standing in for a run that should have tested those versions (#1376)
    inputs.property(
        "scopedGradleVersions",
        providers.environmentVariable("GVY_SCOPED_TEST_GRADLE_VERSIONS").orElse(""),
    )
    jvmArgumentProviders.add(
        objects.newInstance<ScopedGradleTestArguments>().apply {
            gradle.gradleHomeDir?.let { installation.set(it) }
            userHome.set(layout.buildDirectory.dir("scoped-gradle-test-home"))
        },
    )
}
