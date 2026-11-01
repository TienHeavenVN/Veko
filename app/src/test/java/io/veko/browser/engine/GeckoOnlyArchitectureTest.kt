package io.veko.browser.engine

import android.content.ContextWrapper
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.engine.gecko.GeckoBrowserEngine
import io.veko.browser.engine.gecko.GeckoSessionManager
import io.veko.browser.engine.gecko.GeckoSettings
import io.veko.browser.tab.BrowserTabsUiState
import io.veko.browser.tab.GeckoEngineSessionBridge
import io.veko.browser.tab.SessionPersistenceCodec
import io.veko.browser.tab.Tab
import io.veko.browser.tab.TabSessionState
import io.veko.browser.tab.WebDialogPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.ContentBlocking
import java.io.File
import java.nio.file.Files

/**
 * Kiểm thử đơn vị kiến trúc GeckoView-only của Veko Browser:
 * 1) `EngineType.entries` chỉ duy trì duy nhất `EngineType.GECKO` (Mozilla GeckoView).
 * 2) Mọi khóa lưu trữ phiên cũ trong `SessionPersistenceCodec` đều hội tụ an toàn về `EngineType.GECKO` mà không làm mất tab.
 * 3) `GeckoBrowserEngine` kiểm định chính xác ABI, sự hiện diện SDK và thư viện gốc (`libxul.so` + `libmozglue.so`).
 * 4) `JavaScriptEvaluationPolicy` khóa đánh giá JS tùy ý từ Host và chặn các scheme đặc quyền (`javascript:`, `about:`, `data:`, `file:`).
 * 5) `WebDialogPrompt.FileChooser` giải phóng sạch callback khi tab đóng hoặc hủy hộp thoại.
 */
class GeckoOnlyArchitectureTest {

    @Test
    fun `EngineType exposes Mozilla GeckoView as sole browser engine and normalizes legacy keys`() {
        assertEquals(listOf(EngineType.GECKO), EngineType.entries)
        assertEquals(EngineType.GECKO, EngineType.DEFAULT)
        assertEquals(EngineType.GECKO, EngineType.fromStorageKey("GECKO"))
        assertEquals(EngineType.GECKO, EngineType.fromStorageKey("LEGACY_REMOVED_ENGINE"))
        assertEquals(EngineType.GECKO, EngineType.fromStorageKey(null))
        assertEquals("Gecko (WebRender)", EngineType.GECKO.renderingEngineName)
        assertEquals("SpiderMonkey", EngineType.GECKO.javascriptEngineName)
    }

    @Test
    fun `GeckoBrowserEngine validates ABI, SDK classes and native libxul plus libmozglue presence`() {
        val tempDir = Files.createTempDirectory("veko_gecko_sdk_verify").toFile()
        try {
            val runtimeManager = BrowserRuntimeManager(ContextWrapper(null))
            val sessionManager = GeckoSessionManager(GeckoEngineSessionBridge(runtimeManager))

            // 1. Có đủ SDK class và ABI arm64-v8a hợp lệ
            val validEngine = GeckoBrowserEngine(
                runtimeManager = runtimeManager,
                sessionManager = sessionManager,
                deviceAbiProvider = { listOf("arm64-v8a") },
                geckoSdkClassPresent = { true },
                nativeLibraryDirProvider = { null },
            )
            val okReport = validEngine.checkAvailability()
            assertEquals(EngineAvailabilityStatus.AVAILABLE, okReport.status)
            assertEquals(SdkValidationStatus.VERIFIED_MAVEN_SDK, okReport.sdkValidationStatus)
            assertTrue(okReport.isSelectable)
            assertFalse("Checking availability must NOT eagerly initialize GeckoRuntime", validEngine.isInitialized.value)

            // 2. Thiết bị không khớp ABI hỗ trợ
            val incompatibleAbiEngine = GeckoBrowserEngine(
                runtimeManager = runtimeManager,
                sessionManager = sessionManager,
                deviceAbiProvider = { listOf("mips64") },
                geckoSdkClassPresent = { true },
                nativeLibraryDirProvider = { null },
            )
            val badAbiReport = incompatibleAbiEngine.checkAvailability()
            assertEquals(EngineAvailabilityStatus.UNAVAILABLE, badAbiReport.status)
            assertEquals(SdkValidationStatus.ABI_INCOMPATIBLE, badAbiReport.sdkValidationStatus)
            assertFalse(badAbiReport.isSelectable)

            // 3. Thư viện nativeDir tồn tại nhưng thiếu libxul.so
            File(tempDir, "libmozglue.so").writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            val missingLibxulEngine = GeckoBrowserEngine(
                runtimeManager = runtimeManager,
                sessionManager = sessionManager,
                deviceAbiProvider = { listOf("x86_64") },
                geckoSdkClassPresent = { true },
                nativeLibraryDirProvider = { tempDir },
            )
            val missingSoReport = missingLibxulEngine.checkAvailability()
            assertEquals(EngineAvailabilityStatus.UNAVAILABLE, missingSoReport.status)
            assertEquals(SdkValidationStatus.INVALID_ARTIFACT_PROVENANCE, missingSoReport.sdkValidationStatus)
            assertFalse(missingSoReport.isSelectable)
            assertTrue(missingSoReport.missingArtifacts.contains("libxul.so"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `SessionPersistenceCodec preserves Gecko state and strips foreign legacy engine state safely`() {
        val regularTab = Tab(
            id = "tab-gecko-1",
            isPrivate = false,
            engineType = EngineType.GECKO,
            createdAtEpochMs = 1_000L,
            lastAccessedEpochMs = 2_000L,
            sessionState = TabSessionState(
                url = "https://mozilla.org/en-US/",
                title = "Mozilla",
                serializedEngineState = "{\"geckoHistory\":1}",
            ),
        )
        val privateTab = Tab(
            id = "tab-private-secret",
            isPrivate = true,
            engineType = EngineType.GECKO,
            createdAtEpochMs = 1_500L,
            lastAccessedEpochMs = 2_500L,
            sessionState = TabSessionState(url = "https://private.example.org/"),
        )

        val encoded = SessionPersistenceCodec.encodeRegularTabs(
            BrowserTabsUiState(
                tabs = listOf(regularTab, privateTab),
                activeTabId = privateTab.id,
                isRestoring = false,
            ),
        )
        assertFalse("Private tab must never be serialized", encoded.tabsJson.contains("private.example.org"))
        assertEquals("tab-gecko-1", encoded.activeRegularTabId)

        val decoded = SessionPersistenceCodec.decodePersistedTabs(encoded.tabsJson, encoded.activeRegularTabId)
        assertEquals(1, decoded.tabs.size)
        assertEquals(EngineType.GECKO, decoded.tabs.single().engineType)
        assertEquals("{\"geckoHistory\":1}", decoded.tabs.single().sessionState.serializedEngineState)

        // Giả lập JSON lưu từ phiên bản cũ có trường engineType lạ -> giữ URL tab nhưng bỏ serializedEngineState ngoại lai
        val legacyJson = """
            {
              "schemaVersion": 3,
              "tabs": [
                {
                  "id": "legacy-tab-99",
                  "url": "https://duckduckgo.com/?q=kotlin",
                  "title": "Kotlin Search",
                  "engineType": "LEGACY_OTHER",
                  "isDesktopMode": true,
                  "lastAccessed": 5000,
                  "engineState": "incompatible_foreign_blob"
                }
              ]
            }
        """.trimIndent()
        val migrated = SessionPersistenceCodec.decodePersistedTabs(legacyJson, "legacy-tab-99")
        assertEquals(1, migrated.tabs.size)
        assertEquals(EngineType.GECKO, migrated.tabs.single().engineType)
        assertEquals("https://duckduckgo.com/?q=kotlin", migrated.tabs.single().sessionState.url)
        assertTrue(migrated.tabs.single().sessionState.isDesktopMode)
        assertNull("Foreign serialized engine state must be stripped on migration to GeckoView", migrated.tabs.single().sessionState.serializedEngineState)
    }

    @Test
    fun `GeckoSettings maps cookie isolation modes and JavaScriptEvaluationPolicy enforces strict origin checks`() {
        assertEquals(
            ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS,
            GeckoSettings.mapRegularCookieBehavior(CookieControlMode.BLOCK_THIRD_PARTY),
        )
        assertEquals(
            ContentBlocking.CookieBehavior.ACCEPT_NONE,
            GeckoSettings.mapRegularCookieBehavior(CookieControlMode.BLOCK_ALL),
        )
        assertEquals(
            ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS,
            GeckoSettings.mapPrivateCookieBehavior(CookieControlMode.ALLOW_ALL),
        )

        assertTrue(
            JavaScriptEvaluationPolicy.validate("https://example.org", "javascript:alert(1)")
                is JavaScriptEvalResult.BlockedByPolicy,
        )
        assertTrue(
            JavaScriptEvaluationPolicy.validate("about:blank", "1 + 1")
                is JavaScriptEvalResult.BlockedByPolicy,
        )
        assertNull(JavaScriptEvaluationPolicy.validate("https://example.org/path", "document.title"))
    }

    @Test
    fun `FileChooser WebDialogPrompt cancels cleanly with empty selection when tab closes`() {
        var capturedSelection: List<String>? = null
        val prompt = WebDialogPrompt.FileChooser(
            id = "chooser-1",
            tabId = "tab-1",
            title = "Upload document",
            mimeTypes = listOf("application/pdf", "image/*"),
            allowMultiple = true,
            isFolderUpload = false,
            onResult = { uris -> capturedSelection = uris },
        )
        prompt.cancel()
        assertEquals(emptyList<String>(), capturedSelection)
    }
}
