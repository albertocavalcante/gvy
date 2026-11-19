// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Groovy
    implementation(libs.groovy.core)

    // Kotlin
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.coroutines.core)

    // Ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Arrow for functional programming
    implementation(libs.arrow.core)

    // Logging
    implementation(libs.kotlin.logging)

    // ClassGraph for scanning plugin JARs
    implementation(libs.classgraph)

    // ASM for bytecode analysis (extracting getFunctionName return values)
    implementation(libs.asm)

    // HTML to Markdown conversion for vars documentation
    implementation(libs.flexmark.html2md)

    implementation(project(":parser:api"))
    api(project(":common"))
    api(project(":dsl:gdsl"))
    api(project(":build-tool"))

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    // Detekt formatting
    detektPlugins(libs.detekt.formatting)
}

tasks.processTestResources {
    from(rootProject.file("editors/code/client/src/test/fixtures/gdsl-snippet-defaults.gdsl")) {
        into("editors/code/client/src/test/fixtures")
    }
}

tasks.test {
    useJUnitPlatform()
}
