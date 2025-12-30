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

    implementation(project(":groovy-common"))

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
