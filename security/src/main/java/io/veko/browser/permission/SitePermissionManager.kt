package io.veko.browser.permission

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.veko.browser.tab.UrlNavigationPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

// DataStore lưu quyền trang web cho tab thường (tự động loại trừ khỏi Cloud Backup theo allowlist)
private val Context.vekoSitePermissionsStore: DataStore<Preferences> by preferencesDataStore(
    name = "veko_site_permissions",
)

enum class SitePermissionType(val displayLabel: String) {
    GEOLOCATION("Vị trí (GPS)"),
    CAMERA("Máy ảnh (Camera)"),
    MICROPHONE("Micro thu âm"),
    NOTIFICATION("Thông báo trang web"),
    PERSISTENT_STORAGE("Bộ nhớ lưu trữ bền vững"),
}

enum class SitePermissionDecision {
    PROMPT,
    ALLOW,
    DENY,
}

@Immutable
data class OriginPermissionsRecord(
    val origin: String,
    val decisions: Map<SitePermissionType, SitePermissionDecision>,
)

@Immutable
data class SitePermissionPrompt(
    val id: String,
    val tabId: String,
    val origin: String,
    val isPrivate: Boolean,
    val requestedTypes: List<SitePermissionType>,
    val onResult: (allow: Boolean, remember: Boolean) -> Unit,
)

interface SitePermissionBackingStore {
    val rawJsonFlow: Flow<String?>
    suspend fun writeRawJson(rawJson: String)
}

private class DataStoreSitePermissionBackingStore(
    private val appContext: Context,
) : SitePermissionBackingStore {
    override val rawJsonFlow: Flow<String?> = appContext.vekoSitePermissionsStore.data.map { prefs ->
        prefs[KEY_PERMISSIONS_JSON]
    }

    override suspend fun writeRawJson(rawJson: String) {
        appContext.vekoSitePermissionsStore.edit { prefs ->
            prefs[KEY_PERMISSIONS_JSON] = rawJson
        }
    }

    private companion object {
        val KEY_PERMISSIONS_JSON = stringPreferencesKey("origin_permissions_v1")
    }
}

/**
 * Quản lý quyền truy cập tài nguyên nhạy cảm (Camera, Micro, Vị trí, Thông báo) theo từng Origin:
 * - Mặc định luôn là PROMPT (chỉ cấp khi người dùng đồng ý rõ ràng).
 * - Ở chế độ Riêng tư (Private Mode), quyết định chỉ tồn tại trong bộ nhớ RAM và bị xóa sạch khi đóng tab riêng tư.
 * - Hỗ trợ xem danh sách và thu hồi (revoke) từng quyền hoặc toàn bộ quyền của một website bất kỳ lúc nào.
 */
class SitePermissionManager(
    private val backingStore: SitePermissionBackingStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        appContext: Context,
    ) : this(
        backingStore = DataStoreSitePermissionBackingStore(appContext.applicationContext),
        ioDispatcher = Dispatchers.IO,
    )

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writeMutex = Mutex()
    private val stateLock = Any()

    // Quyền đã lưu cho tab thường: origin -> (type -> ALLOW/DENY)
    private val regularDecisions = LinkedHashMap<String, MutableMap<SitePermissionType, SitePermissionDecision>>()

    // Quyền tạm thời trên RAM cho phiên Riêng Tư (tuyệt đối không chạm đĩa)
    private val privateSessionDecisions = LinkedHashMap<String, MutableMap<SitePermissionType, SitePermissionDecision>>()

    private val _savedOrigins = MutableStateFlow<List<OriginPermissionsRecord>>(emptyList())
    val savedOrigins: StateFlow<List<OriginPermissionsRecord>> = _savedOrigins.asStateFlow()
    private val _changeVersion = MutableStateFlow(0L)

    fun observeOriginPermissions(
        rawUrlOrOrigin: String?,
        isPrivate: Boolean,
    ): Flow<Map<SitePermissionType, SitePermissionDecision>> =
        _changeVersion.map { getOriginPermissions(rawUrlOrOrigin, isPrivate) }

    init {
        scope.launch {
            backingStore.rawJsonFlow.collect { rawJson ->
                val decoded = decodePermissionsJson(rawJson)
                synchronized(stateLock) {
                    regularDecisions.clear()
                    for ((origin, map) in decoded) {
                        regularDecisions[origin] = LinkedHashMap(map)
                    }
                    publishRegularSnapshotLocked()
                    _changeVersion.value = _changeVersion.value + 1L
                }
            }
        }
    }

    /**
     * Tra cứu quyết định hiện hành cho một (rawUrlOrOrigin, type); các origin không hợp lệ hoặc HTTP không mã hóa cho Camera/Mic bị chặn an toàn.
     */
    fun getDecision(
        rawUrlOrOrigin: String?,
        type: SitePermissionType,
        isPrivate: Boolean,
    ): SitePermissionDecision {
        val origin = UrlNavigationPolicy.extractOrigin(rawUrlOrOrigin) ?: return SitePermissionDecision.DENY
        // Chặn cấp Camera/Micro/Geolocation trên trang HTTP không mã hóa (trừ localhost/127.0.0.1 phục vụ phát triển)
        if (!isSecureContextOrigin(origin) && type in SECURE_CONTEXT_REQUIRED_TYPES) {
            return SitePermissionDecision.DENY
        }
        synchronized(stateLock) {
            if (isPrivate) {
                privateSessionDecisions[origin]?.get(type)?.let { return it }
                return SitePermissionDecision.PROMPT
            }
            return regularDecisions[origin]?.get(type) ?: SitePermissionDecision.PROMPT
        }
    }

    fun getOriginPermissions(rawUrlOrOrigin: String?, isPrivate: Boolean): Map<SitePermissionType, SitePermissionDecision> {
        val origin = UrlNavigationPolicy.extractOrigin(rawUrlOrOrigin) ?: return emptyMap()
        return synchronized(stateLock) {
            val source = if (isPrivate) privateSessionDecisions[origin] else regularDecisions[origin]
            source?.toMap().orEmpty()
        }
    }

    fun recordDecision(
        rawUrlOrOrigin: String?,
        types: Collection<SitePermissionType>,
        decision: SitePermissionDecision,
        isPrivate: Boolean,
        remember: Boolean = true,
    ) {
        val origin = UrlNavigationPolicy.extractOrigin(rawUrlOrOrigin) ?: return
        if (types.isEmpty()) return

        // Nếu người dùng chỉ cho phép 1 lần (remember = false) ở tab thường thì không lưu bền vững xuống đĩa
        if (!remember && !isPrivate) return

        var snapshotJsonToPersist: String? = null
        synchronized(stateLock) {
            val targetStore = if (isPrivate) privateSessionDecisions else regularDecisions
            val perOrigin = targetStore.getOrPut(origin) { LinkedHashMap() }
            for (type in types) {
                if (decision == SitePermissionDecision.PROMPT) {
                    perOrigin.remove(type)
                } else if (!isSecureContextOrigin(origin) && type in SECURE_CONTEXT_REQUIRED_TYPES && decision == SitePermissionDecision.ALLOW) {
                    perOrigin[type] = SitePermissionDecision.DENY
                } else {
                    perOrigin[type] = decision
                }
            }
            if (perOrigin.isEmpty()) {
                targetStore.remove(origin)
            }
            if (!isPrivate) {
                publishRegularSnapshotLocked()
                snapshotJsonToPersist = encodePermissionsJsonLocked()
            }
            _changeVersion.value = _changeVersion.value + 1L
        }

        snapshotJsonToPersist?.let { json ->
            scope.launch {
                writeMutex.withLock {
                    withContext(ioDispatcher) {
                        backingStore.writeRawJson(json)
                    }
                }
            }
        }
    }

    fun revokePermission(rawUrlOrOrigin: String?, type: SitePermissionType, isPrivate: Boolean = false) {
        recordDecision(
            rawUrlOrOrigin = rawUrlOrOrigin,
            types = listOf(type),
            decision = SitePermissionDecision.PROMPT,
            isPrivate = isPrivate,
            remember = true,
        )
    }

    fun revokeAllForOrigin(rawUrlOrOrigin: String?) {
        val origin = UrlNavigationPolicy.extractOrigin(rawUrlOrOrigin) ?: return
        var snapshotJson: String? = null
        synchronized(stateLock) {
            privateSessionDecisions.remove(origin)
            if (regularDecisions.remove(origin) != null) {
                publishRegularSnapshotLocked()
                snapshotJson = encodePermissionsJsonLocked()
            }
            _changeVersion.value = _changeVersion.value + 1L
        }
        snapshotJson?.let { json ->
            scope.launch {
                writeMutex.withLock { backingStore.writeRawJson(json) }
            }
        }
    }

    fun clearPrivateSessionPermissions() {
        synchronized(stateLock) {
            privateSessionDecisions.clear()
            _changeVersion.value = _changeVersion.value + 1L
        }
    }

    fun clearAllSavedPermissions() {
        val snapshotJson: String
        synchronized(stateLock) {
            regularDecisions.clear()
            privateSessionDecisions.clear()
            publishRegularSnapshotLocked()
            snapshotJson = encodePermissionsJsonLocked()
            _changeVersion.value = _changeVersion.value + 1L
        }
        scope.launch {
            writeMutex.withLock { backingStore.writeRawJson(snapshotJson) }
        }
    }

    fun isSecureContextOrigin(origin: String): Boolean {
        if (origin.startsWith("https://", ignoreCase = true)) return true
        val host = origin.substringAfter("://").substringBefore(':')
        return host.equals("localhost", ignoreCase = true) ||
            host == "127.0.0.1" ||
            host == "[::1]"
    }

    private fun publishRegularSnapshotLocked() {
        _savedOrigins.value = regularDecisions.entries
            .filter { it.value.isNotEmpty() }
            .map { (origin, map) -> OriginPermissionsRecord(origin = origin, decisions = LinkedHashMap(map)) }
    }

    private fun encodePermissionsJsonLocked(): String {
        val root = JSONObject()
        val originsObj = JSONObject()
        for ((origin, map) in regularDecisions) {
            if (map.isEmpty()) continue
            val itemObj = JSONObject()
            for ((type, decision) in map) {
                if (decision != SitePermissionDecision.PROMPT) {
                    itemObj.put(type.name, decision.name)
                }
            }
            originsObj.put(origin, itemObj)
        }
        root.put("schemaVersion", 1)
        root.put("origins", originsObj)
        return root.toString()
    }

    private fun decodePermissionsJson(rawJson: String?): Map<String, Map<SitePermissionType, SitePermissionDecision>> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(rawJson)
            val originsObj = root.optJSONObject("origins") ?: return emptyMap()
            val result = LinkedHashMap<String, Map<SitePermissionType, SitePermissionDecision>>()
            val keys = originsObj.keys()
            while (keys.hasNext()) {
                val rawOrigin = keys.next()
                val validOrigin = UrlNavigationPolicy.extractOrigin(rawOrigin) ?: continue
                val permObj = originsObj.optJSONObject(rawOrigin) ?: continue
                val perOrigin = LinkedHashMap<SitePermissionType, SitePermissionDecision>()
                for (type in SitePermissionType.entries) {
                    val rawVal = permObj.optString(type.name, "")
                    val parsed = SitePermissionDecision.entries.firstOrNull { it.name == rawVal }
                    if (parsed == SitePermissionDecision.ALLOW || parsed == SitePermissionDecision.DENY) {
                        perOrigin[type] = parsed
                    }
                }
                if (perOrigin.isNotEmpty()) {
                    result[validOrigin] = perOrigin
                }
            }
            result
        }.getOrDefault(emptyMap())
    }

    companion object {
        private val SECURE_CONTEXT_REQUIRED_TYPES = setOf(
            SitePermissionType.CAMERA,
            SitePermissionType.MICROPHONE,
            SitePermissionType.GEOLOCATION,
        )
    }
}
