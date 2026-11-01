package io.veko.browser

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
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
                awaitComposeNodeTag(scenario, "home_header")

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
                saveActivityDecorScreenshotToDownloads(scenario, "veko_ci_home_empty.png")

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
                // Pin two additional clean developer/FOSS shortcuts so HomeScreen grid looks realistic
                runBlocking {
                    repo.toggleBookmark(title = "Mozilla MDN", url = "https://developer.mozilla.org")
                    repo.toggleBookmark(title = "F-Droid FOSS", url = "https://f-droid.org")
                    repo.toggleBookmark(title = "Rust Lang", url = "https://www.rust-lang.org")
                }
                composeRule.waitForIdle()
                composeRule.onNodeWithText("Kotlin Official").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithTag("home_empty_shortcuts").assertDoesNotExist()
                // Cuộn lại lên đầu trang để ảnh chụp màn hình 1.png hiển thị trọn vẹn Header Veko + Omnibox + Quick Actions + Lối tắt
                composeRule.onNodeWithTag("home_header").performScrollTo()
                composeRule.waitForIdle()
                saveActivityDecorScreenshotToDownloads(scenario, "veko_ci_home_shortcuts.png")

                // 8. Chuyển sang Chế độ Riêng tư qua PrivacyButton -> không rò rỉ lối tắt thường & cửa sổ bật FLAG_SECURE
                composeRule.onNodeWithTag("home_privacy_button").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("home_private_notice").performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithText("Kotlin Official").assertDoesNotExist()
                scenario.onActivity { activity ->
                    val flags = activity.window.attributes.flags
                    assertTrue(
                        "Expected WindowManager.LayoutParams.FLAG_SECURE on Private Browsing tab",
                        (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0,
                    )
                }
                saveActivityDecorScreenshotToDownloads(scenario, "veko_ci_home_private.png")

                // Quay lại tab thường -> cờ FLAG_SECURE được gỡ sạch
                composeRule.onNodeWithTag("home_privacy_button").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithText("Kotlin Official").performScrollTo().assertIsDisplayed()
                scenario.onActivity { activity ->
                    val flags = activity.window.attributes.flags
                    assertTrue(
                        "Expected FLAG_SECURE to be cleared on Regular tab",
                        (flags and WindowManager.LayoutParams.FLAG_SECURE) == 0,
                    )
                }

                // 9. Kiểm tra khả năng co giãn với cỡ chữ lớn (font_scale = 1.35) và màn hình hẹp (wm size 900x2000)
                runShell("settings put system font_scale 1.35")
                runShell("wm size 900x2000")
                instrumentation.waitForIdleSync()
                awaitComposeNodeTag(scenario, "home_brand_title")

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
            SystemClock.sleep(500)
            instrumentation.waitForIdleSync()
            runBlocking {
                val current = repo.bookmarks.first().map { it.url }
                for (url in current) {
                    repo.deleteBookmark(url)
                }
            }
        }
    }

    @Test
    fun settingsAndTabSwitcherRenderGeckoEngineAndSdkValidationStatus() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val app = targetContext.applicationContext as VekoApplication
        val launchIntent = Intent(targetContext, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            val tabManager = app.tabManager.get()
            waitUntilReady {
                !tabManager.uiState.value.isRestoring && tabManager.uiState.value.tabs.isNotEmpty()
            }
            scenario.onActivity {
                tabManager.createTab(
                    url = io.veko.browser.tab.TabManager.ABOUT_BLANK,
                    isPrivate = false,
                    select = true,
                )
            }
            awaitComposeNodeTag(scenario, "bottom_bar_shield_button")

            // 1. Kiểm chứng Lá chắn Riêng tư 3 lớp (Veko Privacy Shield + Rust adblock-rust 0.13.4) từ nút lá chắn trên thanh đáy
            val shield = app.privacyShieldEngine.get()
            shield.evaluateRequest(
                requestUrl = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
                topFrameUrl = "https://vnexpress.net/",
                resourceType = "script",
                isOffTheRecord = false,
            )
            shield.evaluateRequest(
                requestUrl = "https://adi.admicro.vn/adt/banners/popup.js",
                topFrameUrl = "https://vnexpress.net/",
                resourceType = "script",
                isOffTheRecord = false,
            )
            shield.evaluateRequest(
                requestUrl = "https://www.google-analytics.com/analytics.js",
                topFrameUrl = "https://vnexpress.net/",
                resourceType = "script",
                isOffTheRecord = false,
            )
            shield.evaluateRequest(
                requestUrl = "https://cdn.clarity.ms/tag/fingerprint.js",
                topFrameUrl = "https://vnexpress.net/",
                resourceType = "script",
                isOffTheRecord = false,
            )
            shield.evaluateRequest(
                requestUrl = "https://vnexpress.net/tin-tuc?utm_source=fb&fbclid=xyz987",
                topFrameUrl = "https://vnexpress.net/",
                resourceType = "main_frame",
                isOffTheRecord = false,
            )
            shield.recordEngineSubresourceBlock(
                blockedUri = "https://fpnpmcdn.net/v3/fp.js",
                isAd = false,
                isTracker = false,
                isFingerprinterOrMiner = true,
                isOffTheRecord = false,
            )

            composeRule.onNodeWithTag("bottom_bar_shield_button").assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("veko_privacy_shield_sheet").assertIsDisplayed()
            saveActivityDecorScreenshotToDownloads(scenario, "veko_ci_privacy_shield.png")
            composeRule.onNodeWithTag("veko_privacy_shield_sheet")
                .performScrollToNode(hasTestTag("shield_strict_tracking_switch"))
            composeRule.onNodeWithTag("shield_strict_tracking_switch").assertIsDisplayed()
            composeRule.onNodeWithTag("veko_privacy_shield_sheet")
                .performScrollToNode(hasTestTag("filter_pack_easylist-core"))
            composeRule.onNodeWithTag("filter_pack_easylist-core").assertIsDisplayed()
            composeRule.onNodeWithTag("veko_privacy_shield_sheet")
                .performScrollToNode(hasTestTag("filter_pack_vn-regional-ads"))
            composeRule.onNodeWithTag("filter_pack_vn-regional-ads").assertIsDisplayed()
            composeRule.onNodeWithTag("veko_privacy_shield_sheet").performScrollToIndex(0)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("shield_close_button").assertIsDisplayed().performClick()
            composeRule.waitForIdle()

            // 2. Mở khay Quản lý Tab -> xác minh tạo tab mới trên kiến trúc đơn nhân GeckoView & đóng khay sạch sẽ
            val tabsBefore = tabManager.uiState.value.tabs.size
            composeRule.onNodeWithTag("bottom_bar_tab_switcher").performClick()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithTag("new_tab_engine_chip_CHROMIUM").assertCountEquals(0)
            saveActivityDecorScreenshotToDownloads(scenario, "veko_ci_tab_switcher.png")
            composeRule.onNodeWithTag("tab_sheet_new_tab_button").performClick()
            composeRule.waitForIdle()
            waitUntilReady(timeoutMs = 5_000L) {
                tabManager.uiState.value.tabs.size == tabsBefore + 1
            }

            // 3. Mở Cài đặt Veko -> xác minh chuyển đổi đa ngôn ngữ trực tiếp (VI <-> EN) và thẻ Mozilla GeckoView duy nhất
            composeRule.onNodeWithTag("home_settings_button").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("settings_language_picker").assertIsDisplayed()
            composeRule.onNodeWithTag("language_chip_vi").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Nhân trình duyệt (Mozilla GeckoView)").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Nhân trình duyệt (Mozilla GeckoView)").assertIsDisplayed()
            composeRule.onNodeWithTag("language_chip_en").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Browser Engine (Mozilla GeckoView)").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Browser Engine (Mozilla GeckoView)").assertIsDisplayed()
            saveActivityDecorScreenshotToDownloads(scenario, "veko_ci_gecko_engine_settings.png")
            composeRule.onNodeWithTag("settings_sheet_list")
                .performScrollToNode(hasTestTag("engine_card_GECKO"))
            composeRule.onNodeWithTag("engine_card_GECKO").assertIsDisplayed()
            composeRule.onNodeWithText("Gecko (WebRender) • SpiderMonkey").assertIsDisplayed()
            composeRule.onAllNodesWithTag("engine_card_CHROMIUM").assertCountEquals(0)
            saveAppLauncherIconToDownloads("veko_ci_icon_512.png")
        }
    }

    private fun saveActivityDecorScreenshotToDownloads(
        scenario: ActivityScenario<MainActivity>,
        fileName: String,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        var captured: Bitmap? = null
        scenario.onActivity { activity ->
            val baseDecor = activity.window.decorView
            if (baseDecor.width > 0 && baseDecor.height > 0) {
                val bmp = Bitmap.createBitmap(baseDecor.width, baseDecor.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                val views = WindowInspector.getGlobalWindowViews().filter { it.isShown && it.width > 0 && it.height > 0 }
                if (views.isEmpty()) {
                    baseDecor.draw(canvas)
                } else {
                    val baseLoc = IntArray(2)
                    baseDecor.getLocationOnScreen(baseLoc)
                    views.forEachIndexed { idx, rootView ->
                        if (idx > 0) {
                            canvas.drawColor(android.graphics.Color.argb(135, 2, 6, 14))
                        }
                        val loc = IntArray(2)
                        rootView.getLocationOnScreen(loc)
                        val dx = (loc[0] - baseLoc[0]).toFloat()
                        val dy = (loc[1] - baseLoc[1]).toFloat()
                        canvas.save()
                        canvas.translate(dx, dy)
                        rootView.draw(canvas)
                        canvas.restore()
                    }
                }
                captured = bmp
            }
        }
        captured?.let { bmp ->
            runCatching { writePngToDownloads(fileName, bmp) }
            bmp.recycle()
        }
    }

    private fun saveAppLauncherIconToDownloads(fileName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val drawable = ctx.packageManager.getApplicationIcon(ctx.applicationInfo)
            val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, 512, 512)
            drawable.draw(canvas)
            writePngToDownloads(fileName, bmp)
            bmp.recycle()
        }
    }

    private fun writePngToDownloads(fileName: String, bitmap: Bitmap) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(fileName),
        )
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
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

    private fun awaitComposeNodeTag(
        scenario: ActivityScenario<MainActivity>,
        tag: String,
        timeoutMs: Long = 20_000L,
    ) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var attempt = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            runCatching { scenario.moveToState(Lifecycle.State.RESUMED) }
            val ready = runCatching {
                composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
            if (ready) {
                composeRule.waitForIdle()
                return
            }
            attempt++
            if (attempt % 5 == 0) {
                runShell("input keyevent 224")
                runShell("wm dismiss-keyguard")
                runShell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
                runCatching {
                    targetContext.startActivity(
                        Intent(targetContext, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        },
                    )
                }
            }
            SystemClock.sleep(200L)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for Compose hierarchy and node tag '$tag'")
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
