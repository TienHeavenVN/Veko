package io.veko.browser.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.veko.browser.data.repository.AddressSuggestion
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.SuggestionSource
import io.veko.browser.tab.UrlNavigationPolicy
import io.veko.browser.ui.i18n.LocalVekoStrings

/**
 * Vùng tìm kiếm & nhập địa chỉ nổi bật nhất trên Trang chủ Veko:
 * - Hỗ trợ nhập trực tiếp URL dài hoặc từ khóa tìm kiếm, hiển thị nút xóa nhanh khi có văn bản.
 * - Tích hợp bộ chọn công cụ tìm kiếm (SearchEngineSelector) và thao tác "Dán & đi" gọn gàng.
 * - Không lưu nháp ra đĩa hệ thống (dùng remember thuần trên RAM để bảo vệ chế độ Riêng tư).
 */
@Composable
fun AddressSearchSection(
    isPrivate: Boolean,
    searchEngine: SearchEngine,
    clipboardPreview: String?,
    suggestions: List<AddressSuggestion>,
    onQueryChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onSelectSearchEngine: (SearchEngine) -> Unit,
    onPasteAndGo: (String) -> Unit,
    onFocusStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalVekoStrings.current
    var query by remember(isPrivate) { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = isFocused) {
        focusManager.clearFocus()
    }

    val submitCurrentQuery: () -> Unit = {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            val target = trimmed
            query = ""
            onQueryChanged("")
            focusManager.clearFocus()
            onSubmit(target)
        } else {
            focusRequester.requestFocus()
        }
    }

    val outerBorderColor = if (isFocused) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        border = BorderStroke(
            width = if (isFocused) 1.5.dp else 1.dp,
            color = outerBorderColor,
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_address_section"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .clickable(
                        enabled = !isFocused,
                        onClick = { focusRequester.requestFocus() },
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Icon(
                        imageVector = if (isPrivate) Icons.Rounded.Shield else Icons.Rounded.Search,
                        contentDescription = null,
                        tint = if (isPrivate) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))

                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = if (isPrivate) {
                                    strings.privateSearchOrUrlHint
                                } else {
                                    strings.searchOrUrlHint
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { updated ->
                                query = updated
                                onQueryChanged(updated)
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = { submitCurrentQuery() },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { state ->
                                    if (isFocused != state.isFocused) {
                                        isFocused = state.isFocused
                                        onFocusStateChanged(state.isFocused)
                                    }
                                }
                                .testTag("home_address_input")
                                .semantics {
                                    contentDescription = "Ô nhập địa chỉ website hoặc từ khóa tìm kiếm"
                                },
                        )
                    }

                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                query = ""
                                onQueryChanged("")
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("home_address_clear"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Xóa nội dung đang nhập",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Surface(
                        onClick = submitCurrentQuery,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(38.dp)
                            .testTag("home_address_submit"),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = if (query.isBlank()) {
                                    "Bắt đầu nhập địa chỉ hoặc từ khóa"
                                } else {
                                    "Truy cập địa chỉ hoặc tìm kiếm"
                                },
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Gợi ý nhanh từ Dấu trang/Lịch sử khi người dùng đang gõ trực tiếp trên Trang chủ
            val trimmed = query.trim()
            if (isFocused && (trimmed.isNotEmpty() || suggestions.isNotEmpty())) {
                val normalizedPreview = remember(trimmed, searchEngine) {
                    if (trimmed.isEmpty()) "" else UrlNavigationPolicy.normalizeUserInput(trimmed, searchEngine)
                }
                val isDirectUrl = remember(trimmed, normalizedPreview, searchEngine) {
                    trimmed.isNotEmpty() && !normalizedPreview.startsWith(searchEngine.homeUrl)
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    if (trimmed.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { submitCurrentQuery() }
                                .padding(horizontal = 8.dp, vertical = 9.dp),
                        ) {
                            Icon(
                                imageVector = if (isDirectUrl) Icons.Rounded.Public else Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isDirectUrl) {
                                    "${strings.goToPrefix}$normalizedPreview"
                                } else {
                                    strings.searchWithTemplate(trimmed, searchEngine.label)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    for (item in suggestions.take(4)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    query = ""
                                    onQueryChanged("")
                                    focusManager.clearFocus()
                                    onSubmit(item.url)
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = if (item.source == SuggestionSource.BOOKMARK) {
                                    Icons.Rounded.Bookmark
                                } else {
                                    Icons.Rounded.History
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = UrlNavigationPolicy.stripUserInfo(item.url),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SearchEngineSelector(
                    selectedEngine = searchEngine,
                    onSelectEngine = onSelectSearchEngine,
                )

                if (!clipboardPreview.isNullOrBlank() && query.isEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        onClick = { onPasteAndGo(clipboardPreview) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .defaultMinSize(minHeight = 36.dp)
                            .widthIn(max = 152.dp)
                            .testTag("home_paste_and_go")
                            .semantics {
                                contentDescription = "Dán nội dung từ bộ nhớ tạm và truy cập ngay"
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentPaste,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.pasteAndGo,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
