// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    kotlin("jvm")
    alias(libs.plugins.jmh)
}

group = "com.github.albertocavalcante"
version = rootProject.version

dependencies {
    implementation(project(":common"))
    implementation(project(":source"))
    api(project(":parser:api"))
    api(libs.groovy.core)
    implementation(libs.groovy.macro)

    implementation(libs.arrow.core)
    implementation(libs.kotlin.collections.immutable)
    implementation(libs.kotlin.logging)

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
    // kotlin-logging needs SLF4J impl at test runtime
    testImplementation(libs.logback.classic)
    testImplementation("ch.qos.logback:logback-core:${libs.versions.logback.get()}")
    testImplementation(libs.slf4j.api)
}

jmh {
    warmupIterations.set(2)
    iterations.set(5)
    fork.set(1)
    failOnError.set(true)
    resultFormat.set("JSON")
    profilers.add("gc")
}

tasks.test {
    useJUnitPlatform()
}

kover {
    reports {
        verify {
            rule {
                minBound(54) // Minimum line coverage: 54% for parser module
            }
        }
    }
}
