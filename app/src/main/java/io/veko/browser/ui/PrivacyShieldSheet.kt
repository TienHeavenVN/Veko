package io.veko.browser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.data.repository.VekoSettings
import io.veko.browser.mvi.BrowserMviAction
import io.veko.browser.mvi.BrowserMviState
import io.veko.browser.tab.UrlNavigationPolicy
import io.veko.browser.ui.i18n.LocalVekoStrings

/**
 * Bảng điều khiển Lá chắn Quyền riêng tư đa tầng của Veko Browser:
 * Đứng giữa người dùng với trình theo dõi chéo trang, thu thập dấu vân tay trình duyệt và quảng cáo xâm lấn.
 * Hiển thị số liệu thực từ đường ống C++20/JNI + nhân lọc Rust `brave/adblock-rust` 0.13.4 + Strict ETP.
 */
@Composable
fun VekoPrivacyShieldSheetContent(
    mviState: BrowserMviState,
    onDispatch: (BrowserMviAction.UserIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalVekoStrings.current
    val telemetry = mviState.privacyTelemetry
    val settings = mviState.userSettings
    val activeTab = mviState.tabsState.activeTab
    val activeUrl = activeTab?.sessionState?.url.orEmpty()
    val activeHost = if (UrlNavigationPolicy.isRecordableHttpUrl(activeUrl)) {
        VekoSettings.normalizeHost(activeUrl)
    } else {
        ""
    }
    val isSitePaused = activeHost.isNotEmpty() && settings.trustedShieldHosts.any { trusted ->
        activeHost == trusted || activeHost.endsWith(".$trusted")
    }

    var customRulesDraft by remember(mviState.customFilterRules) {
        mutableStateOf(mviState.customFilterRules)
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 600.dp)
            .testTag("veko_privacy_shield_sheet"),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.shieldTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.shieldProtectorTagline,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.92f),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = strings.shieldEngineSummary(telemetry.activeFilterRules),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TelemetryMetricTile(
                            label = strings.metricAdsBlocked,
                            value = telemetry.adsBlocked.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        TelemetryMetricTile(
                            label = strings.metricTrackersBlocked,
                            value = telemetry.trackersBlocked.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        TelemetryMetricTile(
                            label = strings.metricFingerprintersBlocked,
                            value = telemetry.fingerprintersBlocked.toString(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TelemetryMetricTile(
                            label = strings.metricUrlsCleaned,
                            value = telemetry.paramsStripped.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        TelemetryMetricTile(
                            label = strings.metricHttpsUpgrades,
                            value = telemetry.httpsUpgrades.toString(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (activeHost.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (!isSitePaused) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shield_site_card"),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (!isSitePaused) {
                                    strings.siteShieldActiveFor(activeHost)
                                } else {
                                    strings.siteShieldPausedFor(activeHost)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = strings.siteShieldSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Switch(
                            checked = !isSitePaused,
                            onCheckedChange = { enabled ->
                                onDispatch(
                                    BrowserMviAction.UserIntent.ToggleSiteShieldForHost(
                                        hostOrUrl = activeHost,
                                        shieldEnabled = enabled,
                                        isPrivate = activeTab?.isPrivate == true,
                                    ),
                                )
                            },
                            modifier = Modifier.testTag("shield_site_toggle"),
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = strings.realtimeProtectionSection,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            ShieldToggleRow(
                title = strings.adBlockTitle,
                subtitle = strings.adBlockSubtitle,
                checked = settings.adBlockEnabled,
                onCheckedChange = { onDispatch(BrowserMviAction.UserIntent.SetAdBlockEnabled(it)) },
            )
        }

        item {
            ShieldToggleRow(
                title = strings.trackerBlockTitle,
                subtitle = strings.trackerBlockSubtitle,
                checked = settings.trackerBlockEnabled,
                onCheckedChange = { onDispatch(BrowserMviAction.UserIntent.SetTrackerBlockEnabled(it)) },
            )
        }

        item {
            ShieldToggleRow(
                title = strings.strictAntiTrackingTitle,
                subtitle = strings.strictAntiTrackingSubtitle,
                checked = settings.strictAntiTracking,
                onCheckedChange = { onDispatch(BrowserMviAction.UserIntent.SetStrictAntiTracking(it)) },
                switchTestTag = "shield_strict_tracking_switch",
            )
        }

        item {
            ShieldToggleRow(
                title = strings.stripParamsTitle,
                subtitle = strings.stripParamsSubtitle,
                checked = settings.stripTrackingParamsEnabled,
                onCheckedChange = {
                    onDispatch(BrowserMviAction.UserIntent.SetStripTrackingParamsEnabled(it))
                },
            )
        }

        item {
            ShieldToggleRow(
                title = strings.httpsUpgradeTitle,
                subtitle = strings.httpsUpgradeSubtitle,
                checked = settings.httpsUpgradeEnabled,
                onCheckedChange = { onDispatch(BrowserMviAction.UserIntent.SetHttpsUpgradeEnabled(it)) },
            )
        }

        item {
            ShieldToggleRow(
                title = strings.mixedContentTitle,
                subtitle = strings.mixedContentSubtitle,
                checked = settings.blockMixedContent,
                onCheckedChange = { onDispatch(BrowserMviAction.UserIntent.SetBlockMixedContent(it)) },
            )
        }

        if (mviState.filterSubscriptions.isNotEmpty()) {
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = strings.filterPacksSection,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(mviState.filterSubscriptions, key = { "filter_pack_row_${it.id}" }) { pack ->
                FilterChip(
                    selected = pack.enabled,
                    onClick = {
                        onDispatch(
                            BrowserMviAction.UserIntent.SetFilterSubscriptionEnabled(
                                subscriptionId = pack.id,
                                enabled = !pack.enabled,
                            ),
                        )
                    },
                    label = {
                        Text(
                            text = "${pack.title} • ${pack.category}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filter_pack_${pack.id}"),
                )
            }
        }

        item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = strings.cookieIsolationSection,
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
                        label = { Text(strings.cookieModeLabel(mode)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = strings.customRulesSection,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = customRulesDraft,
                onValueChange = { customRulesDraft = it },
                placeholder = { Text(strings.customRulesPlaceholder) },
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
                    Text(strings.loadIntoRustButton)
                }
                OutlinedButton(
                    onClick = { onDispatch(BrowserMviAction.UserIntent.ResetPrivacyStats) },
                ) {
                    Text(strings.resetStatsButton)
                }
            }
        }

        if (telemetry.recentBlockedItems.isNotEmpty()) {
            item {
                Text(
                    text = strings.recentBlockedSection,
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
                            text = strings.engineDiagnosticsTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Chromium pinned: ${readiness.pinnedChromiumVersion}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "C++20 JNI + Rust FFI: ${if (readiness.nativeJniBridgeLoaded && readiness.rustAdblockLoaded) "Active" else "Standby"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "Active engine: ${readiness.activeRenderingEngine}",
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
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.76f))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
    switchTestTag: String? = null,
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
            modifier = if (switchTestTag != null) Modifier.testTag(switchTestTag) else Modifier,
        )
    }
}
