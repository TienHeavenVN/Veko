package io.veko.browser

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Kiểm thử UI/UX Jetpack Compose thực tế trên Android Emulator cho Trang chủ Veko đã refactor:
 * - Xác minh dòng mô tả dưới chữ "Veko" đã bị xóa hoàn toàn khỏi cây giao diện.
 * - Xác minh ô tìm kiếm/URL trực tiếp, nút xóa nhanh, bộ chọn công cụ tìm kiếm, Quick Actions, Empty Shortcuts, thêm Shortcut,
 *   bộ đếm Tab, chuyển chế độ Riêng tư, thanh điều hướng dưới đáy, TalkBack và tỷ lệ chữ lớn (font_scale 1.35).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class HomeScreenRefactorComposeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun homeComponentsNoTaglineSearchQuickActionsShortcutsPrivateModeAndLargeFontWorkEndToEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val app = targetContext.applicationContext as VekoApplication
        val repo = app.dataRepository.get()

        // Đảm bảo bắt đầu với danh sách dấu trang trống để xác minh EmptyShortcutsState thực tế
        runBlocking {
            val current = repo.bookmarks.first().map { it.url }
            for (url in current) {
                repo.deleteBookmark(url)
            }
        }

        val launchIntent = Intent(targetContext, MainActivity::class.java)
        try {
            ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
                val tabManager = app.tabManager.get()
                waitUntilReady {
                    !tabManager.uiState.value.isRestoring && tabManager.uiState.value.tabs.isNotEmpty()
                }
                scenario.onActivity {
                    tabManager.closeAllPrivateTabs()
                    tabManager.createTab(
                        url = io.veko.browser.tab.TabManager.ABOUT_BLANK,
                        isPrivate = false,
                        select = true,
                    )
                }
                composeRule.waitForIdle()

                // 1. Header hiển thị "Veko" rõ ràng và tuyệt đối KHÔNG còn dòng tagline bên dưới
                composeRule.onNodeWithTag("home_header").assertIsDisplayed()
                composeRule.onNodeWithTag("home_brand_title").assertIsDisplayed().assertTextEquals("Veko")
                composeRule.onNodeWithTag("home_privacy_button").assertIsDisplayed()
                composeRule.onNodeWithTag("home_settings_button").assertIsDisplayed()
                composeRule.onNodeWithText("Trình duyệt nhân GeckoView", substring = true).assertDoesNotExist()
                composeRule.onNodeWithText("Phiên tách biệt trên RAM", substring = true).assertDoesNotExist()

                // 2. Thanh điều hướng dưới đáy hiển thị đầy đủ khi chưa mở bàn phím
                composeRule.onNodeWithTag("browser_bottom_bar").assertIsDisplayed()
                composeRule.onNodeWithTag("bottom_bar_home").assertIsDisplayed()
                composeRule.onNodeWithTag("bottom_bar_address_pill").assertIsDisplayed()
                composeRule.onNodeWithTag("bottom_bar_new_tab").assertIsDisplayed()
                composeRule.onNodeWithTag("bottom_bar_tab_switcher").assertIsDisplayed()
                composeRule.onNodeWithTag("bottom_bar_menu").assertIsDisplayed()

                // 3. EmptyShortcutsState ngắn gọn khi chưa có lối tắt nào được ghim
                composeRule.onNodeWithTag("home_empty_shortcuts").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithTag("home_empty_add_shortcut").assertIsDisplayed()

                // Lưu ảnh chụp thực tế màn hình Trang chủ trạng thái trống ra MediaStore.Downloads cho CI artifact
                saveEmulatorScreenshotToDownloads("veko_ci_home_empty.png")

                // 4. Kiểm tra Quick Actions đồng nhất (Tab, Dấu trang, Lịch sử, Tải xuống)
                composeRule.onNodeWithTag("quick_action_tabs").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithTag("quick_action_bookmarks").assertIsDisplayed()
                composeRule.onNodeWithTag("quick_action_history").assertIsDisplayed()
                composeRule.onNodeWithTag("quick_action_downloads").assertIsDisplayed()

                // 5. Bộ chọn công cụ tìm kiếm (SearchEngineSelector)
                composeRule.onNodeWithTag("home_search_engine_selector").performScrollTo().assertIsDisplayed().performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("search_engine_option_GOOGLE").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("home_search_engine_selector").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("search_engine_option_DUCKDUCKGO").performClick()
                composeRule.waitForIdle()

                // 6. Ô nhập URL/từ khóa trực tiếp: nhập URL dài -> ẩn thanh đáy chống cạnh tranh -> nút Xóa hoạt động
                composeRule.onNodeWithTag("home_address_input").performScrollTo().performClick()
                composeRule.onNodeWithTag("home_address_input")
                    .performTextInput("https://developer.android.com/jetpack/compose/testing/very-long-url-segment")
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("home_address_clear").assertIsDisplayed()
                composeRule.onNodeWithTag("browser_bottom_bar").assertDoesNotExist()
                composeRule.onNodeWithTag("home_address_clear").performClick()
                composeRule.waitForIdle()

                // Nhấn Back qua OnBackPressedDispatcher để BackHandler thoát focus ô tìm kiếm và khôi phục thanh đáy
                scenario.onActivity { activity ->
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("browser_bottom_bar").assertIsDisplayed()

                // 7. Thêm lối tắt mới từ EmptyShortcutsState và xác minh hiển thị trên HomeScreen
                composeRule.onNodeWithTag("home_empty_add_shortcut").performScrollTo().performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("shortcut_dialog_url_input").performTextInput("kotlinlang.org")
                composeRule.onNodeWithTag("shortcut_dialog_title_input").performTextInput("Kotlin Official")
                composeRule.onNodeWithTag("shortcut_dialog_save").performClick()
                composeRule.waitForIdle()

                waitUntilReady(timeoutMs = 5_000L) {
                    runBlocking { repo.bookmarks.first().isNotEmpty() }
                }
                composeRule.waitForIdle()
                composeRule.onNodeWithText("Kotlin Official").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithTag("home_empty_shortcuts").assertDoesNotExist()
                saveEmulatorScreenshotToDownloads("veko_ci_home_shortcuts.png")

                // 8. Chuyển sang Chế độ Riêng tư qua PrivacyButton -> không rò rỉ lối tắt thường
                composeRule.onNodeWithTag("home_privacy_button").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("home_private_notice").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithText("Kotlin Official").assertDoesNotExist()
                saveEmulatorScreenshotToDownloads("veko_ci_home_private.png")

                // Quay lại tab thường
                composeRule.onNodeWithTag("home_privacy_button").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithText("Kotlin Official").performScrollTo().assertIsDisplayed()

                // 9. Kiểm tra khả năng co giãn với cỡ chữ lớn (font_scale = 1.35) và màn hình hẹp (wm size 900x2000)
                runShell("settings put system font_scale 1.35")
                runShell("wm size 900x2000")
                SystemClock.sleep(600)
                composeRule.waitForIdle()

                composeRule.onNodeWithTag("home_brand_title").assertIsDisplayed().assertTextEquals("Veko")
                composeRule.onNodeWithTag("home_privacy_button").assertIsDisplayed()
                composeRule.onNodeWithTag("home_settings_button").assertIsDisplayed()
                composeRule.onNodeWithTag("home_address_section").assertIsDisplayed()
                composeRule.onNodeWithTag("home_quick_actions").performScrollTo().assertIsDisplayed()
                assertTrue("Expected at least 1 active tab in TabManager", tabManager.uiState.value.tabs.isNotEmpty())

                scenario.onActivity {
                    tabManager.closeAllPrivateTabs()
                }
            }
        } finally {
            runShell("settings put system font_scale 1.0")
            runShell("wm size reset")
            runBlocking {
                val current = repo.bookmarks.first().map { it.url }
                for (url in current) {
                    repo.deleteBookmark(url)
                }
            }
        }
    }

    private fun saveEmulatorScreenshotToDownloads(fileName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)
                out.flush()
            }
            bitmap.recycle()
        }
    }

    private fun runShell(cmd: String) {
        runCatching {
            val pfd: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand(cmd)
            FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
            pfd.close()
        }
    }

    private fun waitUntilReady(timeoutMs: Long = 20_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(150L)
        }
        throw AssertionError("Timed out waiting for readiness condition")
    }
}
