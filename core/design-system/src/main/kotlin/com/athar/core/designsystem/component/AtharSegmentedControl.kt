package com.athar.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.athar.core.designsystem.theme.AtharTheme

/**
 * Period selector primitive (Trends screen, Master Brief §3): شهر · 3 أشهر · سنة · مخصص.
 *
 * Pill background, ink-on-parchment when selected, muted otherwise. No icons.
 */
data class AtharSegment<T>(val value: T, val labelAr: String, val labelEn: String? = null)

@Composable
fun <T> AtharSegmentedControl(
    segments: List<AtharSegment<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(theme.colors.divider)
            .padding(2.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEach { segment ->
            val isSelected = segment.value == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (isSelected) theme.colors.parchment else theme.colors.divider)
                    .clickable { onSelect(segment.value) }
                    .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
                contentAlignment = Alignment.Center,
            ) {
                AtharText(
                    text = segment.labelAr,
                    style = theme.typography.caption,
                    color = if (isSelected) theme.colors.ink else theme.colors.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Preview
@Composable
private fun PreviewSegmented() {
    AtharTheme {
        AtharSegmentedControl(
            segments = listOf(
                AtharSegment("month", "شهر"),
                AtharSegment("3m", "٣ أشهر"),
                AtharSegment("year", "سنة"),
                AtharSegment("custom", "مخصص"),
            ),
            selected = "month",
            onSelect = {},
        )
    }
}
