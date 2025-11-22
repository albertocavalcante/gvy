plugins {
    kotlin("jvm")
}

group = "com.github.albertocavalcante"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.openrewrite:rewrite-groovy:8.67.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.0")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}
