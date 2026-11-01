package io.veko.browser.tab

import io.veko.browser.engine.EngineType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bộ mã hóa/giải mã phiên làm việc bền vững có kiểm tra phiên bản lược đồ (schemaVersion),
 * chịu lỗi từng phần tử và loại bỏ tuyệt đối mọi dữ liệu tab riêng tư (isPrivate).
 */
object SessionPersistenceCodec {

    const val CURRENT_SCHEMA_VERSION = 3
    private const val LEGACY_SCHEMA_VERSION_V2 = 2
    private const val LEGACY_SCHEMA_VERSION_V1 = 1
    const val MAX_SERIALIZED_STATE_CHARS = 2 * 1024 * 1024

    data class EncodedSessionPayload(
        val tabsJson: String,
        val activeRegularTabId: String?,
    )

    data class DecodedSessionPayload(
        val tabs: List<Tab>,
        val activeTabId: String?,
    )

    fun encodeRegularTabs(snapshot: BrowserTabsUiState): EncodedSessionPayload {
        // RÀNG BUỘC RIÊNG TƯ: Chỉ chọn tab thường; tuyệt đối không ghi bất kỳ trường nào của tab riêng tư
        val regularTabs = snapshot.tabs.filterNot { it.isPrivate }
        val tabsArray = JSONArray()
        for (tab in regularTabs) {
            check(!tab.isPrivate) { "Tab riêng tư không bao giờ được phép tuần tự hóa xuống ổ đĩa." }
            val safeUrl = UrlNavigationPolicy.sanitizeRestoredUrl(tab.sessionState.url)
            val safeTitle = tab.sessionState.title.trim().ifBlank { "Trang mới" }
            val item = JSONObject().apply {
                put("id", tab.id)
                put("url", safeUrl)
                put("title", safeTitle)
                put("engineType", tab.engineType.storageKey)
                put("isDesktopMode", tab.sessionState.isDesktopMode)
                put("lastAccessed", tab.lastAccessedEpochMs)
                tab.sessionState.serializedEngineState?.let { stateStr ->
                    if (stateStr.isNotBlank() && stateStr.length <= MAX_SERIALIZED_STATE_CHARS) {
                        put("engineState", stateStr)
                    }
                }
            }
            tabsArray.put(item)
        }

        val root = JSONObject().apply {
            put("schemaVersion", CURRENT_SCHEMA_VERSION)
            put("tabs", tabsArray)
        }

        val activeRegularId = snapshot.activeTabId
            ?.takeIf { id -> regularTabs.any { it.id == id } }
            ?: regularTabs.firstOrNull()?.id

        return EncodedSessionPayload(
            tabsJson = root.toString(),
            activeRegularTabId = activeRegularId,
        )
    }

    fun decodePersistedTabs(
        rawJson: String?,
        persistedActiveId: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): DecodedSessionPayload {
        val trimmed = rawJson?.trim()
        if (trimmed.isNullOrEmpty()) return DecodedSessionPayload(emptyList(), null)

        val tabsArray: JSONArray = runCatching {
            if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                val version = root.optInt("schemaVersion", -1)
                if (version != CURRENT_SCHEMA_VERSION &&
                    version != LEGACY_SCHEMA_VERSION_V2 &&
                    version != LEGACY_SCHEMA_VERSION_V1
                ) {
                    return DecodedSessionPayload(emptyList(), null)
                }
                root.optJSONArray("tabs") ?: return DecodedSessionPayload(emptyList(), null)
            } else if (trimmed.startsWith("[")) {
                // Tương thích ngược với định dạng JSONArray v1 trước đó mà không làm mất tab người dùng
                JSONArray(trimmed)
            } else {
                return DecodedSessionPayload(emptyList(), null)
            }
        }.getOrElse {
            return DecodedSessionPayload(emptyList(), null)
        }

        val seenIds = HashSet<String>()
        val restoredTabs = ArrayList<Tab>(tabsArray.length())

        for (index in 0 until tabsArray.length()) {
            val obj = tabsArray.optJSONObject(index) ?: continue
            // Chốt chặn bổ sung: nếu dữ liệu trên đĩa bị sửa đổi chứa isPrivate=true thì bỏ qua ngay
            if (obj.optBoolean("isPrivate", false)) continue

            val id = obj.optString("id", "").trim()
            if (id.isEmpty() || !seenIds.add(id)) continue

            val rawUrl = obj.optString("url", "")
            val sanitizedUrl = UrlNavigationPolicy.sanitizeRestoredUrl(rawUrl)
            val rawTitle = obj.optString("title", "").trim().ifBlank { "Trang mới" }
            val restoredEngine = EngineType.fromStorageKey(obj.optString("engineType", EngineType.GECKO.storageKey))
            val isDesktop = obj.optBoolean("isDesktopMode", false)
            val lastAccessed = obj.optLong("lastAccessed", nowEpochMs).takeIf { it > 0L } ?: nowEpochMs
            val rawEngineState = obj.optString("engineState", "").takeIf {
                it.isNotBlank() && it.length <= MAX_SERIALIZED_STATE_CHARS
            }

            restoredTabs.add(
                Tab(
                    id = id,
                    isPrivate = false,
                    engineType = restoredEngine,
                    createdAtEpochMs = lastAccessed,
                    lastAccessedEpochMs = lastAccessed,
                    accessSequence = index.toLong(),
                    sessionState = TabSessionState(
                        url = sanitizedUrl,
                        title = rawTitle,
                        isDesktopMode = isDesktop,
                        serializedEngineState = rawEngineState,
                    ),
                ),
            )
        }

        val validActiveId = persistedActiveId?.takeIf { activeId ->
            restoredTabs.any { it.id == activeId }
        } ?: restoredTabs.firstOrNull()?.id

        return DecodedSessionPayload(
            tabs = restoredTabs,
            activeTabId = validActiveId,
        )
    }
}
