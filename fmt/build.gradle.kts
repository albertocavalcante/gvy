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

// rewrite-groovy brings Groovy 3 (org.codehaus.groovy). The server excludes it so Groovy 4 wins
// (gls/build.gradle.kts); do the same here so fmt tests run the lexer and parser the server ships.
configurations.configureEach {
    exclude(group = "org.codehaus.groovy")
}

tasks.test {
    useJUnitPlatform()
}
