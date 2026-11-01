package io.veko.browser.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.view.View
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.data.repository.SettingsRepository
import io.veko.browser.engine.chromium.ChromiumBrowserEngine
import io.veko.browser.engine.gecko.GeckoBrowserEngine
import io.veko.browser.tab.EngineSessionCallbacks
import io.veko.browser.tab.EngineSessionHandle
import io.veko.browser.tab.EngineViewHandle
import io.veko.browser.tab.MultiEngineSessionBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bộ điều phối trung tâm của kiến trúc Dual-Engine (`GeckoBrowserEngine` + `ChromiumBrowserEngine`):
 *
 * 1) **Khởi tạo lười đơn nhân (Lazy Single-Engine Init)**: Khi mở ứng dụng, chỉ đọc siêu dữ liệu `checkAvailability()`
 *    nhẹ mà KHÔNG khởi tạo `GeckoRuntime` hay `ChromiumEmbedderRuntime` cho tới khi có tab thực tế mở.
 * 2) **Không thay thế ngầm (No Silent Engine Substitution)**: Từ chối chọn nhân `CHROMIUM` nếu `ChromiumAvailabilityChecker`
 *    chưa xác thực gói SDK nhị phân gốc; khi đổi nhân mặc định, chỉ áp dụng cho tab tạo mới và giữ nguyên nhân của các tab đang mở.
 * 3) **Cô lập Hồ sơ & Riêng tư**: Tách riêng hoàn toàn cookie, bộ nhớ đệm, quyền riêng tư và vòng đời thiếu bộ nhớ (`ComponentCallbacks2`)
 *    của từng engine.
 */
@Singleton
class BrowserEngineManager internal constructor(
    private val engines: Map<EngineType, BrowserEngine>,
    private val settingsRepository: SettingsRepository,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : MultiEngineSessionBridge, ComponentCallbacks2 {

    @Inject
    constructor(
        geckoEngine: GeckoBrowserEngine,
        chromiumEngine: ChromiumBrowserEngine,
        settingsRepository: SettingsRepository,
    ) : this(
        engines = linkedMapOf(
            EngineType.GECKO to geckoEngine,
            EngineType.CHROMIUM to chromiumEngine,
        ),
        settingsRepository = settingsRepository,
        mainDispatcher = Dispatchers.Main.immediate,
    )

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    @Volatile
    private var preferredDefaultEngine: EngineType = EngineType.DEFAULT

    @Volatile
    private var legacyShutdownListener: (() -> Unit)? = null

    @Volatile
    private var engineShutdownListener: ((EngineType) -> Unit)? = null

    private val _engineAvailabilities = MutableStateFlow(computeAvailabilitySnapshot())
    override val engineAvailabilities: StateFlow<Map<EngineType, EngineAvailability>> =
        _engineAvailabilities.asStateFlow()

    private val _initializedEngines = MutableStateFlow<Set<EngineType>>(emptySet())
    override val initializedEngines: StateFlow<Set<EngineType>> =
        _initializedEngines.asStateFlow()

    init {
        for ((type, engine) in engines) {
            engine.setOnRuntimeShutdownListener {
                syncInitializedEngineSet()
                engineShutdownListener?.invoke(type)
                legacyShutdownListener?.invoke()
            }
        }
        val geckoInitFlow = engineFor(EngineType.GECKO).isInitialized
        val chromiumInitFlow = engineFor(EngineType.CHROMIUM).isInitialized
        scope.launch {
            combine(geckoInitFlow, chromiumInitFlow) { geckoReady, chromiumReady ->
                buildSet {
                    if (geckoReady) add(EngineType.GECKO)
                    if (chromiumReady) add(EngineType.CHROMIUM)
                }
            }.collect { activeSet ->
                _initializedEngines.value = activeSet
            }
        }
        scope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                preferredDefaultEngine = resolveExecutableEngine(settings.preferredEngine)
            }
        }
    }

    fun engineFor(engineType: EngineType): BrowserEngine =
        engines[engineType] ?: error("Unsupported EngineType: $engineType")

    override fun refreshAvailabilities(): Map<EngineType, EngineAvailability> {
        val snapshot = computeAvailabilitySnapshot()
        _engineAvailabilities.value = snapshot
        return snapshot
    }

    override fun getAvailability(engineType: EngineType): EngineAvailability =
        _engineAvailabilities.value[engineType] ?: engineFor(engineType).checkAvailability()

    override fun getCapabilities(engineType: EngineType): EngineCapabilities =
        engineFor(engineType).capabilities

    override fun isEngineSelectable(engineType: EngineType): Boolean =
        engineFor(engineType).checkAvailability().also { latest ->
            _engineAvailabilities.value = _engineAvailabilities.value + (engineType to latest)
        }.isSelectable

    override fun resolveExecutableEngine(requested: EngineType): EngineType {
        if (isEngineSelectable(requested)) {
            return requested
        }
        if (requested != EngineType.DEFAULT && isEngineSelectable(EngineType.DEFAULT)) {
            return EngineType.DEFAULT
        }
        return EngineType.GECKO
    }

    /**
     * Đổi nhân mặc định cho các tab tạo mới (kiểm tra nghiêm ngặt tính khả dụng trước khi lưu DataStore).
     */
    suspend fun selectDefaultEngine(requested: EngineType): EngineSelectionResult {
        val availability = engineFor(requested).checkAvailability()
        _engineAvailabilities.value = _engineAvailabilities.value + (requested to availability)
        if (!availability.isSelectable) {
            return EngineSelectionResult.Rejected(
                requestedEngine = requested,
                availability = availability,
                reason = availability.unavailabilityReason
                    ?: "Nhân ${requested.displayName} hiện không khả dụng trên thiết bị này.",
            )
        }
        preferredDefaultEngine = requested
        settingsRepository.setPreferredEngine(requested)
        return EngineSelectionResult.Selected(
            engineType = requested,
            availability = availability,
        )
    }

    override fun createPlatformView(context: Context): View =
        createPlatformView(context, preferredDefaultEngine)

    override fun createPlatformView(context: Context, engineType: EngineType): View {
        val executable = resolveExecutableEngine(engineType)
        val engine = engineFor(executable)
        engine.initialize()
        syncInitializedEngineSet()
        return engine.createPlatformView(context)
    }

    override fun wrapPlatformView(platformView: View): EngineViewHandle =
        wrapPlatformView(platformView, preferredDefaultEngine)

    override fun wrapPlatformView(platformView: View, engineType: EngineType): EngineViewHandle {
        val executable = resolveExecutableEngine(engineType)
        return engineFor(executable).wrapPlatformView(platformView)
    }

    override fun createSession(
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
        callbacks: EngineSessionCallbacks,
    ): EngineSessionHandle = createSessionForEngine(
        engineType = preferredDefaultEngine,
        isPrivate = isPrivate,
        isDesktopMode = isDesktopMode,
        openImmediately = openImmediately,
        callbacks = callbacks,
    )

    override fun createSessionForEngine(
        engineType: EngineType,
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
        callbacks: EngineSessionCallbacks,
    ): EngineSessionHandle {
        val executable = resolveExecutableEngine(engineType)
        val engine = engineFor(executable)
        engine.initialize()
        syncInitializedEngineSet()
        return engine.createSession(
            isPrivate = isPrivate,
            isDesktopMode = isDesktopMode,
            openImmediately = openImmediately,
            callbacks = callbacks,
        )
    }

    override fun setOnRuntimeShutdownListener(listener: (() -> Unit)?) {
        legacyShutdownListener = listener
    }

    override fun setOnEngineShutdownListener(listener: ((EngineType) -> Unit)?) {
        engineShutdownListener = listener
    }

    override fun releasePrivateContextIfUnused(
        engineType: EngineType,
        activePrivateTabsForEngine: Int,
    ) {
        engineFor(engineType).releasePrivateContextIfUnused(activePrivateTabsForEngine)
    }

    override fun applyPageZoomSettings(autoFontSize: Boolean, zoomPercent: Int) {
        // Chỉ đồng bộ xuống những engine đã khởi tạo (không ép khởi tạo engine chưa dùng)
        for (engine in engines.values) {
            if (engine.isInitialized.value || engine.engineType == EngineType.GECKO) {
                engine.applyPageZoom(autoFontSize, zoomPercent)
            }
        }
    }

    override fun applyCookiePolicy(mode: CookieControlMode) {
        for (engine in engines.values) {
            if (engine.isInitialized.value || engine.engineType == EngineType.GECKO) {
                engine.applyCookiePolicy(mode)
            }
        }
    }

    override fun clearEngineStorage(
        engineType: EngineType,
        includeCookies: Boolean,
        includeCache: Boolean,
        onlyPrivate: Boolean,
    ) {
        engineFor(engineType).clearBrowsingStorage(
            includeCookies = includeCookies,
            includeCache = includeCache,
            onlyPrivate = onlyPrivate,
        )
    }

    override fun onLowMemory(level: Int) {
        for (engine in engines.values) {
            if (engine.isInitialized.value) {
                engine.onLowMemory(level)
            }
        }
    }

    fun destroyEngine(engineType: EngineType) {
        engineFor(engineType).destroy()
        syncInitializedEngineSet()
    }

    fun destroyAllEngines() {
        for (engine in engines.values) {
            engine.destroy()
        }
        syncInitializedEngineSet()
    }

    override fun onTrimMemory(level: Int) {
        onLowMemory(level)
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        onLowMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    private fun computeAvailabilitySnapshot(): Map<EngineType, EngineAvailability> =
        EngineType.entries.associateWith { type ->
            engineFor(type).checkAvailability()
        }

    private fun syncInitializedEngineSet() {
        _initializedEngines.value = engines.values
            .filter { it.isInitialized.value }
            .mapTo(LinkedHashSet()) { it.engineType }
    }
}
