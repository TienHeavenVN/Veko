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

private class FakeSinkSession(
    val uniqueFileName: String,
    private val onCommit: (String) -> Unit,
    val buffer: ByteArrayOutputStream = ByteArrayOutputStream(),
) : DownloadStorageSink.Session {
    var aborted: Boolean = false
        private set

    override val outputStream: OutputStream = buffer

    override fun commitCompleted(): String {
        onCommit(uniqueFileName)
        return "content://io.veko.browser.test/downloads/$uniqueFileName"
    }

    override fun abortAndDelete() {
        aborted = true
        buffer.reset()
    }

    override fun close() = Unit
}

private class FakeDownloadStorageSink : DownloadStorageSink {
    private val committedNames = LinkedHashSet<String>()
    val openedSessions = mutableListOf<FakeSinkSession>()

    override fun existingFileNames(): Set<String> = committedNames.toSet()

    override fun openSink(uniqueFileName: String, mimeType: String): DownloadStorageSink.Session {
        return FakeSinkSession(
            uniqueFileName = uniqueFileName,
            onCommit = { committedNames += it },
        ).also { openedSessions += it }
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
            contentDisposition = """attachment; filename="../../etc/evil..pdf"""",
            contentType = "application/pdf",
        )
        assertFalse(sanitizedTraversal.contains(".."))
        assertFalse(sanitizedTraversal.contains("/"))
        assertFalse(sanitizedTraversal.contains("\\"))
        assertTrue(sanitizedTraversal.endsWith(".pdf"))

        // Hỗ trợ tên tệp UTF-8 theo chuẩn RFC 5987 filename*=UTF-8''
        val utf8Name = DownloadFileNameSanitizer.resolveSafeFileName(
            url = "https://example.com/dl?id=99",
            contentDisposition = "attachment; filename*=UTF-8''B%C3%A1o%20c%C3%A1o%20Veko.pdf",
            contentType = "application/pdf",
        )
        assertEquals("Báo cáo Veko.pdf", utf8Name)

        // Tự động gắn phần mở rộng theo MIME khi URL không có đuôi tệp
        val inferredPng = DownloadFileNameSanitizer.resolveSafeFileName(
            url = "https://images.example.com/render?token=abc",
            contentDisposition = null,
            contentType = "image/png",
        )
        assertEquals("render.png", inferredPng)

        // Chống ghi đè khi trùng tên tệp
        val existing = setOf("report.pdf", "report (1).pdf")
        val deduped = DownloadFileNameSanitizer.deduplicateFileName("report.pdf", existing)
        assertEquals("report (2).pdf", deduped)
    }

    @Test
    fun `enqueueExternalResponse streams bytes, deduplicates duplicate names, and cleans up partial files on failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sink = FakeDownloadStorageSink()
        val manager = VekoDownloadManager(
            storageSink = sink,
            httpStreamOpener = { throw IOException("Not used when bodyStream is provided") },
            ioDispatcher = dispatcher,
            clockMs = { 123_456L },
        )

        val contentBytes = "Hello Veko GeckoView Download Stream!".repeat(400).toByteArray()
        val id1 = manager.enqueueExternalResponse(
            ExternalDownloadPayload(
                uri = "https://releases.example.org/files/manual.pdf",
                contentType = "application/pdf",
                contentDisposition = "attachment; filename=\"manual.pdf\"",
                contentLength = contentBytes.size.toLong(),
                isPrivate = false,
                bodyStream = ByteArrayInputStream(contentBytes),
            ),
        )!!
        // Tải lần 2 trùng tên để kiểm tra tự đánh số manual (1).pdf
        val id2 = manager.enqueueExternalResponse(
            ExternalDownloadPayload(
                uri = "https://releases.example.org/files/manual.pdf",
                contentType = "application/pdf",
                contentDisposition = "attachment; filename=\"manual.pdf\"",
                contentLength = contentBytes.size.toLong(),
                isPrivate = true,
                bodyStream = ByteArrayInputStream(contentBytes),
            ),
        )!!
        advanceUntilIdle()

        val itemsAfterSuccess = manager.downloads.value
        val item1 = itemsAfterSuccess.first { it.id == id1 }
        val item2 = itemsAfterSuccess.first { it.id == id2 }
        assertEquals(DownloadStatus.COMPLETED, item1.status)
        assertEquals("manual.pdf", item1.fileName)
        assertEquals(contentBytes.size.toLong(), item1.downloadedBytes)

        assertEquals(DownloadStatus.COMPLETED, item2.status)
        assertEquals("manual (1).pdf", item2.fileName)

        // Mô phỏng đứt mạng giữa chừng: tệp dở dang phải bị xóa sạch (abortAndDelete) và đóng luồng InputStream
        val failingStream = FailingMidStreamInputStream("partial-chunk".toByteArray())
        val failedId = manager.enqueueExternalResponse(
            ExternalDownloadPayload(
                uri = "https://releases.example.org/files/broken.zip",
                contentType = "application/zip",
                contentDisposition = null,
                contentLength = -1L,
                isPrivate = false,
                bodyStream = failingStream,
            ),
        )!!
        advanceUntilIdle()

        val failedItem = manager.downloads.value.first { it.id == failedId }
        assertEquals(DownloadStatus.FAILED, failedItem.status)
        assertTrue("Underlying WebResponse stream must be closed on error", failingStream.closed)
        assertTrue("Partial sink must be aborted on failure", sink.openedSessions.last().aborted)

        // Dọn danh sách tải xuống của phiên riêng tư không làm mất lịch sử tải của tab thường
        manager.clearFinishedDownloads(onlyPrivate = true)
        assertTrue(manager.downloads.value.none { it.id == id2 })
        assertTrue(manager.downloads.value.any { it.id == id1 })
    }
}
