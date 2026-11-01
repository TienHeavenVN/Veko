package io.veko.browser.chromium.embedder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Cầu nối tiến trình con của Chromium Content (`ChildProcessService` + `ContentChildProcessServiceDelegate`).
 *
 * Khi các thư viện `.jar` thượng nguồn (`content_java`, `process_launcher_java`) của Chromium `156.0.8078.85`
 * đã được nhập qua `scripts/import-chromium-sdk.sh`, Service này khởi tạo và ủy quyền trực tiếp `onCreate`,
 * `onBind`, `onDestroy` cho `org.chromium.base.process_launcher.ChildProcessService` để thiết lập IPC thực
 * cho các tiến trình Renderer (sandboxed) và GPU/Network (privileged).
 */
abstract class BaseVekoChromiumProcessService : Service() {
    private var upstreamChildProcessService: Any? = null
    private val fallbackBinder = Binder()

    override fun onCreate() {
        super.onCreate()
        upstreamChildProcessService = createUpstreamChildServiceOrNull(this, applicationContext)
        invokeZeroArg(upstreamChildProcessService, "onCreate")
    }

    override fun onBind(intent: Intent?): IBinder {
        val delegate = upstreamChildProcessService
        if (delegate != null && intent != null) {
            val bound = runCatching {
                val method = delegate.javaClass.getMethod("onBind", Intent::class.java)
                method.invoke(delegate, intent) as? IBinder
            }.getOrNull()
            if (bound != null) return bound
        }
        return fallbackBinder
    }

    override fun onDestroy() {
        invokeZeroArg(upstreamChildProcessService, "onDestroy")
        upstreamChildProcessService = null
        super.onDestroy()
    }

    companion object {
        private const val CHILD_PROCESS_SERVICE_CLASS =
            "org.chromium.base.process_launcher.ChildProcessService"
        private const val CHILD_PROCESS_DELEGATE_INTERFACE =
            "org.chromium.base.process_launcher.ChildProcessServiceDelegate"
        private const val CONTENT_DELEGATE_CLASS =
            "org.chromium.content.app.ContentChildProcessServiceDelegate"

        fun isUpstreamChildServiceAvailable(classLoader: ClassLoader = BaseVekoChromiumProcessService::class.java.classLoader!!): Boolean =
            runCatching {
                Class.forName(CHILD_PROCESS_SERVICE_CLASS, false, classLoader)
                Class.forName(CONTENT_DELEGATE_CLASS, false, classLoader)
                true
            }.getOrDefault(false)

        private fun createUpstreamChildServiceOrNull(service: Service, appContext: Context): Any? =
            runCatching {
                val loader = service.javaClass.classLoader ?: return null
                val delegateImplCls = Class.forName(CONTENT_DELEGATE_CLASS, true, loader)
                val delegateInterfaceCls = Class.forName(CHILD_PROCESS_DELEGATE_INTERFACE, true, loader)
                val childServiceCls = Class.forName(CHILD_PROCESS_SERVICE_CLASS, true, loader)
                val delegateInstance = delegateImplCls.getDeclaredConstructor().newInstance()
                val ctor = childServiceCls.getConstructor(
                    delegateInterfaceCls,
                    Service::class.java,
                    Context::class.java,
                )
                ctor.newInstance(delegateInstance, service, appContext)
            }.getOrNull()

        private fun invokeZeroArg(receiver: Any?, methodName: String) {
            if (receiver == null) return
            runCatching {
                receiver.javaClass.getMethod(methodName).invoke(receiver)
            }
        }
    }
}

class VekoSandboxedProcessService0 : BaseVekoChromiumProcessService()
class VekoSandboxedProcessService1 : BaseVekoChromiumProcessService()
class VekoPrivilegedProcessService0 : BaseVekoChromiumProcessService()
