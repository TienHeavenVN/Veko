package io.veko.browser.download

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private class FakeOpenedSinkHandle(
    override val finalFileName: String,
    val buffer: ByteArrayOutputStream = ByteArrayOutputStream(),
) : OpenedDownloadSink {
    var committedUri: String? = null
        private set
    var discarded: Boolean = false
        private set

    override val outputStream: OutputStream = buffer

    override fun commitSuccess(): String {
        val uri = "content://io.veko.browser.test/downloads/$finalFileName"
        committedUri = uri
        return uri
    }

    override fun discardPartial() {
        discarded = true
        buffer.reset()
    }
}

private class FakeDownloadStorageSink : DownloadStorageSink {
    private val existingNames = LinkedHashSet<String>()
    val openedHandles = mutableListOf<FakeOpenedSinkHandle>()

    override fun openTarget(baseSanitizedName: String, mimeType: String): OpenedDownloadSink {
        val unique = DownloadFileNameSanitizer.deduplicateFileName(baseSanitizedName) { it in existingNames }
        existingNames += unique
        return FakeOpenedSinkHandle(unique).also { openedHandles += it }
    }
}

private class FailingMidStreamInputStream(
    private val prefix: ByteArray,
) : InputStream() {
    private var offset = 0
    var closed: Boolean = false
        private set

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (offset < prefix.size) {
            val count = minOf(len, prefix.size - offset)
            System.arraycopy(prefix, offset, b, off, count)
            offset += count
            return count
        }
        throw IOException("Simulated network connection reset during download")
    }

    override fun read(): Int {
        val single = ByteArray(1)
        val n = read(single, 0, 1)
        return if (n == -1) -1 else (single[0].toInt() and 0xFF)
    }

    override fun close() {
        closed = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VekoDownloadManagerTest {

    @Test
    fun `DownloadFileNameSanitizer strips path traversal, parses RFC5987 headers, infers extensions and deduplicates`() {
        // Ngăn chặn tấn công vượt thư mục (path traversal) và ký tự điều khiển
        val sanitizedTraversal = DownloadFileNameSanitizer.resolveSafeFileName(
            url = "https://cdn.example.com/files/archive",
            contentDisposition = """attachment; filename="../../etc/\u0000evil..pdf"""",
            rawMimeType = "application/pdf",
        )
        assertFalse(sanitizedTraversal.contains(".."))
        assertFalse(sanitizedTraversal.contains("/"))
        assertFalse(sanitizedTraversal.contains("\\"))
        assertTrue(sanitizedTraversal.endsWith(".pdf"))

        // Hỗ trợ tên tệp UTF-8 theo chuẩn RFC 5987 filename*=UTF-8''
        val utf8Name = DownloadFileNameSanitizer.resolveSafeFileName(
            url = "https://example.com/dl?id=99",
            contentDisposition = "attachment; filename*=UTF-8''B%C3%A1o%20c%C3%A1o%20Veko.pdf",
            rawMimeType = "application/pdf",
        )
        assertEquals("Báo cáo Veko.pdf", utf8Name)

        // Tự động gắn phần mở rộng theo MIME khi URL không có đuôi tệp
        val inferredPng = DownloadFileNameSanitizer.resolveSafeFileName(
            url = "https://images.example.com/render?token=abc",
            contentDisposition = null,
            rawMimeType = "image/png",
        )
        assertEquals("render.png", inferredPng)

        // Chống ghi đè khi trùng tên tệp
        val existing = setOf("report.pdf", "report (1).pdf")
        val deduped = DownloadFileNameSanitizer.deduplicateFileName("report.pdf") { it in existing }
        assertEquals("report (2).pdf", deduped)
    }

    @Test
    fun `enqueueExternalResponse streams bytes, deduplicates duplicate names, and cleans up partial files on failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = FakeDownloadStorageSink()
        val manager = VekoDownloadManager(
            storageSink = sink,
            ioDispatcher = dispatcher,
            clockMs = { 123_456L },
        )

        val contentBytes = "Hello Veko GeckoView Download Stream!".repeat(400).toByteArray()
        val id1 = manager.enqueueExternalResponse(
            ExternalDownloadPayload(
                uri = "https://releases.example.org/files/manual.pdf",
                statusCode = 200,
                headers = mapOf(
                    "Content-Type" to "application/pdf",
                    "Content-Length" to contentBytes.size.toString(),
                    "Content-Disposition" to "attachment; filename=\"manual.pdf\"",
                ),
                bodyStream = ByteArrayInputStream(contentBytes),
                isPrivate = false,
            ),
        )
        // Tải lần 2 trùng tên để kiểm tra tự đánh số manual (1).pdf
        val id2 = manager.enqueueExternalResponse(
            ExternalDownloadPayload(
                uri = "https://releases.example.org/files/manual.pdf",
                statusCode = 200,
                headers = mapOf(
                    "Content-Type" to "application/pdf",
                    "Content-Disposition" to "attachment; filename=\"manual.pdf\"",
                ),
                bodyStream = ByteArrayInputStream(contentBytes),
                isPrivate = true,
            ),
        )
        advanceUntilIdle()

        val itemsAfterSuccess = manager.downloads.value
        val item1 = itemsAfterSuccess.first { it.id == id1 }
        val item2 = itemsAfterSuccess.first { it.id == id2 }
        assertEquals(DownloadStatus.COMPLETED, item1.status)
        assertEquals("manual.pdf", item1.fileName)
        assertEquals(contentBytes.size.toLong(), item1.downloadedBytes)

        assertEquals(DownloadStatus.COMPLETED, item2.status)
        assertEquals("manual (1).pdf", item2.fileName)

        // Mô phỏng đứt mạng giữa chừng: tệp dở dang phải bị xóa sạch (discardPartial) và đóng luồng InputStream
        val failingStream = FailingMidStreamInputStream("partial-chunk".toByteArray())
        val failedId = manager.enqueueExternalResponse(
            ExternalDownloadPayload(
                uri = "https://releases.example.org/files/broken.zip",
                statusCode = 200,
                headers = mapOf("Content-Type" to "application/zip"),
                bodyStream = failingStream,
                isPrivate = false,
            ),
        )
        advanceUntilIdle()

        val failedItem = manager.downloads.value.first { it.id == failedId }
        assertEquals(DownloadStatus.FAILED, failedItem.status)
        assertTrue("Underlying WebResponse stream must be closed on error", failingStream.closed)
        assertTrue("Partial sink must be discarded on failure", sink.openedHandles.last().discarded)

        // Dọn danh sách tải xuống của phiên riêng tư không làm mất lịch sử tải của tab thường
        manager.clearFinishedDownloads(onlyPrivate = true)
        assertTrue(manager.downloads.value.none { it.id == id2 })
        assertTrue(manager.downloads.value.any { it.id == id1 })
    }
}
