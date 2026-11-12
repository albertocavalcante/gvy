// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "OpenRewrite recipes for auto-fixing CodeNarc violations in Groovy code"

dependencies {
    // OpenRewrite Core
    implementation(libs.rewrite.groovy)

    // Kotlin
    implementation(libs.kotlin.stdlib)

    // Testing
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation("org.openrewrite:rewrite-test:${libs.versions.rewriteGroovy.get()}")

    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
    testRuntimeOnly("org.openrewrite:rewrite-java-17:${libs.versions.rewriteGroovy.get()}")
}

tasks.test {
    useJUnitPlatform()
}
