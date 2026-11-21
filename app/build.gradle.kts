import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "studio.missmercury.hgcommlink"
    compileSdk = 36

    defaultConfig {
        applicationId = "studio.missmercury.hgcommlink"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

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

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    debugImplementation(libs.compose.tooling)
}

// Chummer's game data ships unmodified inside the APK; the app unpacks it on first run.
val chummerSrc = providers.gradleProperty("chummer.src")
val copyChummerData = tasks.register<Copy>("copyChummerData") {
    from(File(chummerSrc.get(), "Chummer/data")) {
        include("*.xml")
        exclude("*.bak")
    }
    into(layout.buildDirectory.dir("generated/chummerAssets/chummer-data"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/chummerAssets"))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(copyChummerData) }
