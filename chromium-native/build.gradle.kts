plugins {
    id("com.android.library")
}

val gnNativeOutDir = file("build/generated/gnJniLibs")

val buildHostChromiumNativeGn by tasks.registering(Exec::class) {
    description = "Builds host x86_64 C++20 JNI & Rust adblock-rust library + C++ tests via GN + Ninja"
    group = "chromium-native"
    workingDir = rootProject.projectDir
    commandLine("bash", "${rootProject.projectDir}/scripts/build-native-gn.sh", "--mode=host")
    inputs.dir(file("src/main/cpp"))
    inputs.dir(file("src/test/cpp"))
    inputs.dir(file("chromium_src"))
    inputs.dir(rootProject.file("privacy/rust/veko-adblock/src"))
    inputs.file(rootProject.file("privacy/rust/veko-adblock/Cargo.toml"))
    inputs.file(rootProject.file("privacy/rust/veko-adblock/Cargo.lock"))
    inputs.file(rootProject.file("BUILD.gn"))
    inputs.dir(rootProject.file("gn"))
    outputs.file(rootProject.file("out/host_x64/libveko_chromium_native.so"))
    outputs.file(rootProject.file("out/host_x64/veko_chromium_native_tests"))
}

val buildAndroidChromiumNativeGn by tasks.registering(Exec::class) {
    description = "Builds ABI-specific Android C++20 JNI & Rust adblock-rust libraries via GN + Ninja"
    group = "chromium-native"
    workingDir = rootProject.projectDir
    commandLine("bash", "${rootProject.projectDir}/scripts/build-native-gn.sh", "--mode=android")
    inputs.dir(file("src/main/cpp"))
    inputs.dir(file("chromium_src"))
    inputs.dir(rootProject.file("privacy/rust/veko-adblock/src"))
    inputs.file(rootProject.file("privacy/rust/veko-adblock/Cargo.toml"))
    inputs.file(rootProject.file("privacy/rust/veko-adblock/Cargo.lock"))
    inputs.file(rootProject.file("BUILD.gn"))
    inputs.dir(rootProject.file("gn"))
    outputs.dir(gnNativeOutDir)
}

android {
    namespace = "io.veko.browser.chromium.nativebridge"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(gnNativeOutDir)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildAndroidChromiumNativeGn)
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
