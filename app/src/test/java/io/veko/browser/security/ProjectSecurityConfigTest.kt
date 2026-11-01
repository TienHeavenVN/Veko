package io.veko.browser.security

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

class ProjectSecurityConfigTest {

    private fun resolveRepoRoot(): File {
        val prop = System.getProperty("veko.repo.root")
        if (!prop.isNullOrBlank()) {
            val dir = File(prop)
            if (File(dir, "settings.gradle.kts").isFile) return dir
        }
        var current: File? = File(".").canonicalFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile && File(current, "app/build.gradle.kts").isFile) {
                return current
            }
            current = current.parentFile
        }
        error("Could not locate repository root directory")
    }

    @Test
    fun `AndroidManifest and XML backup rules enforce strict settings-only allowlist`() {
        val root = resolveRepoRoot()
        val manifestFile = File(root, "app/src/main/AndroidManifest.xml")
        val backupRulesFile = File(root, "app/src/main/res/xml/backup_rules.xml")
        val extractionRulesFile = File(root, "app/src/main/res/xml/data_extraction_rules.xml")

        assertTrue(manifestFile.isFile)
        assertTrue(backupRulesFile.isFile)
        assertTrue(extractionRulesFile.isFile)

        // Dùng Jsoup XML Parser thuần Java để tránh stub javax.xml trong android.jar khi chạy JVM unit test
        val manifestDoc = Jsoup.parse(manifestFile.readText(), "", Parser.xmlParser())
        val appNode = manifestDoc.selectFirst("manifest > application")
        assertNotNull(appNode)
        assertEquals("@xml/backup_rules", appNode!!.attr("android:fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", appNode.attr("android:dataExtractionRules"))

        // Kiểm tra backup_rules.xml (Android 8–11): chỉ duy nhất file cài đặt người dùng được include
        val backupDoc = Jsoup.parse(backupRulesFile.readText(), "", Parser.xmlParser())
        val legacyIncludes = backupDoc.select("full-backup-content > include")
        assertEquals(1, legacyIncludes.size)
        assertEquals("file", legacyIncludes.single().attr("domain"))
        assertEquals("datastore/veko_user_settings.preferences_pb", legacyIncludes.single().attr("path"))
        assertEquals(0, backupDoc.select("exclude").size)

        // Kiểm tra data_extraction_rules.xml (Android 12+): cloud-backup yêu cầu mã hóa và chỉ include cài đặt
        val extractDoc = Jsoup.parse(extractionRulesFile.readText(), "", Parser.xmlParser())
        val cloudBackup = extractDoc.selectFirst("data-extraction-rules > cloud-backup")
        assertNotNull(cloudBackup)
        assertEquals("true", cloudBackup!!.attr("disableIfNoEncryptionCapabilities"))

        val cloudIncludes = extractDoc.select("data-extraction-rules > cloud-backup > include")
        assertEquals(1, cloudIncludes.size)
        assertEquals("file", cloudIncludes.single().attr("domain"))
        assertEquals("datastore/veko_user_settings.preferences_pb", cloudIncludes.single().attr("path"))

        val transferIncludes = extractDoc.select("data-extraction-rules > device-transfer > include")
        assertEquals(1, transferIncludes.size)
        assertEquals("file", transferIncludes.single().attr("domain"))
        assertEquals("datastore/veko_user_settings.preferences_pb", transferIncludes.single().attr("path"))

        // Đảm bảo tập hợp mọi thẻ <include> thực thi chỉ chứa duy nhất file cài đặt không nhạy cảm
        val allIncludes = legacyIncludes + cloudIncludes + transferIncludes
        assertEquals(3, allIncludes.size)
        for (inc in allIncludes) {
            assertEquals("file", inc.attr("domain"))
            assertEquals("datastore/veko_user_settings.preferences_pb", inc.attr("path"))
            assertFalse(inc.attr("path").contains("veko_tabs_session_store"))
            assertFalse(inc.attr("path").contains("veko_site_permissions"))
            assertFalse(inc.attr("path").contains("veko_browser.db"))
        }

        // Xác minh FileProvider không bị exported ra ngoài và phần cứng camera/micro/vị trí đều là tùy chọn (required="false")
        val providerNode = manifestDoc.selectFirst("manifest > application > provider")
        assertNotNull("FileProvider must be declared in AndroidManifest.xml", providerNode)
        assertEquals("false", providerNode!!.attr("android:exported"))
        assertEquals("true", providerNode.attr("android:grantUriPermissions"))

        val featureNodes = manifestDoc.select("manifest > uses-feature")
        assertTrue("Optional hardware features must be declared", featureNodes.isNotEmpty())
        for (feature in featureNodes) {
            assertEquals(
                "Hardware feature ${feature.attr("android:name")} must not block tablets/devices lacking it",
                "false",
                feature.attr("android:required"),
            )
        }
    }

    @Test
    fun `app build gradle separates debug, preview and official release signing without debug fallback`() {
        val root = resolveRepoRoot()
        val gradleText = File(root, "app/build.gradle.kts").readText()
        val settingsText = File(root, "settings.gradle.kts").readText()

        // Xác minh đủ 7 module kiến trúc GeckoView-only trong settings.gradle.kts (không còn module phụ thuộc engine khác)
        for (requiredModule in listOf(
            ":app",
            ":browser-core",
            ":privacy",
            ":downloads",
            ":storage",
            ":security",
            ":testing",
        )) {
            assertTrue("Missing module $requiredModule in settings.gradle.kts", settingsText.contains("\"$requiredModule\""))
        }
        assertFalse("Removed module must not appear in settings.gradle.kts", settingsText.contains(":chromium"))

        // Không còn câu lệnh fallback signingConfigs.debug cho bản release
        assertFalse(
            "Official release must never fall back to debug signing",
            gradleText.contains("signingConfigs.getByName(\"release\") : signingConfigs.getByName(\"debug\")"),
        )

        // Tách rõ 3 buildTypes: debug (.debug), preview (.preview) và release chính thức
        assertTrue(gradleText.contains("applicationIdSuffix = \".debug\""))
        assertTrue(gradleText.contains("applicationIdSuffix = \".preview\""))
        assertTrue(gradleText.contains("tasks.register(\"verifyReleaseSigningConfig\")"))

        // Kiểm tra đủ cả 4 thông tin khóa ký bắt buộc
        for (requiredVar in listOf(
            "VEKO_KEYSTORE_FILE",
            "VEKO_KEYSTORE_PASSWORD",
            "VEKO_KEY_ALIAS",
            "VEKO_KEY_PASSWORD",
        )) {
            assertTrue("app/build.gradle.kts must check $requiredVar", gradleText.contains(requiredVar))
        }

        // Bóc riêng phần buildTypes { ... } để kiểm tra đúng khối getByName("release")
        val buildTypesSection = gradleText.substringAfter("buildTypes {", missingDelimiterValue = "")
        assertTrue("Expected buildTypes section in app/build.gradle.kts", buildTypesSection.isNotEmpty())
        val buildTypeReleaseRegex = Regex("""(?m)^        getByName\("release"\)\s*\{([\s\S]*?)^        \}""")
        val buildTypeReleaseMatch = buildTypeReleaseRegex.find(buildTypesSection)
        assertNotNull("Expected getByName(\"release\") block in app/build.gradle.kts", buildTypeReleaseMatch)
        val buildTypeReleaseBody = buildTypeReleaseMatch!!.groupValues[1]
        assertFalse(
            "buildTypes.release must not use debug signingConfig",
            buildTypeReleaseBody.contains("getByName(\"debug\")"),
        )
        assertTrue(
            "buildTypes.release must reference release signingConfig when configured",
            buildTypeReleaseBody.contains("getByName(\"release\")"),
        )

        // Xác minh tách biệt rõ chế độ unsigned cho F-Droid và tắt khối DependenciesInfo mã hóa của Google Play
        assertTrue(
            "app/build.gradle.kts must support explicit -PfdroidUnsignedRelease=true mode",
            gradleText.contains("providers.gradleProperty(\"fdroidUnsignedRelease\")") &&
                gradleText.contains("tasks.register(\"verifyFdroidUnsignedReleaseMode\")"),
        )
        assertTrue(
            "app/build.gradle.kts must disable Google Play encrypted dependenciesInfo block",
            gradleText.contains("includeInApk = false") && gradleText.contains("includeInBundle = false"),
        )

        // Xác minh versionCode hiện tại đã được tăng lớn hơn tag v0.2.0 (versionCode = 1)
        val versionCodeMatch = Regex("""versionCode\s*=\s*(\d+)""").find(gradleText)
        assertNotNull("Expected explicit versionCode in app/build.gradle.kts", versionCodeMatch)
        val currentVersionCode = versionCodeMatch!!.groupValues[1].toInt()
        assertTrue(
            "versionCode ($currentVersionCode) must be > 1 (tag v0.2.0)",
            currentVersionCode >= 2,
        )

        // Xác minh tệp LICENSE (MPL-2.0) và Cargo.lock đã có mặt để phục vụ kiểm định nguồn mở / build ngoại tuyến
        val licenseFile = File(root, "LICENSE")
        assertTrue("Root LICENSE file must exist", licenseFile.isFile)
        assertTrue(
            "Root LICENSE must be Mozilla Public License Version 2.0",
            licenseFile.readText().contains("Mozilla Public License Version 2.0"),
        )

        val cargoLockFile = File(root, "privacy/rust/veko-adblock/Cargo.lock")
        assertTrue("privacy/rust/veko-adblock/Cargo.lock must be committed for reproducible offline builds", cargoLockFile.isFile)
        val cargoLockText = cargoLockFile.readText()
        assertTrue(cargoLockText.contains("name = \"adblock\"") && cargoLockText.contains("version = \"0.13.4\""))
        assertTrue(File(root, "scripts/vendor-rust-deps.sh").isFile)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `GitHub Actions workflow enforces least privilege, pinned SHAs, PR keystore isolation and main-lineage release guard`() {
        val workflowFile = File(resolveRepoRoot(), ".github/workflows/build-apk.yml")
        assertTrue(workflowFile.isFile)
        val rawYaml = workflowFile.readText()

        val loader = Load(LoadSettings.builder().build())
        val root = loader.loadFromString(rawYaml) as Map<String, Any?>

        // 1. Quyền mặc định toàn workflow phải là contents: read
        val topPermissions = root["permissions"] as? Map<String, Any?>
        assertNotNull(topPermissions)
        assertEquals(mapOf("contents" to "read"), topPermissions)

        // 2. Tách bạch ci-verify, emulator-test và release-official
        val jobs = root["jobs"] as? Map<String, Any?>
        assertNotNull(jobs)
        val ciJob = jobs!!["ci-verify"] as? Map<String, Any?>
        val emulatorJob = jobs["emulator-test"] as? Map<String, Any?>
        val releaseJob = jobs["release-official"] as? Map<String, Any?>
        assertNotNull("ci-verify job must exist", ciJob)
        assertNotNull("emulator-test job must exist", emulatorJob)
        assertNotNull("release-official job must exist", releaseJob)

        assertEquals(mapOf("contents" to "read"), ciJob!!["permissions"])
        assertEquals(mapOf("contents" to "read"), emulatorJob!!["permissions"])
        assertEquals(mapOf("contents" to "write"), releaseJob!!["permissions"])

        // 3. Các job CI/PR (ci-verify & emulator-test) tuyệt đối không truy cập bất kỳ VEKO_KEYSTORE_* secret nào
        val ciSteps = ciJob["steps"] as List<Map<String, Any?>>
        val emulatorSteps = emulatorJob["steps"] as List<Map<String, Any?>>
        val nonReleaseStepsSerialized = (ciSteps + emulatorSteps).toString()
        assertFalse("CI/PR jobs must never access VEKO_KEYSTORE secrets", nonReleaseStepsSerialized.contains("VEKO_KEYSTORE"))

        // Đảm bảo job emulator-test tích hợp reactivecircus/android-emulator-runner với kiến trúc x86_64 và KVM
        val emulatorStepsSerialized = emulatorSteps.toString()
        assertTrue(
            "emulator-test job must use reactivecircus/android-emulator-runner",
            emulatorSteps.any { (it["uses"] as? String)?.startsWith("reactivecircus/android-emulator-runner@") == true },
        )
        assertTrue("emulator-test job must run x86_64 emulator", emulatorStepsSerialized.contains("arch=x86_64"))

        // 4. Mọi GitHub Action uses: trên toàn bộ các job đều được pin theo SHA 40 ký tự thập lục phân
        val shaPinnedRegex = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$""")
        val allSteps = ciSteps + emulatorSteps + (releaseJob["steps"] as List<Map<String, Any?>>)
        val usesValues = allSteps.mapNotNull { it["uses"] as? String }
        assertTrue(usesValues.isNotEmpty())
        for (uses in usesValues) {
            assertTrue("Action '$uses' must be pinned to a full 40-char commit SHA", shaPinnedRegex.matches(uses))
        }

        // 5. Điều kiện phát hành chính thức: cần cả ci-verify và emulator-test pass + push tag v* + kiểm tra thuộc nhánh main
        val releaseIf = (releaseJob["if"] as? String).orEmpty()
        assertTrue(releaseIf.contains("github.event_name == 'push'"))
        assertTrue(releaseIf.contains("startsWith(github.ref, 'refs/tags/v')"))
        val needs = releaseJob["needs"] as? List<*>
        assertEquals(listOf("ci-verify", "emulator-test"), needs)

        val releaseStepsSerialized = (releaseJob["steps"] as List<Map<String, Any?>>).toString()
        assertTrue(
            "Release job must verify tag is an ancestor of origin/main",
            releaseStepsSerialized.contains("merge-base --is-ancestor HEAD refs/remotes/origin/main"),
        )
        assertTrue(
            "Release job must verify apksigner and reject Android Debug key",
            releaseStepsSerialized.contains("CN=Android Debug") && releaseStepsSerialized.contains("apksigner"),
        )
        assertTrue(
            "Release job must verify SHA-256 checksums before publishing",
            releaseStepsSerialized.contains("sha256sum --check --strict SHA256SUMS.txt"),
        )
        assertTrue(
            "Secret handling steps must enforce set +x",
            releaseStepsSerialized.contains("set +x"),
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `Official website workflow enforces least privilege, pinned SHAs and opt-in Pages deployment`() {
        val workflowFile = File(resolveRepoRoot(), ".github/workflows/website.yml")
        assertTrue(workflowFile.isFile)
        val rawYaml = workflowFile.readText()

        val loader = Load(LoadSettings.builder().build())
        val root = loader.loadFromString(rawYaml) as Map<String, Any?>

        assertEquals(mapOf("contents" to "read"), root["permissions"])

        val jobs = root["jobs"] as? Map<String, Any?>
        assertNotNull(jobs)
        val buildJob = jobs!!["build-website"] as? Map<String, Any?>
        val deployJob = jobs["deploy-pages"] as? Map<String, Any?>
        assertNotNull("build-website job must exist", buildJob)
        assertNotNull("deploy-pages job must exist", deployJob)

        assertEquals(mapOf("contents" to "read"), buildJob!!["permissions"])

        val shaPinnedRegex = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$""")
        val allSteps = (buildJob["steps"] as List<Map<String, Any?>>) + (deployJob!!["steps"] as List<Map<String, Any?>>)
        val usesValues = allSteps.mapNotNull { it["uses"] as? String }
        assertTrue(usesValues.isNotEmpty())
        for (uses in usesValues) {
            assertTrue("Website action '$uses' must be pinned to a full 40-char commit SHA", shaPinnedRegex.matches(uses))
        }

        val deployIf = (deployJob["if"] as? String).orEmpty()
        assertTrue(
            "deploy-pages must only run on main when explicitly enabled",
            deployIf.contains("refs/heads/main") && deployIf.contains("VEKO_ENABLE_PAGES_DEPLOY"),
        )
    }

    @Test
    fun `GeckoView-only architecture bans WebView and non-Gecko engines across all main source modules`() {
        val root = resolveRepoRoot()
        assertFalse(
            "Legacy upstream workflow must be removed",
            File(root, ".github/workflows/chromium-upstream-build.yml").exists(),
        )
        assertFalse("Legacy GN build file must be removed", File(root, "BUILD.gn").exists())

        val nativeBuildScript = File(root, "scripts/build-adblock-native.sh")
        assertTrue("scripts/build-adblock-native.sh must exist", nativeBuildScript.isFile)
        val nativeScriptText = nativeBuildScript.readText()
        assertTrue(
            "Android native AdBlock JNI library must enforce 16 KB ELF page alignment",
            nativeScriptText.contains("-Wl,-z,max-page-size=16384") &&
                nativeScriptText.contains("libveko_adblock_jni.so"),
        )

        val moduleDirs = listOf("app", "browser-core", "privacy", "downloads", "storage", "security", "testing")
        for (mod in moduleDirs) {
            val mainSrc = File(root, "$mod/src/main")
            if (!mainSrc.isDirectory) continue
            mainSrc.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java" || it.extension == "cc" || it.extension == "h") }
                .forEach { srcFile ->
                    val content = srcFile.readText()
                    assertFalse(
                        "Forbidden android.webkit.WebView reference in ${srcFile.relativeTo(root)}",
                        content.contains("android.webkit.WebView"),
                    )
                    assertFalse(
                        "Forbidden org.chromium.* reference in ${srcFile.relativeTo(root)}",
                        content.contains("org.chromium."),
                    )
                }
        }
    }
}
