import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.BaseExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByName
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

private val Project.android get() = extensions.getByName<BaseExtension>("android")
private val BaseExtension.lint get() = (this as CommonExtension<*, *, *, *, *, *>).lint

val Project.currentFlavor get() = gradle.startParameter.taskNames.let { tasks ->
    when {
        tasks.any { it.contains("Release", ignoreCase = true) } -> "release"
        tasks.any { it.contains("Debug", ignoreCase = true) } -> "debug"
        else -> "debug".also {
            println("Warning: No match found for $tasks")
        }
    }
}

fun Project.setupCommon() {
    // JVM 17 — required by sora-editor (and matches the Flutter add-to-app
    // submodule which already builds at 17). Bumped from 11 when adding
    // sora-editor; the inline functions in sora-editor are compiled at 17.
    val javaVersion = JavaVersion.VERSION_17
    android.apply {
        compileSdkVersion(36)
        defaultConfig {
            minSdk = 24
            targetSdk = 36
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
        lint.apply {
            warning += "ExtraTranslation"
            warning += "ImpliedQuantity"
            informational += "MissingQuantity"
            informational += "MissingTranslation"
            informational += "QueryAllPackagesPermission"
            abortOnError = true
            warningsAsErrors = false
        }
    }
    extensions.getByName<KotlinAndroidProjectExtension>("kotlin").compilerOptions.jvmTarget
        .set(JvmTarget.fromTarget(javaVersion.toString()))
}

fun Project.setupCore() {
    setupCommon()
    android.apply {
        defaultConfig {
            versionCode = 1000018
            versionName = "1.0.2"
        }
        compileOptions.isCoreLibraryDesugaringEnabled = true
        lint.apply {
            warning += "RestrictedApi"
        }
        buildFeatures.buildConfig = true
    }
}

fun Project.setupApp() {
    setupCore()

    android.apply {
        // Analytics opt-in is controlled per buildType via this manifest
        // placeholder; the playRelease buildType (used for Google Play uploads)
        // overrides it to "false" so Firebase Analytics never collects.
        defaultConfig.manifestPlaceholders["analyticsEnabled"] = "true"

        buildTypes {
            getByName("debug") {
                isPseudoLocalesEnabled = true
                packagingOptions.doNotStrip("**/libmihomo_android_ffi.so")
            }
            getByName("release") {
                isShrinkResources = true
                isMinifyEnabled = true
                proguardFile(getDefaultProguardFile("proguard-android.txt"))
                proguardFile("proguard-rules.pro")
            }
            // Google Play distribution build — same as release but with
            // Firebase Analytics collection disabled at the manifest level.
            create("playRelease") {
                initWith(getByName("release"))
                matchingFallbacks += "release"
                manifestPlaceholders["analyticsEnabled"] = "false"
            }
        }
        packagingOptions.jniLibs.useLegacyPackaging = true
        splits.abi {
            isEnable = false
        }
    }

    dependencies.add("implementation", project(":core"))
}
