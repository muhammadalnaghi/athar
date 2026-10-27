package com.athar.feature.plan.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.feature.plan.R

/**
 * Stateless form controls extracted from [GoalScenarioCard] (WM028-P-goals UI scope).
 * Rendering only: every value and callback comes from the caller; no parsing or
 * solving lives here. Behavior is unchanged from the pre-split file except that
 * [ActionButton] gained an `enabled` gate so compute/reset cannot be assumed while
 * the scenario currency is still loading.
 */

/** One draft text input; decimal toggles the numeric keyboard flavor. */
@Composable
internal fun ScenarioField(
    value: String,
    label: String,
    decimal: Boolean,
    isError: Boolean,
    onValueChange: (String) -> Unit,
) {
    AtharTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = Modifier.fillMaxWidth(),
        keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        isError = isError,
        supportingText = if (isError) stringResource(R.string.plan_goals_scenario_field_invalid) else null,
    )
}

/** Muted caption used for conventions and provenance notes under a field. */
@Composable
internal fun ConventionText(text: String) {
    val theme = AtharTheme
    AtharText(text = text, style = theme.typography.caption, color = theme.colors.muted)
}

internal data class SelectorOption<T>(val value: T, val label: String)

/** Hairline-bordered segmented choice; the selected option carries the single ember accent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> SelectorRow(
    options: List<SelectorOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    label: String? = null,
) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        label?.let {
            AtharText(text = it, style = theme.typography.caption, color = theme.colors.muted)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.spacing.s), verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            options.forEach { option ->
                val isSelected = option.value == selected
                Box(
                    modifier = Modifier
                        .heightIn(min = MinTouchTarget)
                        .widthIn(min = MinTouchTarget)
                        .clip(RoundedCornerShape(theme.spacing.s))
                        .background(if (isSelected) theme.colors.ember else Color.Transparent)
                        .then(
                            if (isSelected) {
                                Modifier
                            } else {
                                Modifier.border(0.5.dp, theme.colors.divider, RoundedCornerShape(theme.spacing.s))
                            },
                        )
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option.value) })
                        .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
                    contentAlignment = Alignment.Center,
                ) {
                    AtharText(
                        text = option.label,
                        style = theme.typography.caption,
                        color = if (isSelected) theme.colors.parchment else theme.colors.ink,
                    )
                }
            }
        }
    }
}

/**
 * Full-width action chip. While [enabled] is false the click is dropped and the chip
 * dims — used to keep compute/reset unreachable until the display currency has
 * actually loaded, so no solve or seed can be assumed against an unbound scenario.
 */
@Composable
internal fun ActionButton(
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    hairlineBorder: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    Box(
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .alpha(if (enabled) 1f else 0.5f)
            .background(container)
            .then(
                if (hairlineBorder) {
                    Modifier.border(0.5.dp, theme.colors.divider, RoundedCornerShape(theme.spacing.s))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(theme.spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(text = label, style = theme.typography.headline, color = content)
    }
}
