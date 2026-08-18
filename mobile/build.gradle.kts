import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
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

    // Compose lives only in the app module; :core stays UI-free so it does not
    // pay the Compose compiler cost.
    buildFeatures {
        compose = true
    }

    defaultConfig {
        applicationId = "io.github.madeye.meow"
    }

    // The app ships en + zh-rCN only. Without this, AndroidX drags in ~70
    // locales' worth of strings the app can never select.
    androidResources {
        localeFilters += listOf("en", "zh-rCN")
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

    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    implementation(libs.sora.editor)
    implementation(libs.sora.editor.textmate)

    testImplementation(libs.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
