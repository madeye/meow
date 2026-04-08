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

// ---------------------------------------------------------------------------
// Go mihomo (libmihomo.so) build tasks.
//
// The Rust cargoBuild above still produces libmihomo_android_ffi.so which
// owns the netstack-smoltcp tun2socks layer. This section cross-compiles
// the upstream Go mihomo engine into a second native library via
// `go build -buildmode=c-shared`, one task per target ABI. Both libraries
// are merged into jniLibs alongside each other.
// ---------------------------------------------------------------------------

val goModuleDir = layout.projectDirectory.dir("src/main/go/mihomo-core")
val goOutputDir = layout.buildDirectory.dir("goJniLibs/android")

data class GoAbi(
    val gradleName: String,     // matches cargo targets: arm, arm64, x86, x86_64
    val jniDirName: String,     // matches android ABI dir: armeabi-v7a, ...
    val goArch: String,         // GOARCH
    val clangTriple: String,    // NDK clang binary prefix
    val extraLdFlags: List<String> = emptyList(),
)

val goAbis = listOf(
    GoAbi("arm", "armeabi-v7a", "arm", "armv7a-linux-androideabi21"),
    GoAbi(
        "arm64", "arm64-v8a", "arm64", "aarch64-linux-android21",
        extraLdFlags = listOf("-extldflags=-Wl,-z,max-page-size=16384"),
    ),
    GoAbi("x86", "x86", "386", "i686-linux-android21"),
    GoAbi("x86_64", "x86_64", "amd64", "x86_64-linux-android21"),
)

val goProfile = findProperty("GO_PROFILE")?.toString() ?: "debug"
val goTargetAbis = goAbis.filter { targetAbi == null || it.gradleName == targetAbi }

val goHostTag: String = when {
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "darwin-x86_64"
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "windows-x86_64"
    else -> "linux-x86_64"
}

val goBuildTasks = goTargetAbis.map { abi ->
    val taskName = "goBuild" + abi.gradleName.replaceFirstChar { it.uppercase() }
    tasks.register<Exec>(taskName) {
        group = "build"
        description = "Cross-compile libmihomo.so for ${abi.jniDirName}"

        val outDir = goOutputDir.get().dir(abi.jniDirName).asFile
        val outFile = File(outDir, "libmihomo.so")

        inputs.dir(goModuleDir)
        inputs.property("goProfile", goProfile)
        outputs.file(outFile)

        workingDir(goModuleDir.asFile)
        executable("go")

        val ldFlagParts = mutableListOf<String>()
        if (goProfile == "release") {
            ldFlagParts += "-s"
            ldFlagParts += "-w"
        }
        ldFlagParts += abi.extraLdFlags

        val buildArgs = mutableListOf("build", "-buildmode=c-shared")
        if (goProfile == "release") {
            buildArgs += "-trimpath"
        }
        if (ldFlagParts.isNotEmpty()) {
            buildArgs += "-ldflags=" + ldFlagParts.joinToString(" ")
        }
        buildArgs += listOf("-o", outFile.absolutePath, "./")

        args(buildArgs)

        // android.ndkDirectory is only safe to resolve at execution time,
        // not at task-configuration time (the extension may not be fully
        // initialised yet). Defer it via doFirst.
        doFirst {
            outDir.mkdirs()
            val ndkDir = android.ndkDirectory.absolutePath
            val cc = "$ndkDir/toolchains/llvm/prebuilt/$goHostTag/bin/${abi.clangTriple}-clang"
            environment("GOOS", "android")
            environment("GOARCH", abi.goArch)
            environment("CGO_ENABLED", "1")
            environment("CC", cc)
        }
    }
}

val goBuildAll = tasks.register("goBuild") {
    group = "build"
    description = "Build libmihomo.so for all selected ABIs"
    dependsOn(goBuildTasks)
}

tasks.whenTaskAdded {
    when (name) {
        "mergeDebugJniLibFolders", "mergeReleaseJniLibFolders" -> {
            dependsOn("cargoBuild")
            dependsOn("goBuild")
            inputs.dir(layout.buildDirectory.dir("rustJniLibs/android"))
            inputs.dir(goOutputDir)
        }
    }
}

android {
    sourceSets.getByName("main") {
        jniLibs.srcDir(goOutputDir)
    }
}

tasks.register<Exec>("cargoClean") {
    executable("cargo")
    args("clean")
    workingDir("$projectDir/${cargo.module}")
}
tasks.register<Delete>("goClean") {
    delete(goOutputDir)
}
tasks.named("clean").configure {
    dependsOn("cargoClean")
    dependsOn("goClean")
}

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
