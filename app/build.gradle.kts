import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
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
val copyChummerCustomData = tasks.register<Copy>("copyChummerCustomData") {
    from(File(chummerSrc.get(), "Chummer/customdata")) { exclude("*.xsd") }
    into(layout.buildDirectory.dir("generated/chummerAssets/chummer-customdata"))
}
val copyChummerLang = tasks.register<Copy>("copyChummerLang") {
    from(File(chummerSrc.get(), "Chummer/lang")) { include("en-us.xml") }
    into(layout.buildDirectory.dir("generated/chummerAssets/chummer-lang"))
}
// The change log ships in the APK for the What's new sheet.
val copyChangelog = tasks.register<Copy>("copyChangelog") {
    // The list of Chummer data sets the app is checked against ships too, so it can say whether imported data is one of them.
    from(rootProject.file("CHANGELOG.md"), rootProject.file("chummer-supported.txt"))
    into(layout.buildDirectory.dir("generated/chummerAssets"))
}
// Which Chummer the bundled data comes from (its version and the pinned commit), for the About sheet.
val writeChummerVersion = tasks.register("writeChummerVersion") {
    val csproj = File(chummerSrc.get(), "Chummer/Chummer.csproj")
    val pin = rootProject.file("chummer-pin.txt")
    val out = layout.buildDirectory.file("generated/chummerAssets/chummer-version.txt")
    inputs.files(csproj, pin)
    outputs.file(out)
    doLast {
        val version = Regex("<VersionPrefix>([^<]+)</VersionPrefix>").find(csproj.readText())?.groupValues?.get(1)?.removeSuffix(".0")?.removeSuffix(".0") ?: "unknown"
        out.get().asFile.apply { parentFile.mkdirs(); writeText(listOf(version, pin.readText().trim().take(7)).joinToString(System.lineSeparator(), postfix = System.lineSeparator())) }
    }
}
// Only part of Chummer's data folder is bundled, so the app cannot fingerprint what it unpacks. This is the fingerprint of the whole folder
// (the same one tools/data_sets.py makes, and the one chummer-supported.txt lists), so the bundled data is recognised as the pinned data.
val writeDataFingerprint = tasks.register("writeDataFingerprint") {
    val dataDir = File(chummerSrc.get(), "Chummer/data")
    val out = layout.buildDirectory.file("generated/chummerAssets/chummer-data-fingerprint.txt")
    inputs.dir(dataDir)
    outputs.file(out)
    doLast {
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        val all = MessageDigest.getInstance("SHA-256")
        for (f in dataDir.listFiles { x -> x.isFile && x.name.endsWith(".xml") }!!.sortedBy { it.name }) {
            val clean = f.readBytes().filter { it != 13.toByte() }.toByteArray()
            all.update((f.name + "\n").toByteArray(Charsets.UTF_8))
            all.update((hex(MessageDigest.getInstance("SHA-256").digest(clean)) + "\n").toByteArray(Charsets.US_ASCII))
        }
        out.get().asFile.apply { parentFile.mkdirs(); writeText(hex(all.digest()) + "\n") }
    }
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/chummerAssets"))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(copyChummerData, copyChummerCustomData, copyChummerLang, copyChangelog, writeChummerVersion, writeDataFingerprint) }
// Lint reads the same generated assets, so it must wait for them too.
tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach { dependsOn(copyChummerData, copyChummerCustomData, copyChummerLang, copyChangelog, writeChummerVersion, writeDataFingerprint) }
