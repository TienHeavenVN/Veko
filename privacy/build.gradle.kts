import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

val adblockJniOutDir = file("build/generated/adblockJniLibs")

val buildHostAdblockNative by tasks.registering(Exec::class) {
    description = "Builds host x86_64 Rust adblock-rust + C++20 JNI bridge & native test suite"
    group = "privacy-native"
    workingDir = rootProject.projectDir
    commandLine("bash", "${rootProject.projectDir}/scripts/build-adblock-native.sh", "--mode=host")
    inputs.dir(file("src/main/cpp"))
    inputs.dir(file("src/test/cpp"))
    inputs.dir(file("rust/veko-adblock/src"))
    inputs.dir(file("rust/veko-adblock/include"))
    inputs.file(file("rust/veko-adblock/Cargo.toml"))
    inputs.file(file("rust/veko-adblock/Cargo.lock"))
    outputs.file(rootProject.file("out/host_x64/libveko_adblock_jni.so"))
    outputs.file(rootProject.file("out/host_x64/veko_adblock_native_tests"))
}

val buildAndroidAdblockNative by tasks.registering(Exec::class) {
    description = "Builds ABI-specific Android Rust adblock-rust + C++20 JNI libraries (16 KB ELF aligned)"
    group = "privacy-native"
    workingDir = rootProject.projectDir
    commandLine("bash", "${rootProject.projectDir}/scripts/build-adblock-native.sh", "--mode=android")
    inputs.dir(file("src/main/cpp"))
    inputs.dir(file("rust/veko-adblock/src"))
    inputs.dir(file("rust/veko-adblock/include"))
    inputs.file(file("rust/veko-adblock/Cargo.toml"))
    inputs.file(file("rust/veko-adblock/Cargo.lock"))
    outputs.dir(adblockJniOutDir)
}

android {
    namespace = "io.veko.browser.privacy.module"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(adblockJniOutDir)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            val hostNativeSo = rootProject.file("out/host_x64/libveko_adblock_jni.so")
            if (hostNativeSo.isFile) {
                it.systemProperty("veko.native.lib.path", hostNativeSo.absolutePath)
            }
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildAndroidAdblockNative)
}

dependencies {
    api(project(":storage"))
    api(project(":security"))

    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
