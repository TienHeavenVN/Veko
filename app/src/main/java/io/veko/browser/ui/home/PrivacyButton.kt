package io.veko.browser.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.veko.browser.ui.i18n.LocalVekoStrings

/**
 * Nút chuyển đổi nhanh giữa chế độ duyệt thường và chế độ Riêng tư trên Header Trang chủ.
 * Đảm bảo vùng chạm tối thiểu chuẩn Android (48dp) và nhãn TalkBack rõ ràng.
 */
@Composable
fun PrivacyButton(
    isPrivate: Boolean,
    privateTabCount: Int,
    onClick: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val strings = LocalVekoStrings.current
    val label = when {
        isPrivate -> strings.regularTabButton
        privateTabCount > 0 -> "${strings.privateButton} ($privateTabCount)"
        else -> strings.privateButton
    }
    val accessibilityLabel = label

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (isPrivate) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (isPrivate) {
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(minHeight = 40.dp)
            .testTag("home_privacy_button")
            .semantics { contentDescription = accessibilityLabel },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = 8.dp,
            ),
        ) {
            Icon(
                imageVector = if (isPrivate) Icons.Rounded.Public else Icons.Rounded.Shield,
                contentDescription = null,
                tint = if (isPrivate) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(16.dp),
            )
            if (!compact) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isPrivate) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
