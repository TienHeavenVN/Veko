package io.veko.browser.chromium.embedder

import io.veko.browser.chromium.nativebridge.VekoChromiumNative
import io.veko.browser.data.repository.CookieControlMode
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Đại diện Kotlin của `content::BrowserContext` + `content::StoragePartition` gắn với handle C++ gốc:
 * - Regular Context: lưu phân vùng hồ sơ tại thư mục riêng của ứng dụng.
 * - Off-The-Record Context: hoàn toàn nằm trong RAM (`storagePath == ""`), cô lập hoàn toàn cookie/cache/storage
 *   với phiên thường và hủy sạch vùng nhớ C++ ngay khi đóng tab riêng tư cuối cùng.
 */
class ChromiumBrowserContext private constructor(
    val nativeHandle: Long,
    val isOffTheRecord: Boolean
) : Closeable {

    private val destroyed = AtomicBoolean(false)

    val isDestroyed: Boolean
        get() = destroyed.get()

    val storagePartitionPath: String
        get() {
            check(!destroyed.get()) { "BrowserContext($nativeHandle) has already been destroyed." }
            return VekoChromiumNative.getContextStoragePath(nativeHandle)
        }

    fun setCookie(
        requestUrl: String,
        topFrameUrl: String,
        cookieHeader: String,
        mode: CookieControlMode
    ): Boolean {
        if (destroyed.get()) return false
        return VekoChromiumNative.setCookie(
            contextHandle = nativeHandle,
            requestUrl = requestUrl,
            topFrameUrl = topFrameUrl,
            cookieKv = cookieHeader,
            cookieModeOrdinal = mode.ordinal
        )
    }

    fun getCookies(
        requestUrl: String,
        topFrameUrl: String,
        mode: CookieControlMode
    ): String {
        if (destroyed.get()) return ""
        return VekoChromiumNative.getCookies(
            contextHandle = nativeHandle,
            requestUrl = requestUrl,
            topFrameUrl = topFrameUrl,
            cookieModeOrdinal = mode.ordinal
        )
    }

    fun clearStoragePartition(): Boolean {
        if (destroyed.get()) return false
        return VekoChromiumNative.clearStoragePartition(nativeHandle)
    }

    override fun close() {
        if (destroyed.compareAndSet(false, true)) {
            VekoChromiumNative.destroyBrowserContext(nativeHandle)
        }
    }

    companion object {
        fun createRegular(profileDirectory: File): ChromiumBrowserContext {
            profileDirectory.mkdirs()
            val handle = VekoChromiumNative.createBrowserContext(
                isOffTheRecord = false,
                diskStoragePath = profileDirectory.absolutePath
            )
            return ChromiumBrowserContext(nativeHandle = handle, isOffTheRecord = false)
        }

        fun createOffTheRecord(): ChromiumBrowserContext {
            val handle = VekoChromiumNative.createBrowserContext(
                isOffTheRecord = true,
                diskStoragePath = ""
            )
            return ChromiumBrowserContext(nativeHandle = handle, isOffTheRecord = true)
        }
    }
}
