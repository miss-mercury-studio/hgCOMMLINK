plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // Paths to the read-only Chummer checkout and reference characters (see gradle.properties).
    systemProperty("chummer.src", providers.gradleProperty("chummer.src").get())
    systemProperty("chummer.reference", providers.gradleProperty("chummer.reference").get())
    testLogging { showStandardStreams = true }
}
