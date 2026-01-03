plugins {
    kotlin("jvm")
    `java-library`
}

group = "com.github.albertocavalcante"
version = rootProject.version

dependencies {
    api(project(":parser:api"))

    // OpenRewrite Groovy parser (same as groovy-formatter)
    implementation(libs.rewrite.groovy) {
        exclude(group = "org.openrewrite.tools", module = "jgit")
        exclude(group = "net.java.dev.jna", module = "jna")
        exclude(group = "net.java.dev.jna", module = "jna-platform")
    }

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
