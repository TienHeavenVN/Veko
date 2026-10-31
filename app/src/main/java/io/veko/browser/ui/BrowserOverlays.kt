package io.veko.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.veko.browser.download.DownloadItem
import io.veko.browser.download.DownloadStatus
import io.veko.browser.permission.SitePermissionDecision
import io.veko.browser.permission.SitePermissionPrompt
import io.veko.browser.permission.SitePermissionType
import io.veko.browser.tab.ExternalAppPrompt
import io.veko.browser.tab.SecurityState
import io.veko.browser.tab.Tab
import io.veko.browser.tab.UrlNavigationPolicy
import io.veko.browser.tab.WebChoiceOption
import io.veko.browser.tab.WebContextMenuState
import io.veko.browser.tab.WebDialogPrompt
import java.util.Locale

internal fun copyPlainTextToClipboard(context: Context, label: String, text: String): Boolean {
    val clean = text.trim()
    if (clean.isEmpty()) return false
    return runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, clean))
        true
    }.getOrDefault(false)
}

internal fun readClipboardPlainText(context: Context): String? {
    return runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.takeIf(String::isNotBlank)
    }.getOrNull()
}

internal fun sharePageOrLink(context: Context, url: String, title: String?) {
    val cleanUrl = UrlNavigationPolicy.stripUserInfo(url.trim())
    if (cleanUrl.isEmpty()) return
    runCatching {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            if (!title.isNullOrBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, title)
            }
            putExtra(Intent.EXTRA_TEXT, cleanUrl)
        }
        val chooser = Intent.createChooser(sendIntent, "Chia sẻ liên kết").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}

internal fun openDownloadedItem(context: Context, item: DownloadItem): Boolean {
    val uriStr = item.contentUriString ?: return false
    return runCatching {
        val contentUri = Uri.parse(uriStr)
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, item.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(openIntent, item.fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

internal fun launchExternalAppUri(context: Context, uriString: String): Boolean {
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

internal fun formatByteCount(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

internal fun permissionIcon(type: SitePermissionType): ImageVector = when (type) {
    SitePermissionType.GEOLOCATION -> Icons.Rounded.LocationOn
    SitePermissionType.CAMERA -> Icons.Rounded.Videocam
    SitePermissionType.MICROPHONE -> Icons.Rounded.Mic
    SitePermissionType.NOTIFICATION -> Icons.Rounded.Notifications
    SitePermissionType.PERSISTENT_STORAGE -> Icons.Rounded.Storage
}

/**
 * Khay kiểm tra bảo mật kết nối HTTPS/HTTP và quản lý/thu hồi quyền theo từng Origin.
 */
@Composable
internal fun SiteSecuritySheetContent(
    activeTab: Tab?,
    originPermissions: Map<SitePermissionType, SitePermissionDecision>,
    onCopyUrl: (String) -> Unit,
    onSetPermission: (String, SitePermissionType, SitePermissionDecision, Boolean) -> Unit,
    onRevokeAllForOrigin: (String, Boolean) -> Unit,
) {
    val rawUrl = activeTab?.sessionState?.url.orEmpty()
    val safeFullUrl = UrlNavigationPolicy.stripUserInfo(rawUrl)
    val origin = UrlNavigationPolicy.extractOrigin(safeFullUrl)
    val isPrivate = activeTab?.isPrivate == true
    val securityState = activeTab?.sessionState?.securityState ?: SecurityState.LOCAL_OR_UNKNOWN

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = when (securityState) {
                SecurityState.SECURE -> MaterialTheme.colorScheme.primaryContainer
                SecurityState.INSECURE -> MaterialTheme.colorScheme.errorContainer
                SecurityState.LOCAL_OR_UNKNOWN -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(
                    imageVector = when {
                        securityState == SecurityState.SECURE -> Icons.Rounded.Lock
                        securityState == SecurityState.INSECURE -> Icons.Rounded.Warning
                        isPrivate -> Icons.Rounded.Shield
                        else -> Icons.Rounded.Public
                    },
                    contentDescription = null,
                    tint = when (securityState) {
                        SecurityState.SECURE -> MaterialTheme.colorScheme.onPrimaryContainer
                        SecurityState.INSECURE -> MaterialTheme.colorScheme.onErrorContainer
                        SecurityState.LOCAL_OR_UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (securityState) {
                            SecurityState.SECURE -> "Kết nối được mã hóa (HTTPS)"
                            SecurityState.INSECURE -> "Kết nối không bảo mật (HTTP)"
                            SecurityState.LOCAL_OR_UNKNOWN -> "Trang cục bộ hoặc chưa tải nội dung mạng"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (securityState) {
                            SecurityState.SECURE ->
                                "Chứng chỉ TLS hợp lệ. Dữ liệu trao đổi giữa trình duyệt và máy chủ được mã hóa."
                            SecurityState.INSECURE ->
                                "Trang này truyền dữ liệu không mã hóa. Không nên nhập mật khẩu, thông tin cá nhân hay thẻ thanh toán."
                            SecurityState.LOCAL_OR_UNKNOWN ->
                                "Chưa thiết lập phiên mạng từ xa."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (safeFullUrl.isNotBlank() && !UrlNavigationPolicy.isNewTabBlankUrl(safeFullUrl)) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = safeFullUrl,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onCopyUrl(safeFullUrl) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Sao chép địa chỉ đầy đủ")
                    }
                }
            }
        }

        if (origin != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (isPrivate) "Quyền tạm thời (Tab riêng tư)" else "Quyền trang web ($origin)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (originPermissions.isNotEmpty()) {
                    TextButton(onClick = { onRevokeAllForOrigin(origin, isPrivate) }) {
                        Text("Đặt lại tất cả")
                    }
                }
            }

            SitePermissionType.entries.forEach { permType ->
                val current = originPermissions[permType] ?: SitePermissionDecision.PROMPT
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = permissionIcon(permType),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = permType.displayLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = current == SitePermissionDecision.ALLOW,
                            onClick = {
                                val next = if (current == SitePermissionDecision.ALLOW) {
                                    SitePermissionDecision.PROMPT
                                } else {
                                    SitePermissionDecision.ALLOW
                                }
                                onSetPermission(origin, permType, next, isPrivate)
                            },
                            label = { Text("Cho phép") },
                        )
                        FilterChip(
                            selected = current == SitePermissionDecision.DENY,
                            onClick = {
                                val next = if (current == SitePermissionDecision.DENY) {
                                    SitePermissionDecision.PROMPT
                                } else {
                                    SitePermissionDecision.DENY
                                }
                                onSetPermission(origin, permType, next, isPrivate)
                            },
                            label = { Text("Chặn") },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Khay quản lý tệp tải xuống (hiển thị dung lượng, tiến độ, mở tệp, hủy hoặc thử lại).
 */
@Composable
internal fun DownloadsSheetContent(
    downloads: List<DownloadItem>,
    onOpenFile: (DownloadItem) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearFinished: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = 560.dp)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Tệp tải xuống (${downloads.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (downloads.any { !it.isInProgress }) {
                TextButton(onClick = onClearFinished) {
                    Text("Xóa mục đã xong")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (downloads.isEmpty()) {
            EmptyOverlayHint("Chưa có tệp tải xuống nào.")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(downloads, key = { it.id }) { item ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = item.status == DownloadStatus.COMPLETED) {
                                    onOpenFile(item)
                                }
                                .padding(14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.FileDownload,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.fileName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val sizeLabel = if (item.totalBytes != null && item.totalBytes > 0) {
                                        "${formatByteCount(item.downloadedBytes)} / ${formatByteCount(item.totalBytes)}"
                                    } else {
                                        formatByteCount(item.downloadedBytes)
                                    }
                                    val statusLabel = when (item.status) {
                                        DownloadStatus.QUEUED -> "Đang chuẩn bị..."
                                        DownloadStatus.DOWNLOADING -> "Đang tải • $sizeLabel"
                                        DownloadStatus.COMPLETED -> "Hoàn tất • $sizeLabel • Chạm để mở"
                                        DownloadStatus.CANCELED -> "Đã hủy"
                                        DownloadStatus.FAILED -> item.errorMessage ?: "Tải xuống thất bại"
                                    }
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (item.status == DownloadStatus.FAILED) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                when (item.status) {
                                    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> {
                                        IconButton(onClick = { onCancel(item.id) }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Hủy tải xuống")
                                        }
                                    }
                                    DownloadStatus.FAILED, DownloadStatus.CANCELED -> {
                                        IconButton(onClick = { onRetry(item.id) }) {
                                            Icon(Icons.Rounded.Refresh, contentDescription = "Thử tải lại")
                                        }
                                        IconButton(onClick = { onRemove(item.id) }) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Xóa khỏi danh sách")
                                        }
                                    }
                                    DownloadStatus.COMPLETED -> {
                                        IconButton(onClick = { onRemove(item.id) }) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Ẩn khỏi danh sách")
                                        }
                                    }
                                }
                            }

                            if (item.isInProgress) {
                                Spacer(modifier = Modifier.height(8.dp))
                                val frac = item.progressFraction
                                if (frac != null) {
                                    LinearProgressIndicator(
                                        progress = { frac },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Menu ngữ cảnh khi nhấn giữ vào liên kết hoặc hình ảnh/video/âm thanh trên trang web.
 */
@Composable
internal fun WebContextMenuSheet(
    menu: WebContextMenuState,
    onOpenInNewTab: (String, Boolean) -> Unit,
    onOpenInBackgroundTab: (String, Boolean) -> Unit,
    onCopyText: (String) -> Unit,
    onShareUrl: (String, String?) -> Unit,
    onDownloadUrl: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val primaryTarget = menu.linkUri ?: menu.mediaSrcUri ?: return
    val displayTarget = UrlNavigationPolicy.stripUserInfo(primaryTarget)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            if (!menu.linkText.isNullOrBlank() || !menu.title.isNullOrBlank()) {
                Text(
                    text = menu.linkText ?: menu.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = displayTarget,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        menu.linkUri?.let { link ->
            val isHttp = UrlNavigationPolicy.isRecordableHttpUrl(link)
            if (isHttp) {
                ContextActionRow(
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    label = if (menu.isPrivate) "Mở trong tab riêng tư mới" else "Mở trong tab mới",
                    onClick = {
                        onOpenInNewTab(link, menu.isPrivate)
                        onDismiss()
                    },
                )
                ContextActionRow(
                    icon = Icons.Rounded.Add,
                    label = "Mở trong tab nền",
                    onClick = {
                        onOpenInBackgroundTab(link, menu.isPrivate)
                        onDismiss()
                    },
                )
            }
            ContextActionRow(
                icon = Icons.Rounded.ContentCopy,
                label = "Sao chép địa chỉ liên kết",
                onClick = {
                    onCopyText(link)
                    onDismiss()
                },
            )
            ContextActionRow(
                icon = Icons.Rounded.Share,
                label = "Chia sẻ liên kết",
                onClick = {
                    onShareUrl(link, menu.linkText)
                    onDismiss()
                },
            )
            if (isHttp) {
                ContextActionRow(
                    icon = Icons.Rounded.FileDownload,
                    label = "Tải liên kết xuống",
                    onClick = {
                        onDownloadUrl(link, menu.isPrivate)
                        onDismiss()
                    },
                )
            }
        }

        val mediaUri = menu.mediaSrcUri
        if (mediaUri != null && mediaUri != menu.linkUri) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            ContextActionRow(
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                label = "Mở tệp phương tiện trong tab mới",
                onClick = {
                    onOpenInNewTab(mediaUri, menu.isPrivate)
                    onDismiss()
                },
            )
            ContextActionRow(
                icon = Icons.Rounded.FileDownload,
                label = "Tải hình ảnh / phương tiện xuống",
                onClick = {
                    onDownloadUrl(mediaUri, menu.isPrivate)
                    onDismiss()
                },
            )
            ContextActionRow(
                icon = Icons.Rounded.ContentCopy,
                label = "Sao chép liên kết phương tiện",
                onClick = {
                    onCopyText(mediaUri)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun ContextActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

/**
 * Hiển thị hộp thoại chuẩn Material 3 cho JS alert/confirm/prompt, HTML <select>, beforeunload và kịch bản chậm.
 */
@Composable
internal fun WebPromptHost(
    prompt: WebDialogPrompt,
    onClearPromptState: () -> Unit,
) {
    when (prompt) {
        is WebDialogPrompt.Alert -> {
            AlertDialog(
                onDismissRequest = {
                    onClearPromptState()
                    prompt.onDismiss()
                },
                title = { Text(prompt.title?.takeIf(String::isNotBlank) ?: "Thông báo từ trang web") },
                text = { Text(prompt.message) },
                confirmButton = {
                    Button(
                        onClick = {
                            onClearPromptState()
                            prompt.onDismiss()
                        },
                    ) {
                        Text("Đã hiểu")
                    }
                },
            )
        }

        is WebDialogPrompt.Confirm -> {
            AlertDialog(
                onDismissRequest = {
                    onClearPromptState()
                    prompt.cancel()
                },
                title = { Text(prompt.title?.takeIf(String::isNotBlank) ?: "Xác nhận") },
                text = { Text(prompt.message) },
                confirmButton = {
                    Button(
                        onClick = {
                            onClearPromptState()
                            prompt.onDecision(true)
                        },
                    ) {
                        Text(prompt.positiveLabel)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            onClearPromptState()
                            prompt.onDecision(false)
                        },
                    ) {
                        Text(prompt.negativeLabel)
                    }
                },
            )
        }

        is WebDialogPrompt.TextInput -> {
            var value by remember(prompt.id) { mutableStateOf(prompt.defaultValue) }
            AlertDialog(
                onDismissRequest = {
                    onClearPromptState()
                    prompt.onDismiss()
                },
                title = { Text(prompt.title?.takeIf(String::isNotBlank) ?: "Nhập thông tin") },
                text = {
                    Column {
                        if (prompt.message.isNotBlank()) {
                            Text(prompt.message, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onClearPromptState()
                            prompt.onConfirm(value)
                        },
                    ) {
                        Text("Xác nhận")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            onClearPromptState()
                            prompt.onDismiss()
                        },
                    ) {
                        Text("Hủy")
                    }
                },
            )
        }

        is WebDialogPrompt.ChoiceSelect -> {
            ChoiceSelectDialog(
                prompt = prompt,
                onClearPromptState = onClearPromptState,
            )
        }

        is WebDialogPrompt.SlowScript -> {
            AlertDialog(
                onDismissRequest = {
                    onClearPromptState()
                    prompt.onAction(false)
                },
                icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
                title = { Text("Trang web phản hồi chậm") },
                text = {
                    Text(
                        "Một kịch bản trên trang (${prompt.scriptFileName}) đang làm chậm phản hồi. Bạn muốn dừng kịch bản này hay tiếp tục đợi?",
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onClearPromptState()
                            prompt.onAction(true)
                        },
                    ) {
                        Text("Dừng kịch bản")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            onClearPromptState()
                            prompt.onAction(false)
                        },
                    ) {
                        Text("Đợi thêm")
                    }
                },
            )
        }
    }
}

@Composable
private fun ChoiceSelectDialog(
    prompt: WebDialogPrompt.ChoiceSelect,
    onClearPromptState: () -> Unit,
) {
    val selectedIds = remember(prompt.id) {
        mutableStateListOf<String>().apply {
            addAll(prompt.options.filter { it.selected && !it.isGroupHeader }.map { it.id })
        }
    }

    AlertDialog(
        onDismissRequest = {
            onClearPromptState()
            prompt.onDismiss()
        },
        title = { Text(prompt.title?.takeIf(String::isNotBlank) ?: "Chọn một mục") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(prompt.options, key = { "${it.id}_${it.label}" }) { option ->
                    ChoiceOptionRow(
                        option = option,
                        isMultiple = prompt.isMultiple,
                        isSelected = option.id in selectedIds,
                        onSelectSingle = { id ->
                            onClearPromptState()
                            prompt.onConfirmIds(listOf(id))
                        },
                        onToggleMulti = { id ->
                            if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
                        },
                    )
                }
            }
        },
        confirmButton = {
            if (prompt.isMultiple) {
                Button(
                    onClick = {
                        onClearPromptState()
                        prompt.onConfirmIds(selectedIds.toList())
                    },
                ) {
                    Text("Xong")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onClearPromptState()
                    prompt.onDismiss()
                },
            ) {
                Text("Đóng")
            }
        },
    )
}

@Composable
private fun ChoiceOptionRow(
    option: WebChoiceOption,
    isMultiple: Boolean,
    isSelected: Boolean,
    onSelectSingle: (String) -> Unit,
    onToggleMulti: (String) -> Unit,
) {
    if (option.isGroupHeader) {
        Text(
            text = option.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
        )
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !option.disabled) {
                if (isMultiple) onToggleMulti(option.id) else onSelectSingle(option.id)
            }
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        if (isMultiple) {
            Checkbox(
                checked = isSelected,
                enabled = !option.disabled,
                onCheckedChange = { onToggleMulti(option.id) },
            )
        } else {
            RadioButton(
                selected = isSelected,
                enabled = !option.disabled,
                onClick = { onSelectSingle(option.id) },
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = option.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (option.disabled) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * Hộp thoại xin quyền truy cập Vị trí / Máy ảnh / Micrô cho từng Origin cụ thể.
 */
@Composable
internal fun SitePermissionDialog(
    prompt: SitePermissionPrompt,
    onResolve: (allow: Boolean, remember: Boolean) -> Unit,
) {
    var rememberDecision by remember(prompt.id) { mutableStateOf(!prompt.isPrivate) }
    val requestedNames = prompt.requestedTypes.joinToString(", ") { it.displayLabel }

    AlertDialog(
        onDismissRequest = { onResolve(false, false) },
        icon = {
            Icon(
                imageVector = prompt.requestedTypes.firstOrNull()?.let(::permissionIcon) ?: Icons.Rounded.Shield,
                contentDescription = null,
            )
        },
        title = { Text("Yêu cầu quyền truy cập") },
        text = {
            Column {
                Text(
                    text = "${prompt.origin} muốn sử dụng: $requestedNames.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (prompt.isPrivate) {
                    Text(
                        text = "Đang ở chế độ riêng tư: lựa chọn chỉ có hiệu lực tạm thời trên RAM và sẽ tự xóa khi đóng tab riêng tư.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { rememberDecision = !rememberDecision },
                    ) {
                        Checkbox(
                            checked = rememberDecision,
                            onCheckedChange = { rememberDecision = it },
                        )
                        Text(
                            text = "Ghi nhớ lựa chọn cho trang web này",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onResolve(true, rememberDecision) }) {
                Text("Cho phép")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { onResolve(false, rememberDecision) }) {
                Text("Từ chối")
            }
        },
    )
}

/**
 * Hộp thoại xác nhận trước khi chuyển sang ứng dụng ngoài (mailto:, tel:, sms:, geo:, market:).
 */
@Composable
internal fun ExternalAppConfirmDialog(
    prompt: ExternalAppPrompt,
    onConfirmLaunch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
        title = { Text("Mở ứng dụng bên ngoài?") },
        text = {
            Column {
                Text(
                    text = "Trang web muốn mở liên kết này bằng một ứng dụng khác trên thiết bị:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = prompt.uri,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    onConfirmLaunch(prompt.uri)
                    onDismiss()
                },
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Mở ứng dụng")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        },
    )
}

@Composable
internal fun EmptyOverlayHint(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
