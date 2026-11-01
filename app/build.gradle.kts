import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Browser Engine duy nhất của Veko: Mozilla GeckoView (Gecko WebRender + SpiderMonkey);
// hỗ trợ ghi đè sang artifact/version biên dịch từ mã nguồn Firefox cục bộ khi đóng gói F-Droid.
val geckoviewArtifact = providers.gradleProperty("veko.geckoview.artifact")
    .orElse("geckoview")
    .get()
val geckoviewVersion = providers.gradleProperty("veko.geckoview.version")
    .orElse("150.0.20260511200624")
    .get()

// Chế độ tường minh tạo gói Release chưa ký (`*-release-unsigned.apk`) phục vụ quy trình F-Droid tự ký:
// Mặc định là `false` để bảo toàn chốt chặn fail-fast của quy trình phát hành do nhà phát triển ký.
val isFdroidUnsignedRelease = providers.gradleProperty("fdroidUnsignedRelease")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

val releaseKeystorePath = System.getenv("VEKO_KEYSTORE_FILE")?.trim()
val releaseStorePassword = System.getenv("VEKO_KEYSTORE_PASSWORD")?.trim()
val releaseKeyAlias = System.getenv("VEKO_KEY_ALIAS")?.trim()
val releaseKeyPassword = System.getenv("VEKO_KEY_PASSWORD")?.trim()
val releaseKeystoreFile = if (!releaseKeystorePath.isNullOrEmpty()) file(releaseKeystorePath) else null
val hasCompleteReleaseSigning = !isFdroidUnsignedRelease &&
    releaseKeystoreFile != null &&
    releaseKeystoreFile.isFile &&
    releaseKeystoreFile.length() > 0L &&
    !releaseStorePassword.isNullOrEmpty() &&
    !releaseKeyAlias.isNullOrEmpty() &&
    !releaseKeyPassword.isNullOrEmpty()

android {
    namespace = "io.veko.browser"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.veko.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.4.0-privacy-shield"
        manifestPlaceholders["appLabel"] = "Veko"
        buildConfigField("String", "GECKOVIEW_VERSION", "\"$geckoviewVersion\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (hasCompleteReleaseSigning) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Veko Debug"
            signingConfig = signingConfigs.getByName("debug")
        }

        // Bản chính thức: TUYỆT ĐỐI KHÔNG fallback về debug key; thiếu khóa ký sẽ dừng build ngay lập tức
        getByName("release") {
            manifestPlaceholders["appLabel"] = "Veko"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasCompleteReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        // Bản thử nghiệm tối ưu R8 dành cho kiểm thử CI/PR: định danh tách biệt (.preview + nhãn "Veko Preview")
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            manifestPlaceholders["appLabel"] = "Veko Preview"
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += "NotificationPermission"
    }

    testOptions {
        unitTests.all {
            it.systemProperty("veko.repo.root", rootProject.projectDir.absolutePath)
            val hostNativeSo = rootProject.file("out/host_x64/libveko_adblock_jni.so")
            if (hostNativeSo.isFile) {
                it.systemProperty("veko.native.lib.path", hostNativeSo.absolutePath)
            }
        }
    }

    bundle {
        abi {
            enableSplit = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    dependenciesInfo {
        // Tuân thủ F-Droid & Reproducible Builds: không nhúng khối metadata phụ thuộc mã hóa bằng khóa Google Play (0x504B4453)
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md,LICENSE-notice.md}"
        }
    }
}

// Chốt chặn bắt buộc cho luồng phát hành của nhà phát triển: thất bại rõ ràng nếu thiếu khóa ký riêng
tasks.register("verifyReleaseSigningConfig") {
    group = "verification"
    description = "Validates that official release signing credentials and keystore file are present."
    doLast {
        val missing = mutableListOf<String>()
        val ksPath = System.getenv("VEKO_KEYSTORE_FILE")?.trim()
        if (ksPath.isNullOrEmpty()) {
            missing += "VEKO_KEYSTORE_FILE (decoded from VEKO_KEYSTORE_BASE64)"
        } else {
            val ksFile = file(ksPath)
            if (!ksFile.isFile || ksFile.length() <= 0L) {
                missing += "VEKO_KEYSTORE_FILE (keystore file missing or empty)"
            }
        }
        if (System.getenv("VEKO_KEYSTORE_PASSWORD")?.trim().isNullOrEmpty()) {
            missing += "VEKO_KEYSTORE_PASSWORD"
        }
        if (System.getenv("VEKO_KEY_ALIAS")?.trim().isNullOrEmpty()) {
            missing += "VEKO_KEY_ALIAS"
        }
        if (System.getenv("VEKO_KEY_PASSWORD")?.trim().isNullOrEmpty()) {
            missing += "VEKO_KEY_PASSWORD"
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Official Release signing is not configured. Missing/invalid: " +
                    missing.joinToString(", ") +
                    ". Never fall back to debug signing for official releases; use ':app:assemblePreview' for trial R8 builds " +
                    "or '-PfdroidUnsignedRelease=true' exclusively for F-Droid unsigned source builds.",
            )
        }
    }
}

// Chốt chặn riêng cho chế độ F-Droid (`-PfdroidUnsignedRelease=true`): đảm bảo không trộn lẫn khóa cá nhân của lập trình viên
tasks.register("verifyFdroidUnsignedReleaseMode") {
    group = "verification"
    description = "Validates that F-Droid unsigned release mode does not attach developer signing credentials."
    doLast {
        val leakedVars = listOf(
            "VEKO_KEYSTORE_FILE",
            "VEKO_KEYSTORE_PASSWORD",
            "VEKO_KEY_ALIAS",
            "VEKO_KEY_PASSWORD",
        ).filter { !System.getenv(it)?.trim().isNullOrEmpty() }
        if (leakedVars.isNotEmpty()) {
            throw GradleException(
                "fdroidUnsignedRelease=true forbids developer keystore variables (${leakedVars.joinToString(", ")}). " +
                    "Unset VEKO_KEYSTORE_* to build clean unsigned APKs for F-Droid.",
            )
        }
    }
}

tasks.configureEach {
    val taskName = name
    val isReleaseBundleTask =
        !taskName.contains("Preview") &&
            !taskName.contains("UnitTest") &&
            !taskName.contains("AndroidTest") &&
            (taskName == "bundleRelease" || (taskName.startsWith("bundle") && taskName.endsWith("Release")))
    val isOfficialReleaseApkPackaging =
        !taskName.contains("Preview") &&
            !taskName.contains("UnitTest") &&
            !taskName.contains("AndroidTest") &&
            (taskName == "assembleRelease" ||
                (taskName.startsWith("assemble") && taskName.endsWith("Release")) ||
                (taskName.startsWith("package") && taskName.endsWith("Release")) ||
                (taskName.startsWith("validateSigning") && taskName.endsWith("Release")))
    if (isReleaseBundleTask) {
        // Gói AAB luôn yêu cầu khóa ký chính thức (F-Droid chỉ sử dụng APK unsigned)
        dependsOn("verifyReleaseSigningConfig")
    } else if (isOfficialReleaseApkPackaging) {
        if (isFdroidUnsignedRelease) {
            dependsOn("verifyFdroidUnsignedReleaseMode")
        } else {
            dependsOn("verifyReleaseSigningConfig")
        }
    }
    if (taskName == "testDebugUnitTest") {
        dependsOn(":testing:testDebugUnitTest")
    }
}

dependencies {
    implementation(project(":browser-core"))
    implementation(project(":privacy"))
    implementation(project(":downloads"))
    implementation(project(":storage"))
    implementation(project(":security"))

    // GeckoView Engine (mặc định từ Mozilla Maven; có thể ghi đè sang artifact build từ nguồn Firefox qua -Pveko.geckoview.*)
    implementation("org.mozilla.geckoview:$geckoviewArtifact:$geckoviewVersion")

    // Jetpack Compose BOM + Material 3 + Coil 3 (chế độ local/memory an toàn quyền riêng tư)
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Single-Activity + Lifecycle + ViewModel Compose
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Coroutines + Flow
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Hilt Dependency Injection (KSP)
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-android-compiler:2.59.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    // Room Runtime cho DI DatabaseModule
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")

    // Kiểm thử đơn vị & hồi quy trên JVM
    testImplementation(project(":testing"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.snakeyaml:snakeyaml-engine:3.0.1")
    testImplementation("org.jsoup:jsoup:1.18.3")

    // Kiểm thử tích hợp thực tế trên Android Emulator (Native JNI + Rust Adblock + Compose + Room + Engine)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
