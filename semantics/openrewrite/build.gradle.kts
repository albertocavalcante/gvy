plugins {
    kotlin("jvm")
}

group = "com.github.albertocavalcante"
version = rootProject.version

dependencies {
    // Share semantics:core's selected Groovy 4 version with the server and OpenRewrite.
    implementation(project(":semantics:core"))

    // OpenRewrite uses Groovy 4 as well.
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
