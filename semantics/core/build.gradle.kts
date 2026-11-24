// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    kotlin("jvm")
}

tasks.jar {
    archiveBaseName.set("groovy-semantics-core")
}

dependencies {
    // Functional Programming - Arrow-kt core types (Either, Option, etc.)
    api(libs.arrow.core)

    // Common functional utilities (DomainError, etc.)
    implementation(project(":common"))

    // Cycle-safe class hierarchy walks (internal package, shared by every module that walks user-written hierarchies)
    implementation(project(":parser:api"))

    // Groovy AST for SemanticDocumentBuilder
    implementation(libs.groovy.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // kotlin-logging in transitive deps (groovy-common) needs SLF4J impl at test runtime
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
}
