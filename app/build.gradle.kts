import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// M0 spike: bundle Chummer's data files into the APK so the device benchmark can load them.
// Production will fetch/cache the data pack instead (see docs/PLAN.md).
val chummerSrc = providers.gradleProperty("chummer.src").get()
val chummerRef = providers.gradleProperty("chummer.reference").get()
val spikeAssets = layout.buildDirectory.dir("spikeAssets")
val copySpikeAssets = tasks.register<Copy>("copySpikeAssets") {
    from("$chummerSrc/Chummer/data") { include("*.xml"); into("data") }
    from("$chummerRef/Viper_career.chum5") { into("fixtures") }
    into(spikeAssets)
}

android {
    namespace = "studio.missmercury.hgcommlink"
    compileSdk = 36

    defaultConfig {
        applicationId = "studio.missmercury.hgcommlink"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-spike"
    }

    sourceSets["main"].assets.srcDir(spikeAssets)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/DEPENDENCIES")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.named("preBuild") { dependsOn(copySpikeAssets) }

dependencies {
    implementation(project(":core"))
}
