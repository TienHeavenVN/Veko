package io.veko.browser.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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

        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val androidNs = "http://schemas.android.com/apk/res/android"

        val manifestDoc = dbf.newDocumentBuilder().parse(manifestFile)
        val appNode = manifestDoc.getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/backup_rules", appNode.getAttributeNS(androidNs, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", appNode.getAttributeNS(androidNs, "dataExtractionRules"))

        // Kiểm tra backup_rules.xml (Android 8–11): chỉ duy nhất file cài đặt người dùng được include
        val backupDoc = dbf.newDocumentBuilder().parse(backupRulesFile)
        val legacyIncludes = backupDoc.getElementsByTagName("include")
        assertEquals(1, legacyIncludes.length)
        val legacyInclude = legacyIncludes.item(0) as Element
        assertEquals("file", legacyInclude.getAttribute("domain"))
        assertEquals("datastore/veko_user_settings.preferences_pb", legacyInclude.getAttribute("path"))

        // Kiểm tra data_extraction_rules.xml (Android 12+): cloud-backup yêu cầu mã hóa và chỉ include cài đặt
        val extractDoc = dbf.newDocumentBuilder().parse(extractionRulesFile)
        val cloudBackup = extractDoc.getElementsByTagName("cloud-backup").item(0) as Element
        assertEquals("true", cloudBackup.getAttribute("disableIfNoEncryptionCapabilities"))

        val cloudIncludes = cloudBackup.getElementsByTagName("include")
        assertEquals(1, cloudIncludes.length)
        val cloudInc = cloudIncludes.item(0) as Element
        assertEquals("file", cloudInc.getAttribute("domain"))
        assertEquals("datastore/veko_user_settings.preferences_pb", cloudInc.getAttribute("path"))

        val transfer = extractDoc.getElementsByTagName("device-transfer").item(0) as Element
        val transferIncludes = transfer.getElementsByTagName("include")
        assertEquals(1, transferIncludes.length)
        val transferInc = transferIncludes.item(0) as Element
        assertEquals("file", transferInc.getAttribute("domain"))
        assertEquals("datastore/veko_user_settings.preferences_pb", transferInc.getAttribute("path"))

        // Đảm bảo không có bất kỳ dòng nào include database hay session store
        val combinedXml = backupRulesFile.readText() + "\n" + extractionRulesFile.readText()
        assertFalse(combinedXml.contains("<include domain=\"database\""))
        assertFalse(combinedXml.contains("veko_tabs_session_store"))
        assertFalse(combinedXml.contains("veko_browser.db"))
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

        // Bóc riêng khối buildTypes -> release { ... } theo đúng mức thụt lề để chắc chắn không dùng signingConfigs.debug
        val buildTypeReleaseRegex = Regex("""(?m)^        release\s*\{([\s\S]*?)^        \}""")
        val buildTypeReleaseMatch = buildTypeReleaseRegex.find(gradleText)
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

        // 2. Tách bạch ci-verify và release-official
        val jobs = root["jobs"] as? Map<String, Any?>
        assertNotNull(jobs)
        val ciJob = jobs!!["ci-verify"] as? Map<String, Any?>
        val releaseJob = jobs["release-official"] as? Map<String, Any?>
        assertNotNull("ci-verify job must exist", ciJob)
        assertNotNull("release-official job must exist", releaseJob)

        assertEquals(mapOf("contents" to "read"), ciJob!!["permissions"])
        assertEquals(mapOf("contents" to "write"), releaseJob!!["permissions"])

        // 3. Job CI/PR tuyệt đối không truy cập bất kỳ VEKO_KEYSTORE_* secret nào
        val ciSteps = ciJob["steps"] as List<Map<String, Any?>>
        val ciStepsSerialized = ciSteps.toString()
        assertFalse("CI/PR job must never access VEKO_KEYSTORE secrets", ciStepsSerialized.contains("VEKO_KEYSTORE"))

        // 4. Mọi GitHub Action uses: đều được pin theo SHA 40 ký tự thập lục phân
        val shaPinnedRegex = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$""")
        val allSteps = ciSteps + (releaseJob["steps"] as List<Map<String, Any?>>)
        val usesValues = allSteps.mapNotNull { it["uses"] as? String }
        assertTrue(usesValues.isNotEmpty())
        for (uses in usesValues) {
            assertTrue("Action '$uses' must be pinned to a full 40-char commit SHA", shaPinnedRegex.matches(uses))
        }

        // 5. Điều kiện phát hành chính thức: cần ci-verify pass + push tag v* + kiểm tra thuộc nhánh main
        val releaseIf = (releaseJob["if"] as? String).orEmpty()
        assertTrue(releaseIf.contains("github.event_name == 'push'"))
        assertTrue(releaseIf.contains("startsWith(github.ref, 'refs/tags/v')"))
        val needs = releaseJob["needs"] as? List<*>
        assertEquals(listOf("ci-verify"), needs)

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
