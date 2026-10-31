package io.veko.browser.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import io.veko.browser.tab.UrlNavigationPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELED,
}

@Immutable
data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val fileName: String,
    val mimeType: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val isPrivate: Boolean = false,
    val contentUriString: String? = null,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    val canRetry: Boolean
        get() = (status == DownloadStatus.FAILED || status == DownloadStatus.CANCELED) &&
            UrlNavigationPolicy.isRecordableHttpUrl(url)

    val isInProgress: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING

    val progressFraction: Float?
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}

sealed interface DownloadNotificationEvent {
    data class Started(val fileName: String) : DownloadNotificationEvent
    data class Completed(val item: DownloadItem) : DownloadNotificationEvent
    data class Failed(val fileName: String, val reason: String) : DownloadNotificationEvent
}

/**
 * Gói phản hồi tải xuống truyền từ GeckoSession.ContentDelegate.onExternalResponse.
 */
class ExternalDownloadPayload(
    val uri: String,
    val contentType: String?,
    val contentDisposition: String?,
    val contentLength: Long,
    val isPrivate: Boolean,
    val bodyStream: InputStream?,
)

/**
 * Giao diện đích lưu trữ tệp tải xuống theo Scoped Storage hiện đại (không đòi quyền WRITE_EXTERNAL_STORAGE).
 */
interface DownloadStorageSink {
    interface Session : AutoCloseable {
        val outputStream: OutputStream
        fun commitCompleted(): String?
        fun abortAndDelete()
    }

    fun openSink(uniqueFileName: String, mimeType: String): Session
    fun existingFileNames(): Set<String>
}

private class AndroidScopedDownloadStorageSink(
    private val appContext: Context,
) : DownloadStorageSink {

    override fun existingFileNames(): Set<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val names = HashSet<String>()
            runCatching {
                val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
                appContext.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val col = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                    while (col >= 0 && cursor.moveToNext()) {
                        cursor.getString(col)?.let { names.add(it) }
                    }
                }
            }
            return names
        }
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir
        return dir.listFiles()?.mapNotNullTo(HashSet()) { it.name }.orEmpty()
    }

    override fun openSink(uniqueFileName: String, mimeType: String): DownloadStorageSink.Session {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, uniqueFileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val itemUri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Không thể khởi tạo tệp trong MediaStore.Downloads")
            val stream = resolver.openOutputStream(itemUri)
                ?: run {
                    runCatching { resolver.delete(itemUri, null, null) }
                    throw IOException("Không thể mở luồng ghi MediaStore")
                }

            return object : DownloadStorageSink.Session {
                override val outputStream: OutputStream = stream

                override fun commitCompleted(): String {
                    runCatching { stream.flush() }
                    runCatching { stream.close() }
                    val publishValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, publishValues, null, null)
                    return itemUri.toString()
                }

                override fun abortAndDelete() {
                    runCatching { stream.close() }
                    runCatching { resolver.delete(itemUri, null, null) }
                }

                override fun close() {
                    runCatching { stream.close() }
                }
            }
        }

        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        val tempFile = File(dir, "$uniqueFileName.part")
        val finalFile = File(dir, uniqueFileName)
        val fos = FileOutputStream(tempFile)

        return object : DownloadStorageSink.Session {
            override val outputStream: OutputStream = fos

            override fun commitCompleted(): String? {
                runCatching { fos.flush() }
                runCatching { fos.close() }
                if (finalFile.exists()) finalFile.delete()
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }
                return runCatching {
                    FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        finalFile,
                    ).toString()
                }.getOrNull()
            }

            override fun abortAndDelete() {
                runCatching { fos.close() }
                runCatching { tempFile.delete() }
            }

            override fun close() {
                runCatching { fos.close() }
            }
        }
    }
}

/**
 * Bộ khử độc tên tệp, MIME type và chống ghi đè trùng tên (pure-JDK, kiểm thử trực tiếp trên JVM).
 */
object DownloadFileNameSanitizer {

    private const val DEFAULT_BASE_NAME = "download"
    private const val MAX_FILE_NAME_LENGTH = 120
    private val ILLEGAL_FILENAME_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F\u200E\u200F\u202A-\u202E\u2066-\u2069]""")

    private val MIME_TO_EXT = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/x-zip-compressed" to "zip",
        "application/vnd.android.package-archive" to "apk",
        "application/json" to "json",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "text/html" to "html",
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "audio/mpeg" to "mp3",
        "audio/ogg" to "ogg",
    )

    private val EXT_TO_MIME = MIME_TO_EXT.entries.associate { (mime, ext) -> ext to mime } + mapOf(
        "jpeg" to "image/jpeg",
        "htm" to "text/html",
    )

    fun sanitizeMimeType(rawContentType: String?, fileName: String): String =
        DownloadMimeValidator.validate(rawContentType, fileName).effectiveMimeType

    fun resolveSafeFileName(
        url: String,
        contentDisposition: String?,
        contentType: String?,
    ): String {
        val fromHeader = parseContentDispositionFileName(contentDisposition)
        val fromUrl = extractUrlPathFileName(url)
        val rawCandidate = (fromHeader ?: fromUrl ?: DEFAULT_BASE_NAME).trim()

        // Tách lấy tên cuối cùng, loại bỏ hoàn toàn chuỗi leo thư mục (../ hoặc ..\) và ký tự điều khiển/bidi
        val leaf = rawCandidate
            .replace('\\', '/')
            .substringAfterLast('/')
            .let { ILLEGAL_FILENAME_CHARS.replace(it, "_") }
            .replace(Regex("""\.{2,}"""), ".")
            .trim()
            .trimStart('.')
            .trimEnd('.', ' ')
            .replace(Regex("""_{2,}"""), "_")
            .ifBlank { DEFAULT_BASE_NAME }

        val cleanMime = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val mappedExt = cleanMime?.let { MIME_TO_EXT[it] }

        val dotIdx = leaf.lastIndexOf('.')
        val hasValidExt = dotIdx > 0 && dotIdx < leaf.length - 1 && (leaf.length - dotIdx - 1) in 1..10
        val basePart: String
        val extPart: String?
        if (hasValidExt) {
            basePart = leaf.substring(0, dotIdx).ifBlank { DEFAULT_BASE_NAME }
            extPart = leaf.substring(dotIdx + 1)
        } else {
            basePart = leaf
            extPart = mappedExt
        }

        val suffix = if (!extPart.isNullOrBlank()) ".${extPart.take(10)}" else ""
        val maxBaseLen = (MAX_FILE_NAME_LENGTH - suffix.length).coerceAtLeast(8)
        return basePart.take(maxBaseLen) + suffix
    }

    fun deduplicateFileName(desiredFileName: String, existingNames: Set<String>): String {
        val lowerExisting = existingNames.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        if (desiredFileName.lowercase(Locale.ROOT) !in lowerExisting) {
            return desiredFileName
        }

        val dotIdx = desiredFileName.lastIndexOf('.')
        val base = if (dotIdx > 0) desiredFileName.substring(0, dotIdx) else desiredFileName
        val ext = if (dotIdx > 0) desiredFileName.substring(dotIdx) else ""

        var counter = 1
        while (counter < 10_000) {
            val candidate = "$base ($counter)$ext"
            if (candidate.lowercase(Locale.ROOT) !in lowerExisting) {
                return candidate
            }
            counter++
        }
        return "${base}_${UUID.randomUUID().toString().take(6)}$ext"
    }

    private fun parseContentDispositionFileName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        // Ưu tiên chuẩn RFC 5987 filename*=UTF-8''...
        val rfc5987Regex = Regex("""(?i)filename\*\s*=\s*(?:UTF-8''|ISO-8859-1'')?([^;\r\n]+)""")
        rfc5987Regex.find(header)?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'')?.let { encoded ->
            val decoded = runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }.getOrDefault(encoded)
            if (decoded.isNotBlank()) return decoded
        }

        val basicRegex = Regex("""(?i)filename\s*=\s*"([^"]+)"|filename\s*=\s*([^;\s]+)""")
        val match = basicRegex.find(header) ?: return null
        val raw = match.groupValues[1].ifBlank { match.groupValues[2] }.trim().trim('"', '\'')
        return raw.takeIf { it.isNotBlank() }
    }

    private fun extractUrlPathFileName(url: String): String? {
        val withoutQuery = url.substringBefore('#').substringBefore('?')
        val afterAuthority = withoutQuery.substringAfter("://", missingDelimiterValue = "")
        val path = afterAuthority.substringAfter('/', missingDelimiterValue = "")
        if (path.isBlank()) return null
        val lastSeg = path.substringAfterLast('/').trim()
        if (lastSeg.isBlank()) return null
        return runCatching { URLDecoder.decode(lastSeg, StandardCharsets.UTF_8.name()) }
            .getOrDefault(lastSeg)
            .takeIf { it.isNotBlank() }
    }
}

/**
 * Trình quản lý tải xuống tích hợp trực tiếp với GeckoView WebResponse:
 * - Tận dụng ngay luồng dữ liệu đã xác thực (cookies/session) từ GeckoView khi có sẵn.
 * - Hỗ trợ theo dõi tiến trình thời gian thực, hủy tải (Cancel), tải lại khi lỗi (Retry), xử lý trùng tên và dọn sạch tệp dở dang.
 */
class VekoDownloadManager(
    private val storageSink: DownloadStorageSink,
    private val httpStreamOpener: (String) -> RemoteHttpStream,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    data class RemoteHttpStream(
        val inputStream: InputStream,
        val contentType: String?,
        val contentDisposition: String?,
        val contentLength: Long,
        val closeConnection: () -> Unit,
    )

    constructor(
        appContext: Context,
    ) : this(
        storageSink = AndroidScopedDownloadStorageSink(appContext.applicationContext),
        httpStreamOpener = ::openDefaultHttpStream,
        ioDispatcher = Dispatchers.IO,
        clockMs = { System.currentTimeMillis() },
    )

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val nameLock = Any()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _events = MutableSharedFlow<DownloadNotificationEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DownloadNotificationEvent> = _events.asSharedFlow()

    /**
     * Nhận phản hồi tải xuống trực tiếp từ GeckoSession.ContentDelegate.onExternalResponse.
     */
    fun enqueueExternalResponse(payload: ExternalDownloadPayload): String? {
        val trimmedUri = payload.uri.trim()
        val isHttpSource = UrlNavigationPolicy.isRecordableHttpUrl(trimmedUri)
        if (!isHttpSource && payload.bodyStream == null) {
            runCatching { payload.bodyStream?.close() }
            return null
        }

        val initialName = DownloadFileNameSanitizer.resolveSafeFileName(
            url = trimmedUri,
            contentDisposition = payload.contentDisposition,
            contentType = payload.contentType,
        )
        val uniqueName = allocateUniqueFileName(initialName)
        val safeMime = DownloadFileNameSanitizer.sanitizeMimeType(payload.contentType, uniqueName)

        val item = DownloadItem(
            url = trimmedUri,
            fileName = uniqueName,
            mimeType = safeMime,
            downloadedBytes = 0L,
            totalBytes = payload.contentLength.takeIf { it > 0L } ?: -1L,
            status = DownloadStatus.QUEUED,
            isPrivate = payload.isPrivate,
            createdAtEpochMs = clockMs(),
        )

        _downloads.update { listOf(item) + it }
        _events.tryEmit(DownloadNotificationEvent.Started(item.fileName))

        val job = scope.launch {
            executeDownload(item = item, providedStream = payload.bodyStream)
        }
        activeJobs[item.id] = job
        return item.id
    }

    /**
     * Tải xuống một liên kết hoặc hình ảnh từ menu ngữ cảnh giữ lâu (Long-Press Context Menu).
     */
    fun enqueueUrlDownload(url: String, isPrivate: Boolean): String? {
        if (!UrlNavigationPolicy.isRecordableHttpUrl(url)) return null
        return enqueueExternalResponse(
            ExternalDownloadPayload(
                uri = url.trim(),
                contentType = null,
                contentDisposition = null,
                contentLength = -1L,
                isPrivate = isPrivate,
                bodyStream = null,
            ),
        )
    }

    fun cancelDownload(downloadId: String) {
        activeJobs.remove(downloadId)?.cancel()
        updateItem(downloadId) { current ->
            if (current.status == DownloadStatus.QUEUED || current.status == DownloadStatus.DOWNLOADING) {
                current.copy(status = DownloadStatus.CANCELED, errorMessage = "Đã hủy bởi người dùng")
            } else {
                current
            }
        }
    }

    fun retryDownload(downloadId: String): String? {
        val target = _downloads.value.firstOrNull { it.id == downloadId } ?: return null
        if (!target.canRetry) return null
        removeDownloadRecord(downloadId)
        return enqueueUrlDownload(url = target.url, isPrivate = target.isPrivate)
    }

    fun removeDownloadRecord(downloadId: String) {
        activeJobs.remove(downloadId)?.cancel()
        _downloads.update { list -> list.filterNot { it.id == downloadId } }
    }

    fun clearFinishedDownloads(onlyPrivate: Boolean = false) {
        _downloads.update { list ->
            list.filter { item ->
                val isRunning = item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.DOWNLOADING
                if (isRunning) true else if (onlyPrivate) !item.isPrivate else false
            }
        }
    }

    private fun allocateUniqueFileName(desired: String): String = synchronized(nameLock) {
        val inMemoryNames = _downloads.value
            .filter { it.status != DownloadStatus.CANCELED && it.status != DownloadStatus.FAILED }
            .mapTo(HashSet()) { it.fileName }
        val storageNames = runCatching { storageSink.existingFileNames() }.getOrDefault(emptySet())
        DownloadFileNameSanitizer.deduplicateFileName(desired, inMemoryNames + storageNames)
    }

    private suspend fun executeDownload(item: DownloadItem, providedStream: InputStream?) {
        var remoteConnCloser: (() -> Unit)? = null
        var sinkSession: DownloadStorageSink.Session? = null

        try {
            updateItem(item.id) { it.copy(status = DownloadStatus.DOWNLOADING, errorMessage = null) }

            val activeStream: InputStream
            var resolvedFileName = item.fileName
            var resolvedMime = item.mimeType
            var resolvedTotal = item.totalBytes

            if (providedStream != null) {
                activeStream = providedStream
            } else {
                val opened = withContext(ioDispatcher) { httpStreamOpener(item.url) }
                remoteConnCloser = opened.closeConnection
                activeStream = opened.inputStream
                if (!opened.contentDisposition.isNullOrBlank() || !opened.contentType.isNullOrBlank()) {
                    val refined = DownloadFileNameSanitizer.resolveSafeFileName(
                        url = item.url,
                        contentDisposition = opened.contentDisposition,
                        contentType = opened.contentType,
                    )
                    if (refined != item.fileName) {
                        resolvedFileName = allocateUniqueFileName(refined)
                    }
                    resolvedMime = DownloadFileNameSanitizer.sanitizeMimeType(opened.contentType, resolvedFileName)
                }
                if (opened.contentLength > 0L) {
                    resolvedTotal = opened.contentLength
                }
                updateItem(item.id) {
                    it.copy(fileName = resolvedFileName, mimeType = resolvedMime, totalBytes = resolvedTotal)
                }
            }

            sinkSession = withContext(ioDispatcher) {
                storageSink.openSink(uniqueFileName = resolvedFileName, mimeType = resolvedMime)
            }

            var bytesCopied = 0L
            var lastProgressEmitMs = 0L
            val buffer = ByteArray(STREAM_BUFFER_BYTES)

            activeStream.use { input ->
                val out = sinkSession.outputStream
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        bytesCopied += read
                        val now = clockMs()
                        if (now - lastProgressEmitMs >= PROGRESS_THROTTLE_MS) {
                            lastProgressEmitMs = now
                            updateItem(item.id) { it.copy(downloadedBytes = bytesCopied) }
                        }
                    }
                }
            }

            val publishedUri = withContext(ioDispatcher) { sinkSession.commitCompleted() }
            var completedSnapshot: DownloadItem? = null
            updateItem(item.id) { current ->
                current.copy(
                    downloadedBytes = bytesCopied,
                    totalBytes = if (current.totalBytes > 0L) current.totalBytes else bytesCopied,
                    status = DownloadStatus.COMPLETED,
                    contentUriString = publishedUri,
                ).also { completedSnapshot = it }
            }
            completedSnapshot?.let { _events.tryEmit(DownloadNotificationEvent.Completed(it)) }
        } catch (ce: CancellationException) {
            runCatching { providedStream?.close() }
            runCatching { sinkSession?.abortAndDelete() }
            updateItem(item.id) { current ->
                if (current.status != DownloadStatus.CANCELED) {
                    current.copy(status = DownloadStatus.CANCELED, errorMessage = "Đã hủy")
                } else {
                    current
                }
            }
            throw ce
        } catch (t: Throwable) {
            runCatching { providedStream?.close() }
            runCatching { sinkSession?.abortAndDelete() }
            val safeReason = t.message?.take(140)?.takeIf { it.isNotBlank() } ?: "Lỗi kết nối hoặc lưu tệp"
            var failedName = item.fileName
            updateItem(item.id) { current ->
                failedName = current.fileName
                current.copy(status = DownloadStatus.FAILED, errorMessage = safeReason)
            }
            _events.tryEmit(DownloadNotificationEvent.Failed(failedName, safeReason))
        } finally {
            activeJobs.remove(item.id)
            runCatching { remoteConnCloser?.invoke() }
        }
    }

    private fun updateItem(id: String, transform: (DownloadItem) -> DownloadItem) {
        _downloads.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 32 * 1024
        const val PROGRESS_THROTTLE_MS = 180L

        fun openDefaultHttpStream(url: String): RemoteHttpStream {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:150.0) Gecko/150.0 Firefox/150.0")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            return RemoteHttpStream(
                inputStream = conn.inputStream,
                contentType = conn.contentType,
                contentDisposition = conn.getHeaderField("Content-Disposition"),
                contentLength = conn.contentLengthLong,
                closeConnection = { conn.disconnect() },
            )
        }
    }
}
