package io.veko.browser

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bộ kiểm thử tích hợp chạy trực tiếp trên Android Emulator (x86_64) với native GeckoRuntime + libxul.so:
 * - Xác minh khởi tạo GeckoRuntime, tiến trình con :tab, gắn kết GeckoView trong Jetpack Compose và lưu lịch sử Room.
 * - Xác minh tái tạo Activity (xoay màn hình / cấu hình) không nhân đôi tab ACTION_VIEW và gắn lại GeckoView sạch sẽ.
 * - Xác minh trần cứng 6 GeckoSession sống khi mở tab dồn dập và cô lập 100% dữ liệu Private Mode khỏi hệ thống tệp Android.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class VekoEmulatorInstrumentedTest {

    private lateinit var server: LoopbackHttpTestServer

    @Before
    fun setUp() {
        server = LoopbackHttpTestServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun geckoRuntimeBootsRendersPageAndPersistsRegularHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val app = targetContext.applicationContext as VekoApplication
        val targetUrl = "${server.baseUrl}/smoke"

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
            .setClass(targetContext, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val tabManager = app.tabManager.get()

            waitUntil(
                timeoutMs = 45_000L,
                description = "GeckoRuntime loads loopback /smoke page with expected title",
            ) {
                val active = tabManager.uiState.value.activeTab
                active != null &&
                    active.sessionState.title == "Title:/smoke" &&
                    !active.sessionState.isLoading
            }

            // Kiểm tra cây View thực tế của MainActivity có đúng thực thể GeckoView đã đo kích thước và gắn session mở
            waitUntil(
                timeoutMs = 10_000L,
                description = "MainActivity hosts measured GeckoView with open GeckoSession",
            ) {
                val ready = AtomicBoolean(false)
                scenario.onActivity { activity ->
                    val gv = findFirstGeckoView(activity.window.decorView)
                    ready.set(gv != null && gv.width > 0 && gv.height > 0 && gv.session?.isOpen == true)
                }
                ready.get()
            }

            // Xác minh Room SQLite thực tế đã ghi nhận lịch sử cho tab thường
            val repo = app.dataRepository.get()
            waitUntil(
                timeoutMs = 10_000L,
                description = "Room browsing_history persists visited regular URL",
            ) {
                runBlocking {
                    repo.recentHistory.first().any { it.url == targetUrl }
                }
            }
        }
    }

    @Test
    fun activityRecreateDoesNotDuplicateExternalIntentTabAndRebindsGeckoView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val app = targetContext.applicationContext as VekoApplication
        val initialUrl = "${server.baseUrl}/recreate-initial"
        val afterRecreateUrl = "${server.baseUrl}/recreate-after"

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(initialUrl))
            .setClass(targetContext, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val tabManager = app.tabManager.get()

            waitUntil(
                timeoutMs = 45_000L,
                description = "Initial external ACTION_VIEW page finishes loading",
            ) {
                val active = tabManager.uiState.value.activeTab
                active != null &&
                    active.sessionState.title == "Title:/recreate-initial" &&
                    !active.sessionState.isLoading
            }

            val tabCountBeforeRecreate = tabManager.uiState.value.tabs.size
            val activeTabIdBeforeRecreate = tabManager.uiState.value.activeTabId

            // Tái tạo Activity thực tế trên Emulator: hủy GeckoView cũ và gắn GeckoView mới mà không crash hay mở lặp tab
            scenario.recreate()

            assertEquals(
                "EXTRA_EXTERNAL_URL_CONSUMED must prevent duplicate tab creation on Activity.recreate()",
                tabCountBeforeRecreate,
                tabManager.uiState.value.tabs.size,
            )
            assertEquals(activeTabIdBeforeRecreate, tabManager.uiState.value.activeTabId)

            waitUntil(
                timeoutMs = 10_000L,
                description = "Recreated MainActivity rebinds a live measured GeckoView",
            ) {
                val rebound = AtomicBoolean(false)
                scenario.onActivity { activity ->
                    val gv = findFirstGeckoView(activity.window.decorView)
                    rebound.set(gv != null && gv.width > 0 && gv.height > 0 && gv.session?.isOpen == true)
                }
                rebound.get()
            }

            scenario.onActivity {
                tabManager.loadUrlOrSearch(afterRecreateUrl)
            }

            waitUntil(
                timeoutMs = 30_000L,
                description = "Recreated MainActivity navigates cleanly to /recreate-after",
            ) {
                val active = tabManager.uiState.value.activeTab
                active != null &&
                    active.sessionState.title == "Title:/recreate-after" &&
                    !active.sessionState.isLoading
            }
        }
    }

    @Test
    fun rapidTabCreationEnforcesSixLiveGeckoSessionsAndKeepsPrivateCanaryOffDisk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val app = targetContext.applicationContext as VekoApplication
        val privateCanaryToken = "veko-private-canary-98412"
        val privateCanaryUrl = "https://$privateCanaryToken.invalid/secret-path"

        val launchIntent = Intent(targetContext, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            val tabManager = app.tabManager.get()

            waitUntil(
                timeoutMs = 30_000L,
                description = "TabManager completes initial bootstrap",
            ) {
                !tabManager.uiState.value.isRestoring && tabManager.uiState.value.tabs.isNotEmpty()
            }

            val createdIds = mutableListOf<String>()

            // Mở dồn dập 8 tab thường + 1 tab riêng tư ngay trên UI thread thực tế
            scenario.onActivity {
                for (index in 1..8) {
                    createdIds += tabManager.createTab(
                        url = "${server.baseUrl}/rapid-$index",
                        isPrivate = false,
                        select = true,
                    )
                }
                assertTrue(
                    "Live native GeckoSessions (${tabManager.activeLiveSessionCount}) must never exceed 6",
                    tabManager.activeLiveSessionCount <= 6,
                )

                createdIds += tabManager.createTab(
                    url = privateCanaryUrl,
                    isPrivate = true,
                    select = true,
                )
                assertTrue(
                    "Live native GeckoSessions (${tabManager.activeLiveSessionCount}) must remain <= 6 after private tab",
                    tabManager.activeLiveSessionCount <= 6,
                )
            }

            assertTrue("All regular + private tabs must remain preserved in UI state", tabManager.uiState.value.tabs.size >= 9)

            // Chờ vượt ngưỡng debounce 600ms để DataStore flush phiên tab thường xuống đĩa thật của Android
            SystemClock.sleep(1_500L)

            val datastoreDir = File(targetContext.filesDir, "datastore")
            val sessionStoreFile = File(datastoreDir, "veko_tabs_session_store.preferences_pb")
            waitUntil(
                timeoutMs = 10_000L,
                description = "DataStore writes regular tabs snapshot to disk",
            ) {
                sessionStoreFile.isFile && sessionStoreFile.length() > 0L
            }

            val sessionBytesText = String(sessionStoreFile.readBytes(), StandardCharsets.ISO_8859_1)
            assertTrue("Regular loopback tab URL should be persisted in session store", sessionBytesText.contains("127.0.0.1"))
            assertFalse(
                "Private tab canary must NEVER appear in DataStore session file",
                sessionBytesText.contains(privateCanaryToken),
            )

            // Quét toàn bộ tệp SQLite Room (db, wal, shm) để chắc chắn không rò rỉ dữ liệu riêng tư
            val dbParentDir = targetContext.getDatabasePath("veko_browser.db").parentFile
            val dbFiles = dbParentDir?.listFiles().orEmpty()
            for (file in dbFiles) {
                if (file.isFile) {
                    val rawDbContent = String(file.readBytes(), StandardCharsets.ISO_8859_1)
                    assertFalse(
                        "Private canary leaked into database file ${file.name}",
                        rawDbContent.contains(privateCanaryToken),
                    )
                }
            }

            val historyItems = runBlocking { app.dataRepository.get().recentHistory.first() }
            assertFalse(
                "Private canary must never appear in Room History Flow",
                historyItems.any { it.url.contains(privateCanaryToken) },
            )

            // Dọn các tab vừa tạo để giữ bộ nhớ nhẹ cho các test kế tiếp
            scenario.onActivity {
                createdIds.forEach { id -> tabManager.closeTab(id) }
            }
        }
    }

    private fun findFirstGeckoView(root: View?): GeckoView? {
        if (root == null) return null
        if (root is GeckoView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findFirstGeckoView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun waitUntil(
        timeoutMs: Long,
        pollIntervalMs: Long = 150L,
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(pollIntervalMs)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $description")
    }

    /**
     * Máy chủ HTTP loopback tối giản chạy trên 127.0.0.1 để kiểm thử GeckoRuntime hoàn toàn độc lập với mạng ngoài.
     */
    private class LoopbackHttpTestServer : Closeable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()

        @Volatile
        private var running = true

        val baseUrl: String
            get() = "http://127.0.0.1:${serverSocket.localPort}"

        fun start() {
            executor.execute {
                while (running) {
                    val client = try {
                        serverSocket.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    executor.execute {
                        runCatching {
                            client.use { socket ->
                                socket.soTimeout = 5_000
                                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                                val requestLine = reader.readLine().orEmpty()
                                while (true) {
                                    val header = reader.readLine() ?: break
                                    if (header.isEmpty()) break
                                }
                                val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                                val html = buildHtml(path)
                                val bodyBytes = html.toByteArray(StandardCharsets.UTF_8)
                                val headers = buildString {
                                    append("HTTP/1.1 200 OK\r\n")
                                    append("Content-Type: text/html; charset=utf-8\r\n")
                                    append("Content-Length: ${bodyBytes.size}\r\n")
                                    append("Cache-Control: no-store\r\n")
                                    append("Connection: close\r\n\r\n")
                                }.toByteArray(StandardCharsets.UTF_8)
                                val out = socket.getOutputStream()
                                out.write(headers)
                                out.write(bodyBytes)
                                out.flush()
                            }
                        }
                    }
                }
            }
        }

        private fun buildHtml(path: String): String = """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Title:$path</title>
            </head>
            <body>
              <h1>Veko Local Test Server</h1>
              <p>Path: $path</p>
            </body>
            </html>
        """.trimIndent()

        override fun close() {
            running = false
            runCatching { serverSocket.close() }
            executor.shutdownNow()
        }
    }
}
