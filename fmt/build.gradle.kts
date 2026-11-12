// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    kotlin("jvm")
}

group = "com.github.albertocavalcante"
version = rootProject.version

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.rewrite.groovy) {
        // Keep formatter functionality, but avoid shipping OpenRewrite's Git integration and native dependencies
        // unless we explicitly prove we need them at runtime.
        exclude(group = "org.openrewrite.tools", module = "jgit")
        exclude(group = "net.java.dev.jna", module = "jna")
        exclude(group = "net.java.dev.jna", module = "jna-platform")
    }

    // TokenStreamGuard uses the Groovy compiler's own lexer as ground truth.
    implementation(libs.groovy.core)
    implementation(project(":common"))
    implementation(libs.slf4j.api)

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Keep legacy Groovy 3 dependencies off the formatter classpath. rewrite-groovy and the
// explicit lexer dependency both use Groovy 4, resolved to the server's selected version.
configurations.configureEach {
    exclude(group = "org.codehaus.groovy")
}

tasks.test {
    useJUnitPlatform()
}
