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
    api(libs.groovy.core)

    implementation(libs.kotlin.collections.immutable)
    implementation(libs.slf4j.api)

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

kover {
    reports {
        verify {
            rule {
                minBound(40) // Minimum line coverage: 40% for parser module
            }
        }
    }
}
