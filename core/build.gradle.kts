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
    // Optional folder of characters from bug reports and testers, kept outside the repo (see Fixtures). Empty when not set.
    systemProperty("chummer.submitted", providers.gradleProperty("chummer.submitted").getOrElse(""))
    // Adding or changing a character there must rerun the tests, so the folder counts as an input.
    providers.gradleProperty("chummer.submitted").orNull?.takeIf { it.isNotBlank() }?.let { inputs.dir(it).optional() }
    // Set to true to add the open data set's bonus shapes to bonus-shapes.txt (see DataSetDriftTest).
    systemProperty("chummer.updateShapes", providers.gradleProperty("chummer.updateShapes").getOrElse("false"))
    testLogging { showStandardStreams = true }
}
