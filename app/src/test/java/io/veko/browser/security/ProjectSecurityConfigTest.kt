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
            if (File(dir, "settings.gradle").isFile) return dir
        }
        var current: File? = File(".").canonicalFile
        while (current != null) {
            if (File(current, "settings.gradle").isFile && File(current, "app/build.gradle").isFile) {
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
            assertFalse(inc.attr("path").contains("veko_browser.db"))
        }
    }

    @Test
    fun `app build gradle separates debug, preview and official release signing without debug fallback`() {
        val gradleText = File(resolveRepoRoot(), "app/build.gradle").readText()

        // Không còn câu lệnh fallback signingConfigs.debug cho bản release
        assertFalse(
            "Official release must never fall back to signingConfigs.debug",
            gradleText.contains("signingConfigs.release : signingConfigs.debug"),
        )

        // Tách rõ 3 buildTypes: debug (.debug), preview (.preview) và release chính thức
        assertTrue(gradleText.contains("applicationIdSuffix \".debug\""))
        assertTrue(gradleText.contains("applicationIdSuffix \".preview\""))
        assertTrue(gradleText.contains("tasks.register(\"verifyReleaseSigningConfig\")"))

        // Kiểm tra đủ cả 4 thông tin khóa ký bắt buộc
        for (requiredVar in listOf(
            "VEKO_KEYSTORE_FILE",
            "VEKO_KEYSTORE_PASSWORD",
            "VEKO_KEY_ALIAS",
            "VEKO_KEY_PASSWORD",
        )) {
            assertTrue("app/build.gradle must check $requiredVar", gradleText.contains(requiredVar))
        }

        // Bóc riêng phần buildTypes { ... } để kiểm tra đúng khối buildTypes.release (tránh trùng signingConfigs.release)
        val buildTypesSection = gradleText.substringAfter("buildTypes {", missingDelimiterValue = "")
        assertTrue("Expected buildTypes section in app/build.gradle", buildTypesSection.isNotEmpty())
        val buildTypeReleaseRegex = Regex("""(?m)^        release\s*\{([\s\S]*?)^        \}""")
        val buildTypeReleaseMatch = buildTypeReleaseRegex.find(buildTypesSection)
        assertNotNull("Expected buildTypes.release block in app/build.gradle", buildTypeReleaseMatch)
        val buildTypeReleaseBody = buildTypeReleaseMatch!!.groupValues[1]
        assertFalse(
            "buildTypes.release must not use signingConfigs.debug",
            buildTypeReleaseBody.contains("signingConfigs.debug"),
        )
        assertTrue(
            "buildTypes.release must reference signingConfigs.release when configured",
            buildTypeReleaseBody.contains("signingConfigs.release"),
        )
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
}
