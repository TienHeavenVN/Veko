package io.veko.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.mvi.BrowserMviAction
import io.veko.browser.mvi.BrowserMviState

/**
 * Bảng điều khiển Quyền riêng tư nguyên bản của Veko Browser (Veko Privacy Matrix):
 * Hiển thị số liệu thực từ đường ống C++20/JNI + nhân lọc Rust `brave/adblock-rust` 0.13.4 theo kiến trúc MVI/UDF.
 */
@Composable
fun VekoPrivacyShieldSheetContent(
    mviState: BrowserMviState,
    onDispatch: (BrowserMviAction.UserIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val telemetry = mviState.privacyTelemetry
    val settings = mviState.userSettings
    var customRulesDraft by remember(mviState.customFilterRules) {
        mutableStateOf(mviState.customFilterRules)
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("veko_privacy_shield_sheet"),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Ma trận Quyền riêng tư Veko",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Nhân lọc Rust adblock-rust 0.13.4 • ${telemetry.activeFilterRules} quy tắc đang hoạt động",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TelemetryMetricTile(
                            label = "Quảng cáo chặn",
                            value = telemetry.adsBlocked.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        TelemetryMetricTile(
                            label = "Theo dõi chặn",
                            value = telemetry.trackersBlocked.toString(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TelemetryMetricTile(
                            label = "URL làm sạch",
                            value = telemetry.paramsStripped.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        TelemetryMetricTile(
                            label = "Nâng cấp HTTPS",
                            value = telemetry.httpsUpgrades.toString(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "Lớp bảo vệ mạng theo thời gian thực",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            ShieldToggleRow(
                title = "Chặn mạng quảng cáo",
                subtitle = "Chặn script, banner và subresource quảng cáo qua EasyList Core",
                checked = settings.adBlockEnabled,
                onCheckedChange = { onDispatch(BrowserMviAction.UserIntent.SetAdBlockEnabled(it)) },
            )
        }

        item {
            ShieldToggleRow(
                title = "Chặn trình theo dõi chéo trang",
                subtitle = "Chặn pixel phân tích, telemetry và script dấu vân tay qua EasyPrivacy Core",
                checked = settings.trackerBlockEnabled,
                onCheckedChange = { onDispatch(BrowserMviAction.UserIntent.SetTrackerBlockEnabled(it)) },
            )
        }

        item {
            ShieldToggleRow(
                title = "Tự động gỡ tham số theo dõi URL",
                subtitle = "Xóa utm_*, fbclid, gclid, msclkid trước khi gửi truy vấn mạng",
                checked = settings.stripTrackingParamsEnabled,
                onCheckedChange = {
                    onDispatch(BrowserMviAction.UserIntent.SetStripTrackingParamsEnabled(it))
                },
            )
        }

        item {
            ShieldToggleRow(
                title = "Ưu tiên nâng cấp kết nối HTTPS",
                subtitle = "Tự động nâng cấp điều hướng HTTP sang HTTPS và giữ nguyên chứng chỉ TLS nghiêm ngặt",
                checked = settings.httpsUpgradeEnabled,
                onCheckedChange = { onDispatch(BrowserMviAction.UserIntent.SetHttpsUpgradeEnabled(it)) },
            )
        }

        item {
            ShieldToggleRow(
                title = "Chặn nội dung hỗn hợp HTTP (Mixed Content)",
                subtitle = "Ngăn trang HTTPS tải tài nguyên con HTTP không mã hóa",
                checked = settings.blockMixedContent,
                onCheckedChange = { onDispatch(BrowserMviAction.UserIntent.SetBlockMixedContent(it)) },
            )
        }

        item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Cô lập Cookie & Dữ liệu phiên",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CookieControlMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.cookieControlMode == mode,
                        onClick = {
                            onDispatch(BrowserMviAction.UserIntent.SetCookieControlMode(mode))
                        },
                        label = { Text(mode.labelVi) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Quy tắc lọc Adblock Plus tùy chỉnh (Rust Engine)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = customRulesDraft,
                onValueChange = { customRulesDraft = it },
                placeholder = { Text("Ví dụ: ||ads.example.com^ hoặc @@||allow.example.org^") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = {
                        onDispatch(BrowserMviAction.UserIntent.SaveCustomFilterRules(customRulesDraft))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Nạp vào Rust")
                }
                OutlinedButton(
                    onClick = { onDispatch(BrowserMviAction.UserIntent.ResetPrivacyStats) },
                ) {
                    Text("Đặt lại số liệu")
                }
            }
        }

        if (telemetry.recentBlockedItems.isNotEmpty()) {
            item {
                Text(
                    text = "Mục bị chặn gần đây (chỉ ghi trên phiên thường)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    telemetry.recentBlockedItems.take(6).forEach { entry ->
                        Text(
                            text = "• $entry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        mviState.embedderReadiness?.let { readiness ->
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Chẩn đoán nhân trình duyệt",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Chromium mục tiêu khóa cứng: ${readiness.pinnedChromiumVersion}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "Cầu nối C++20 JNI + Rust FFI: ${if (readiness.nativeJniBridgeLoaded && readiness.rustAdblockLoaded) "Đang hoạt động" else "Chưa nạp"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "Engine kết xuất hiện hành: ${readiness.activeRenderingEngine}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun TelemetryMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShieldToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
