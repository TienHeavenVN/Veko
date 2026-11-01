package io.veko.browser.engine

import androidx.compose.runtime.Immutable

/**
 * Trạng thái sẵn sàng thực tế của một nhân trình duyệt trên thiết bị hiện hành.
 */
@Immutable
enum class EngineAvailabilityStatus(val displayLabel: String) {
    AVAILABLE("Sẵn sàng (Available)"),
    UNAVAILABLE("Không khả dụng (Unavailable)"),
    BLOCKED("Tạm khóa chờ SDK (Blocked)"),
}

/**
 * Kết quả kiểm định gói SDK và tính toàn vẹn nhị phân của nhân trình duyệt.
 */
@Immutable
enum class SdkValidationStatus(val displayLabel: String) {
    VERIFIED_MAVEN_SDK("Đã xác thực Mozilla Maven SDK"),
    VERIFIED_NATIVE_SDK("Đã xác thực gói SDK Chromium Content gốc"),
    MISSING_UPSTREAM_SDK("Thiếu gói nhị phân Chromium Content thượng nguồn"),
    ABI_INCOMPATIBLE("Kiến trúc CPU/ABI không tương thích"),
    INVALID_ARTIFACT_PROVENANCE("Không khớp chữ ký liên kết Blink/V8 thực tế"),
}

/**
 * Báo cáo kiểm định đầy đủ của một `BrowserEngine`, phục vụ cả quyết định định tuyến
 * trong `BrowserEngineManager` lẫn bảng thông tin kỹ thuật trong Cài đặt Veko.
 */
@Immutable
data class EngineAvailability(
    val engineType: EngineType,
    val engineName: String,
    val engineVersion: String,
    val pinnedCommit: String? = null,
    val status: EngineAvailabilityStatus,
    val renderingEngine: String,
    val javascriptEngine: String,
    val deviceAbis: List<String>,
    val abiCompatible: Boolean,
    val sdkValidationStatus: SdkValidationStatus,
    val isSelectable: Boolean,
    val unavailabilityReason: String? = null,
    val missingArtifacts: List<String> = emptyList(),
) {
    init {
        if (isSelectable) {
            require(status == EngineAvailabilityStatus.AVAILABLE) {
                "Selectable engine $engineType must report AVAILABLE status, got $status"
            }
            require(abiCompatible) {
                "Selectable engine $engineType must be ABI-compatible"
            }
            require(
                sdkValidationStatus == SdkValidationStatus.VERIFIED_MAVEN_SDK ||
                    sdkValidationStatus == SdkValidationStatus.VERIFIED_NATIVE_SDK,
            ) {
                "Selectable engine $engineType must pass SDK validation, got $sdkValidationStatus"
            }
        }
    }
}

/**
 * Kết quả khi người dùng hoặc tầng điều phối yêu cầu đổi nhân trình duyệt mặc định.
 */
sealed interface EngineSelectionResult {
    data class Selected(
        val engineType: EngineType,
        val availability: EngineAvailability,
    ) : EngineSelectionResult

    data class Rejected(
        val requestedEngine: EngineType,
        val availability: EngineAvailability,
        val reason: String,
    ) : EngineSelectionResult
}
