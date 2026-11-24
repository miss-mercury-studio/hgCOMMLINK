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
    // Set to true to add the open data set's bonus shapes to bonus-shapes.txt (see DataSetDriftTest).
    systemProperty("chummer.updateShapes", providers.gradleProperty("chummer.updateShapes").getOrElse("false"))
    testLogging { showStandardStreams = true }
}
