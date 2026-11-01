package io.veko.browser.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.veko.browser.ui.i18n.LocalVekoStrings

/**
 * Header Trang chủ Veko:
 * - Bên trái: Logo V-Prism và chữ "Veko" nổi bật, không kèm bất kỳ dòng mô tả/tagline phụ nào bên dưới.
 * - Bên phải: Nút Riêng tư (PrivacyButton) và nút Cài đặt căn chỉnh thẳng hàng theo trục dọc, đủ vùng chạm 48dp.
 */
@Composable
fun HomeHeader(
    isPrivate: Boolean,
    privateTabCount: Int,
    onTogglePrivateMode: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalVekoStrings.current
    val fontScale = LocalDensity.current.fontScale

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_header"),
    ) {
        val isCompactWidth = maxWidth < 340.dp || (maxWidth < 390.dp && fontScale >= 1.3f)
        val showPrivateChip = isPrivate && maxWidth >= 370.dp && fontScale < 1.35f

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                VekoPrismEmblem(
                    isPrivate = isPrivate,
                    size = 38.dp,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Veko",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("home_brand_title"),
                )
                if (showPrivateChip) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = strings.privateBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PrivacyButton(
                    isPrivate = isPrivate,
                    privateTabCount = privateTabCount,
                    onClick = onTogglePrivateMode,
                    compact = isCompactWidth,
                )
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("home_settings_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = strings.openSettingsDesc,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
