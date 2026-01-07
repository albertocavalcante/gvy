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
    testRuntimeOnly("org.openrewrite:rewrite-java-17:8.71.0")
}

tasks.test {
    useJUnitPlatform()
}
