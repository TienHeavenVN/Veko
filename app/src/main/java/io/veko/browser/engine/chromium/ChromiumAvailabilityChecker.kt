package io.veko.browser.engine.chromium

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.veko.browser.chromium.embedder.BaseVekoChromiumProcessService
import io.veko.browser.chromium.embedder.ChromiumEmbedderRuntime
import io.veko.browser.chromium.nativebridge.VekoChromiumNative
import io.veko.browser.engine.EngineAvailability
import io.veko.browser.engine.EngineAvailabilityStatus
import io.veko.browser.engine.EngineCapabilities
import io.veko.browser.engine.EngineType
import io.veko.browser.engine.SdkValidationStatus
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Giao diện thăm dò tạo tác SDK Chromium Content `156.0.8078.85` (`0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5`)
 * mà KHÔNG tự ý gọi `System.loadLibrary("veko_content_embedder")` hay khởi chạy tiến trình con.
 */
interface ChromiumSdkArtifactProbe {
    fun deviceSupportedAbis(): List<String>
    fun hasNativeContentSharedLibrary(abi: String): Boolean
    fun presentAssetNames(): Set<String>
    fun hasUpstreamJavaRuntimeClasses(): Boolean
    fun isUpstreamBlinkV8SymbolLinked(): Boolean
}

/**
 * Bộ kiểm định trung thực tính khả dụng của nhân Chromium Content `156.0.8078.85`
 * dựa trên đồ thị phụ thuộc thực tế (`chromium/veko_embedder/BUILD.gn` + `scripts/import-chromium-sdk.sh`):
 *
 * 1) Tương thích ABI (`arm64-v8a` là mục tiêu chính; hỗ trợ `armeabi-v7a`, `x86_64` khi có `.so` tương ứng).
 * 2) Hiện diện thư viện động nguyên khối `libveko_content_embedder.so` (liên kết `//content`, Blink, V8, Skia, Net).
 * 3) Hiện diện tài nguyên không nén `.pak`, `icudtl.dat` và ảnh chụp ngữ cảnh V8 (`v8_context_snapshot_*.bin` / `snapshot_blob.bin`).
 * 4) Hiện diện các lớp Java thượng nguồn (`BrowserStartupController` + `ContentChildProcessServiceDelegate`).
 * 5) Xác thực cờ liên kết nhị phân `isUpstreamContentLinked()` trong C++ JNI (chặn mọi tệp giả mạo/placeholder 0 byte).
 */
@Singleton
class ChromiumAvailabilityChecker internal constructor(
    private val probe: ChromiumSdkArtifactProbe,
) {

    @Inject
    constructor(
        @ApplicationContext appContext: Context,
    ) : this(AndroidChromiumSdkArtifactProbe(appContext))

    fun checkAvailability(): EngineAvailability {
        val deviceAbis = probe.deviceSupportedAbis().ifEmpty { listOf(PRIMARY_TARGET_ABI) }
        val supportedDeviceAbis = deviceAbis.filter { it in SUPPORTED_CHROMIUM_ABIS }

        if (supportedDeviceAbis.isEmpty()) {
            return EngineAvailability(
                engineType = EngineType.CHROMIUM,
                engineName = EngineType.CHROMIUM.displayName,
                engineVersion = PINNED_VERSION,
                pinnedCommit = PINNED_COMMIT,
                status = EngineAvailabilityStatus.UNAVAILABLE,
                renderingEngine = EngineType.CHROMIUM.renderingEngineName,
                javascriptEngine = EngineType.CHROMIUM.javascriptEngineName,
                deviceAbis = deviceAbis,
                abiCompatible = false,
                sdkValidationStatus = SdkValidationStatus.ABI_INCOMPATIBLE,
                isSelectable = false,
                unavailabilityReason = "Kiến trúc CPU thiết bị (${deviceAbis.joinToString()}) không nằm trong danh sách ABI hỗ trợ của Chromium Content ($PRIMARY_TARGET_ABI chính).",
            )
        }

        val matchingNativeAbi = supportedDeviceAbis.firstOrNull { abi ->
            probe.hasNativeContentSharedLibrary(abi)
        }
        val assets = probe.presentAssetNames()
        val hasContentPak = CONTENT_PAK_ASSET in assets
        val hasIcuDat = ICU_DATA_ASSET in assets
        val hasV8Snapshot = V8_SNAPSHOT_CANDIDATES.any { it in assets }
        val hasUpstreamJava = probe.hasUpstreamJavaRuntimeClasses()

        val missingArtifacts = buildList {
            if (matchingNativeAbi == null) {
                add("jniLibs/${supportedDeviceAbis.first()}/$CONTENT_EMBEDDER_SO")
            }
            if (!hasContentPak) add("assets/$CONTENT_PAK_ASSET")
            if (!hasIcuDat) add("assets/$ICU_DATA_ASSET")
            if (!hasV8Snapshot) add("assets/v8_context_snapshot_{64,32}.bin")
            if (!hasUpstreamJava) add("jars/content_java.jar (BrowserStartupController & ChildProcessService)")
        }

        if (missingArtifacts.isNotEmpty()) {
            return EngineAvailability(
                engineType = EngineType.CHROMIUM,
                engineName = EngineType.CHROMIUM.displayName,
                engineVersion = PINNED_VERSION,
                pinnedCommit = PINNED_COMMIT,
                status = EngineAvailabilityStatus.BLOCKED,
                renderingEngine = EngineType.CHROMIUM.renderingEngineName,
                javascriptEngine = EngineType.CHROMIUM.javascriptEngineName,
                deviceAbis = deviceAbis,
                abiCompatible = true,
                sdkValidationStatus = SdkValidationStatus.MISSING_UPSTREAM_SDK,
                isSelectable = false,
                unavailabilityReason = "Chưa nhập gói nhị phân Chromium Content $PINNED_VERSION (${PINNED_COMMIT.take(12)}). " +
                    "Biên dịch Blink + V8 + //content yêu cầu máy chủ Linux >=32 GB RAM / >=110 GB NVMe qua scripts/build-chromium-android.sh & scripts/import-chromium-sdk.sh.",
                missingArtifacts = missingArtifacts,
            )
        }

        // Chốt chặn chống giả mạo: kể cả khi đủ tệp trên đĩa, bắt buộc xác minh liên kết ký hiệu thực tế trong C++ JNI
        if (!probe.isUpstreamBlinkV8SymbolLinked()) {
            return EngineAvailability(
                engineType = EngineType.CHROMIUM,
                engineName = EngineType.CHROMIUM.displayName,
                engineVersion = PINNED_VERSION,
                pinnedCommit = PINNED_COMMIT,
                status = EngineAvailabilityStatus.BLOCKED,
                renderingEngine = EngineType.CHROMIUM.renderingEngineName,
                javascriptEngine = EngineType.CHROMIUM.javascriptEngineName,
                deviceAbis = deviceAbis,
                abiCompatible = true,
                sdkValidationStatus = SdkValidationStatus.INVALID_ARTIFACT_PROVENANCE,
                isSelectable = false,
                unavailabilityReason = "Phát hiện tệp tạo tác Chromium chưa liên kết ký hiệu thực tế với Blink/V8/Content ($PINNED_VERSION). Veko từ chối kích hoạt để ngăn hiển thị giả lập.",
                missingArtifacts = listOf("Verified upstream Blink/V8 JNI symbol linkage"),
            )
        }

        return EngineAvailability(
            engineType = EngineType.CHROMIUM,
            engineName = EngineType.CHROMIUM.displayName,
            engineVersion = PINNED_VERSION,
            pinnedCommit = PINNED_COMMIT,
            status = EngineAvailabilityStatus.AVAILABLE,
            renderingEngine = EngineType.CHROMIUM.renderingEngineName,
            javascriptEngine = EngineType.CHROMIUM.javascriptEngineName,
            deviceAbis = deviceAbis,
            abiCompatible = true,
            sdkValidationStatus = SdkValidationStatus.VERIFIED_NATIVE_SDK,
            isSelectable = true,
            unavailabilityReason = null,
            missingArtifacts = emptyList(),
        )
    }

    companion object {
        const val PINNED_VERSION: String = ChromiumEmbedderRuntime.PINNED_CHROMIUM_VERSION
        const val PINNED_COMMIT: String = ChromiumEmbedderRuntime.PINNED_CHROMIUM_COMMIT
        const val PRIMARY_TARGET_ABI: String = EngineCapabilities.PRIMARY_TARGET_ABI
        val SUPPORTED_CHROMIUM_ABIS: Set<String> = EngineCapabilities.DEFAULT_SUPPORTED_ABIS

        const val CONTENT_EMBEDDER_SO: String = "libveko_content_embedder.so"
        const val CONTENT_PAK_ASSET: String = "veko_content.pak"
        const val ICU_DATA_ASSET: String = "icudtl.dat"
        val V8_SNAPSHOT_CANDIDATES: Set<String> = setOf(
            "v8_context_snapshot_64.bin",
            "v8_context_snapshot_32.bin",
            "snapshot_blob.bin",
        )
    }
}

private class AndroidChromiumSdkArtifactProbe(
    private val appContext: Context,
) : ChromiumSdkArtifactProbe {

    override fun deviceSupportedAbis(): List<String> =
        Build.SUPPORTED_ABIS?.toList().orEmpty()

    override fun hasNativeContentSharedLibrary(abi: String): Boolean {
        val appInfo = runCatching { appContext.applicationInfo }.getOrNull() ?: return false
        val nativeDir = appInfo.nativeLibraryDir?.let(::File)
        if (nativeDir != null) {
            val soFile = File(nativeDir, ChromiumAvailabilityChecker.CONTENT_EMBEDDER_SO)
            if (soFile.isFile && soFile.length() > MIN_VALID_CONTENT_SO_BYTES) {
                return true
            }
        }
        val apkPath = appInfo.sourceDir?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry("lib/$abi/${ChromiumAvailabilityChecker.CONTENT_EMBEDDER_SO}")
                entry != null && entry.size > MIN_VALID_CONTENT_SO_BYTES
            }
        }.getOrDefault(false)
    }

    override fun presentAssetNames(): Set<String> =
        runCatching {
            appContext.assets.list("").orEmpty().toSet()
        }.getOrDefault(emptySet())

    override fun hasUpstreamJavaRuntimeClasses(): Boolean {
        val browserControllerPresent = runCatching {
            Class.forName(
                "org.chromium.content_public.browser.BrowserStartupController",
                false,
                AndroidChromiumSdkArtifactProbe::class.java.classLoader,
            )
            true
        }.getOrDefault(false)
        return browserControllerPresent && BaseVekoChromiumProcessService.isUpstreamChildServiceAvailable()
    }

    override fun isUpstreamBlinkV8SymbolLinked(): Boolean =
        runCatching { VekoChromiumNative.isUpstreamContentLinked() }.getOrDefault(false)

    private companion object {
        // Thư viện động nguyên khối Chromium Content + Blink + V8 thực tế luôn lớn hơn nhiều ngưỡng tối thiểu 1 MB
        const val MIN_VALID_CONTENT_SO_BYTES: Long = 1_048_576L
    }
}
