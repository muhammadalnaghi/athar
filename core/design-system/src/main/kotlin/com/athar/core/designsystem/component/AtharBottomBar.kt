package com.athar.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.core.designsystem.display.currentUiLocale
import java.util.Locale

/**
 * The single bottom navigation primitive for Athar — three thin labels, an ember dot
 * under the active label. No icons. (Master Brief §3.)
 */
data class AtharBottomBarItem(val key: String, val labelEn: String, val labelAr: String) {
    fun labelFor(locale: Locale): String = if (locale.language == "ar") labelAr else labelEn
}

@Composable
fun AtharBottomBar(
    items: List<AtharBottomBarItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = theme.colors.parchment,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = theme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEach { item ->
                BottomBarLabel(
                    item = item,
                    selected = item.key == selectedKey,
                    onClick = { onSelect(item.key) },
                )
            }
        }
    }
}

@Composable
private fun BottomBarLabel(
    item: AtharBottomBarItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .padding(horizontal = theme.spacing.s, vertical = theme.spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(theme.spacing.xs),
    ) {
        AtharText(
            text = item.labelFor(currentUiLocale()),
            style = theme.typography.body,
            color = if (selected) theme.colors.ink else theme.colors.muted,
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (selected) theme.colors.ember else theme.colors.parchment),
        )
    }
}

@Preview
@Preview(widthDp = 320, fontScale = 1.3f, locale = "ar")
@Composable
private fun PreviewBottomBar() {
    AtharTheme {
        AtharBottomBar(
            items = listOf(
                AtharBottomBarItem("today", "Today", "اليوم"),
                AtharBottomBarItem("trends", "Trends", "النمط"),
                AtharBottomBarItem("plan", "Plan", "الخطة"),
            ),
            selectedKey = "today",
            onSelect = {},
        )
    }
}
