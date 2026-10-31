package io.veko.browser.chromium.embedder

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Khung tiến trình con đa tiến trình chuẩn Chromium Content Embedder trên Android:
 * Renderer chạy trong `android:isolatedProcess="true"` không có quyền truy cập mạng/đĩa trực tiếp.
 */
abstract class BaseChromiumChildProcessService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder = binder
}

class ChromiumSandboxedProcessService0 : BaseChromiumChildProcessService()
class ChromiumSandboxedProcessService1 : BaseChromiumChildProcessService()
class ChromiumPrivilegedProcessService0 : BaseChromiumChildProcessService()
