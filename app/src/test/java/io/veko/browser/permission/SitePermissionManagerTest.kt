package io.veko.browser.permission

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class InMemoryPermissionsStore(initialJson: String? = null) : PermissionsBackingStore {
    private val flow = MutableStateFlow(initialJson)
    var writeCount: Int = 0
        private set
    val latestJson: String?
        get() = flow.value

    override val rawJsonFlow: Flow<String?> = flow

    override suspend fun writeRawJson(json: String) {
        writeCount++
        flow.value = json
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SitePermissionManagerTest {

    @Test
    fun `insecure non-loopback HTTP origins are strictly denied while HTTPS and localhost support prompts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryPermissionsStore()
        val manager = SitePermissionManager(backingStore = store, ioDispatcher = dispatcher)
        advanceUntilIdle()

        // HTTP thường không mã hóa bị từ chối ngay cả cho Vị trí, Máy ảnh và Micrô
        for (type in SitePermissionType.entries) {
            assertEquals(
                SitePermissionDecision.DENY,
                manager.getDecision("http://insecure-example.com/camera", type, isPrivate = false),
            )
        }

        // Loopback phục vụ kiểm thử/phát triển nội bộ và HTTPS hợp lệ trả về PROMPT khi chưa có quyết định
        assertEquals(
            SitePermissionDecision.PROMPT,
            manager.getDecision("http://127.0.0.1:8080/rtc", SitePermissionType.CAMERA, isPrivate = false),
        )
        assertEquals(
            SitePermissionDecision.PROMPT,
            manager.getDecision("https://meet.jit.si/room1", SitePermissionType.MICROPHONE, isPrivate = false),
        )
    }

    @Test
    fun `regular permissions persist per-origin and support granular or bulk revocation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryPermissionsStore()
        val manager = SitePermissionManager(backingStore = store, ioDispatcher = dispatcher)
        advanceUntilIdle()

        manager.recordDecision(
            rawUrlOrOrigin = "https://maps.example.org/view?lat=21",
            types = listOf(SitePermissionType.LOCATION),
            decision = SitePermissionDecision.ALLOW,
            isPrivate = false,
            remember = true,
        )
        manager.recordDecision(
            rawUrlOrOrigin = "https://video.example.org/call",
            types = listOf(SitePermissionType.CAMERA, SitePermissionType.MICROPHONE),
            decision = SitePermissionDecision.DENY,
            isPrivate = false,
            remember = true,
        )
        advanceUntilIdle()

        assertTrue(store.writeCount >= 2)
        assertEquals(
            SitePermissionDecision.ALLOW,
            manager.getDecision("https://maps.example.org/other-page", SitePermissionType.LOCATION, isPrivate = false),
        )
        assertEquals(
            SitePermissionDecision.PROMPT,
            manager.getDecision("https://sub.maps.example.org/", SitePermissionType.LOCATION, isPrivate = false),
        )

        // Khởi tạo lại manager mới từ cùng dữ liệu lưu trữ để xác minh khôi phục JSON chính xác
        val reloaded = SitePermissionManager(backingStore = store, ioDispatcher = dispatcher)
        advanceUntilIdle()
        assertEquals(
            SitePermissionDecision.ALLOW,
            reloaded.getDecision("https://maps.example.org/", SitePermissionType.LOCATION, isPrivate = false),
        )
        assertEquals(
            SitePermissionDecision.DENY,
            reloaded.getDecision("https://video.example.org/", SitePermissionType.CAMERA, isPrivate = false),
        )

        // Thu hồi riêng 1 Origin
        reloaded.revokeOrigin("https://maps.example.org", isPrivate = false)
        advanceUntilIdle()
        assertEquals(
            SitePermissionDecision.PROMPT,
            reloaded.getDecision("https://maps.example.org/", SitePermissionType.LOCATION, isPrivate = false),
        )
        assertEquals(
            SitePermissionDecision.DENY,
            reloaded.getDecision("https://video.example.org/", SitePermissionType.MICROPHONE, isPrivate = false),
        )

        // Thu hồi toàn bộ quyền đã lưu
        reloaded.clearAllPersistedPermissions()
        advanceUntilIdle()
        assertEquals(
            SitePermissionDecision.PROMPT,
            reloaded.getDecision("https://video.example.org/", SitePermissionType.CAMERA, isPrivate = false),
        )
    }

    @Test
    fun `private mode permissions stay strictly in RAM and wipe cleanly without touching persistent store`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryPermissionsStore()
        val manager = SitePermissionManager(backingStore = store, ioDispatcher = dispatcher)
        advanceUntilIdle()

        manager.recordDecision(
            rawUrlOrOrigin = "https://private-conf.example.com/room",
            types = listOf(SitePermissionType.CAMERA, SitePermissionType.MICROPHONE),
            decision = SitePermissionDecision.ALLOW,
            isPrivate = true,
            remember = true,
        )
        advanceUntilIdle()

        // Tuyệt đối không ghi bất kỳ byte nào xuống đĩa khi ở chế độ riêng tư
        assertEquals(0, store.writeCount)
        assertFalse(store.latestJson.orEmpty().contains("private-conf.example.com"))
        assertEquals(
            SitePermissionDecision.ALLOW,
            manager.getDecision("https://private-conf.example.com/room", SitePermissionType.CAMERA, isPrivate = true),
        )
        // Tab thường truy cập cùng tên miền không bị ảnh hưởng bởi quyền tạm của tab riêng tư
        assertEquals(
            SitePermissionDecision.PROMPT,
            manager.getDecision("https://private-conf.example.com/room", SitePermissionType.CAMERA, isPrivate = false),
        )

        // Khi đóng toàn bộ tab riêng tư -> quyền trên RAM bị xóa ngay lập tức
        manager.clearPrivateSessionPermissions()
        assertEquals(
            SitePermissionDecision.PROMPT,
            manager.getDecision("https://private-conf.example.com/room", SitePermissionType.CAMERA, isPrivate = true),
        )
    }
}
