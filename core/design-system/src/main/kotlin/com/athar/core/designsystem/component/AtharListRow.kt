package com.athar.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.athar.core.common.money.Money
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget

/**
 * Single transaction-row primitive: title · subtitle · trailing money.
 *
 * Used in: Today feed, Trends category list, Plan budget list.
 * Minimum 48dp touch target (Master Brief §2.4).
 */
@Composable
fun AtharListRow(
    title: String,
    subtitle: String? = null,
    trailing: Money? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = theme.spacing.m, vertical = theme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AtharText(text = title, style = theme.typography.headline)
            if (subtitle != null) {
                AtharText(
                    text = subtitle,
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
        }
        if (trailing != null) {
            AtharNumber(money = trailing)
        }
    }
}

@Preview
@Composable
private fun PreviewListRow() {
    AtharTheme {
        AtharListRow(
            title = "ستاربكس",
            subtitle = "قهوة · بطاقة الراجحي ١٢٣٤",
            trailing = Money.of("-200.00"),
        )
    }
}
