import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

setupApp()

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String): String? =
    localProps.getProperty(key) ?: System.getenv(key)

android {
    namespace = "io.github.madeye.meow"

    defaultConfig {
        applicationId = "io.github.akari.meow"

        // Overlay (uncommitted): restrict packaged native libs to the requested
        // ABI so a single-arch build does not ship Flutter's multi-ABI
        // libflutter.so/libapp.so as dead weight.
        val targetAbi = findProperty("TARGET_ABI")?.toString()
        if (targetAbi != null) {
            val abiMap = mapOf(
                "arm" to "armeabi-v7a",
                "arm64" to "arm64-v8a",
                "x86" to "x86",
                "x86_64" to "x86_64",
            )
            ndk { abiFilters += abiMap[targetAbi]!! }
        }
    }

    // Product flavors: "akari" overrides icon resources (src/akari/res)
    // and sets applicationId. "upstream" uses defaults from src/main.
    // Namespace stays io.github.madeye.meow for both flavors -- the Rust JNI
    // symbols (Java_io_github_madeye_meow_core_*) are bound to the namespace.
    flavorDimensions += "brand"
    productFlavors {
        create("upstream") { dimension = "brand" }
        create("akari") {
            dimension = "brand"
        }
    }

    val keystorePath = prop("KEYSTORE_PATH")
    val keystoreFile = keystorePath?.let { File(it) }

    if (keystoreFile != null && keystoreFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = prop("KEYSTORE_PASSWORD")
                keyAlias = prop("KEY_ALIAS")
                keyPassword = prop("KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
            getByName("playRelease") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(project(":flutter"))

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    implementation(libs.sora.editor)
    implementation(libs.sora.editor.textmate)
}
