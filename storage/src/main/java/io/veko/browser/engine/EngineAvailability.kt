package io.veko.browser.engine

import androidx.compose.runtime.Immutable

/**
 * Trạng thái sẵn sàng thực tế của Mozilla GeckoView trên thiết bị hiện hành.
 */
@Immutable
enum class EngineAvailabilityStatus(val displayLabel: String) {
    AVAILABLE("Sẵn sàng (Available)"),
    UNAVAILABLE("Không khả dụng (Unavailable)"),
}

/**
 * Kết quả kiểm định gói SDK Mozilla GeckoView và tính tương thích nhị phân theo ABI thiết bị.
 */
@Immutable
enum class SdkValidationStatus(val displayLabel: String) {
    VERIFIED_MAVEN_SDK("Đã xác thực Mozilla GeckoView SDK"),
    ABI_INCOMPATIBLE("Kiến trúc CPU/ABI không tương thích"),
    INVALID_ARTIFACT_PROVENANCE("Thiếu thư viện gốc bắt buộc của GeckoView"),
}

/**
 * Báo cáo kiểm định đầy đủ của `GeckoBrowserEngine`, phục vụ cả kiểm tra khởi chạy
 * lẫn thẻ thông tin kỹ thuật trong Cài đặt Veko.
 */
@Immutable
data class EngineAvailability(
    val engineType: EngineType = EngineType.GECKO,
    val engineName: String,
    val engineVersion: String,
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
            require(sdkValidationStatus == SdkValidationStatus.VERIFIED_MAVEN_SDK) {
                "Selectable engine $engineType must pass SDK validation, got $sdkValidationStatus"
            }
        }
    }
}
