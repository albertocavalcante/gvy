plugins {
    kotlin("jvm")
}

group = "com.github.albertocavalcante"
version = rootProject.version

dependencies {
    // Semantics core (exclude Groovy to avoid conflict with OpenRewrite's Groovy 3.x)
    implementation(project(":semantics:core")) {
        exclude(group = "org.apache.groovy")
    }

    // OpenRewrite (uses Groovy 3.x internally)
    implementation(libs.rewrite.groovy)

    // Arrow for Either types
    implementation(libs.arrow.core)

    // Testing
    testImplementation(kotlin("test"))
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
