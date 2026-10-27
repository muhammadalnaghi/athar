package com.athar.feature.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.res.stringResource
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharNumber
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme

@Immutable
data class TodayDeclaredPosition(
    val id: String,
    val revision: Long,
    val title: String,
    val asOfDate: String,
    val net: Money,
)

@Composable
internal fun DeclaredPositionSummary(position: TodayDeclaredPosition) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(text = stringResource(R.string.today_declared_position_title), style = theme.typography.body)
        AtharNumber(money = position.net)
        AtharText(
            text = stringResource(R.string.today_declared_position_scope, position.title, position.asOfDate),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        AtharText(
            text = stringResource(R.string.today_declared_position_basis),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
    }
}
