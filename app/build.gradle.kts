import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

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
        // One source of truth: app.versionName in gradle.properties. The code is major*10000 + minor*100 + patch,
        // so it always rises with the version (0.1.1 -> 101, 1.2.3 -> 10203).
        val v = providers.gradleProperty("app.versionName").get()
        versionName = v
        versionCode = v.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }
    }

    // Release signing. CI passes the key in environment variables; on this PC it comes from a
    // keystore.properties file kept outside the repo (see docs/RELEASING.md). Without either, release builds are unsigned.
    val signingProps = Properties().apply {
        val file = File(System.getenv("HGCOMMLINK_KEYSTORE_PROPERTIES") ?: "${System.getProperty("user.home")}/hgcommlink-signing/keystore.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }
    fun signing(env: String, key: String): String? = System.getenv(env) ?: signingProps.getProperty(key)
    signingConfigs {
        val storeFile = signing("HGCOMMLINK_STORE_FILE", "storeFile")
        if (storeFile != null) create("release") {
            this.storeFile = File(storeFile)
            storePassword = signing("HGCOMMLINK_STORE_PASSWORD", "storePassword")
            keyAlias = signing("HGCOMMLINK_KEY_ALIAS", "keyAlias")
            keyPassword = signing("HGCOMMLINK_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes {
        // The debug build is a separate app (its own id, name, data and folder permission), so it installs beside the
        // release build instead of fighting it over the signature. Test a release any time without removing the debug app.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "hgCOMMLINK (debug)"
        }
        release {
            manifestPlaceholders["appLabel"] = "hgCOMMLINK"
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Two editions of one app (same package, same signing key, so they can replace each other):
    //  - github: the APK on GitHub Releases. Ships Chummer's game data inside.
    //  - play:   the Google Play bundle. Ships no game data; the player points the app at their own Chummer folder on first run.
    flavorDimensions += "edition"
    productFlavors {
        create("github") {
            dimension = "edition"
            buildConfigField("boolean", "BUNDLED_GAME_DATA", "true")
            buildConfigField("String", "EDITION", "\"github\"")
        }
        create("play") {
            dimension = "edition"
            buildConfigField("boolean", "BUNDLED_GAME_DATA", "false")
            buildConfigField("String", "EDITION", "\"play\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/DEPENDENCIES")
    }
}

// The Play edition's debug build is its own app (id ".debug.play"), so it can be tried on a phone that has the GitHub edition's debug build
// (its first-run game data screen only shows when the app has no data, and the GitHub debug build already unpacked it).
androidComponents {
    onVariants(selector().withBuildType("debug").withFlavor("edition" to "play")) { variant ->
        variant.applicationId.set("studio.missmercury.hgcommlink.debug.play")
        variant.manifestPlaceholders.put("appLabel", "hgCOMMLINK (play debug)")
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

// Chummer's game data ships unmodified inside the github edition's APK (the play edition has none); the app unpacks it on first run.
// Only the github edition needs the chummer5a checkout (-Pchummer.src).
val chummerSrc = providers.gradleProperty("chummer.src")
val copyChummerData = tasks.register<Copy>("copyChummerData") {
    from(File(chummerSrc.get(), "Chummer/data")) {
        include("*.xml")
        exclude("*.bak")
    }
    into(layout.buildDirectory.dir("generated/chummerAssets/chummer-data"))
}
val copyChummerCustomData = tasks.register<Copy>("copyChummerCustomData") {
    from(File(chummerSrc.get(), "Chummer/customdata")) { exclude("*.xsd") }
    into(layout.buildDirectory.dir("generated/chummerAssets/chummer-customdata"))
}
val copyChummerLang = tasks.register<Copy>("copyChummerLang") {
    from(File(chummerSrc.get(), "Chummer/lang")) { include("en-us.xml") }
    into(layout.buildDirectory.dir("generated/chummerAssets/chummer-lang"))
}
// The change log ships in both editions for the What's new sheet.
val copyChangelog = tasks.register<Copy>("copyChangelog") {
    // The list of Chummer data sets the app is checked against ships too, so it can say whether the player's data is one of them.
    from(rootProject.file("CHANGELOG.md"), rootProject.file("chummer-supported.txt"))
    into(layout.buildDirectory.dir("generated/changelogAssets"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/changelogAssets"))
android.sourceSets.getByName("github").assets.srcDir(layout.buildDirectory.dir("generated/chummerAssets"))
// Asset merging and lint read the same generated folders, so they must wait for them. Task names carry the flavor
// ("mergeGithubDebugAssets", "lintAnalyzePlayRelease"): only the github ones need Chummer's files.
tasks.matching { (it.name.startsWith("merge") && it.name.endsWith("Assets")) || it.name.contains("lint", ignoreCase = true) }.configureEach {
    dependsOn(copyChangelog)
    if (name.contains("Github")) dependsOn(copyChummerData, copyChummerCustomData, copyChummerLang)
}
