import com.android.build.api.variant.LibraryAndroidComponentsExtension
import java.net.URI
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("org.mozilla.rust-android-gradle.rust-android")
    kotlin("android")
    id("kotlin-parcelize")
}

setupCore()

val allAbis = mapOf("arm" to "armeabi-v7a", "arm64" to "arm64-v8a", "x86" to "x86", "x86_64" to "x86_64")
val targetAbi = findProperty("TARGET_ABI")?.toString()

// GeoX databases bundled as APK assets. Seeded into the engine home dir
// by MihomoInstance.copyGeoxAssets() on each start so the engine never
// needs to reach the network for these — meow-rs's pre-VPN auto-fetch is
// flaky on censored / metered links (github.com:443 can take 15s+ to
// TCP-connect from CN cellular, with no timeout in the library path).
//
// Only the two meow-rs actually consumes are bundled: country.mmdb (the
// GEOIP DB, required when any GEOIP rule fires) and GeoLite2-ASN.mmdb
// (GEOIP-ASN rules). meow-rs requires geosite in `.mrs` format which
// MetaCubeX does not publish as a release asset, so geosite is omitted —
// any user config with GEOSITE rules will need to ship its own .mrs file.
//
// Downloaded at build time via jsdelivr CDN (more reliable than
// github.com from CN) and cached under build/ to keep multi-MB binaries
// out of git. Wired via AGP's `addGeneratedSourceDirectory` so every
// assets consumer (merge, lint, package) picks up the dependency.
abstract class DownloadGeoxAssets : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val base = "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release"
        val files = mapOf(
            "country.mmdb" to "$base/country.mmdb",
            "GeoLite2-ASN.mmdb" to "$base/GeoLite2-ASN.mmdb",
        )
        val dir = outputDir.get().asFile.resolve("geox")
        dir.mkdirs()
        // Prune stale files from a previous task run with a different
        // `files` map, so they don't sneak into the APK as dead weight.
        dir.listFiles()?.forEach { existing ->
            if (existing.isFile && existing.name !in files) {
                logger.lifecycle("downloadGeoxAssets: pruning stale ${existing.name}")
                existing.delete()
            }
        }
        files.forEach { (name, url) ->
            val target = dir.resolve(name)
            if (target.exists() && target.length() > 0) return@forEach
            logger.lifecycle("downloadGeoxAssets: $url -> $target")
            URI(url).toURL().openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

val downloadGeoxAssets = tasks.register<DownloadGeoxAssets>("downloadGeoxAssets")

android {
    namespace = "io.github.madeye.meow.core"

    defaultConfig {
        consumerProguardFiles("proguard-rules.pro")

        ksp {
            arg("room.incremental", "true")
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    sourceSets.getByName("androidTest") {
        assets.setSrcDirs(assets.srcDirs + files("$projectDir/schemas"))
    }

    buildFeatures.aidl = true
}

// Register the download output as a generated assets source for every
// variant. AGP wires up the task dependency for merge / lint / package
// consumers so we don't need a separate `whenTaskAdded` hook.
extensions.getByType(LibraryAndroidComponentsExtension::class.java).onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(
        downloadGeoxAssets,
        DownloadGeoxAssets::outputDir,
    )
}

cargo {
    module = "src/main/rust/mihomo-android-ffi"
    libname = "mihomo_android_ffi"
    targets = if (targetAbi != null) listOf(targetAbi) else listOf("arm", "arm64", "x86", "x86_64")
    profile = findProperty("CARGO_PROFILE")?.toString() ?: currentFlavor
    exec = { spec, toolchain ->
        run {
            try {
                Runtime.getRuntime().exec(arrayOf("python3", "-V"))
                spec.environment("RUST_ANDROID_GRADLE_PYTHON_COMMAND", "python3")
            } catch (e: java.io.IOException) {
                try {
                    Runtime.getRuntime().exec(arrayOf("python", "-V"))
                    spec.environment("RUST_ANDROID_GRADLE_PYTHON_COMMAND", "python")
                } catch (e: java.io.IOException) {
                    throw GradleException("Python not found. Install Python to compile this project.")
                }
            }
            spec.environment("RUST_ANDROID_GRADLE_CC_LINK_ARG", "-Wl,-z,max-page-size=16384")
        }
    }
}

tasks.whenTaskAdded {
    when (name) {
        "mergeDebugJniLibFolders", "mergeReleaseJniLibFolders" -> {
            dependsOn("cargoBuild")
            inputs.dir(layout.buildDirectory.dir("rustJniLibs/android"))
        }
    }
}

tasks.register<Exec>("cargoClean") {
    executable("cargo")
    args("clean")
    workingDir("$projectDir/${cargo.module}")
}
tasks.named("clean").configure { dependsOn("cargoClean") }

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.androidx.lifecycle.livedata.core.ktx)
    api(libs.androidx.preference)
    api(libs.androidx.room.runtime)
    api(libs.androidx.work.multiprocess)
    api(libs.androidx.work.runtime.ktx)
    api(libs.kotlinx.coroutines.android)
    api(libs.material)
    api(libs.timber)
    coreLibraryDesugaring(libs.desugar)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit.ktx)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
}
