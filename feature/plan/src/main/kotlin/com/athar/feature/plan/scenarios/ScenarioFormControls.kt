package com.athar.feature.plan.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.feature.plan.R

@Composable
internal fun ScenarioInput(value: String, label: String, error: Boolean, decimal: Boolean = false, onChange: (String) -> Unit) {
    AtharTextField(value, onChange, label, Modifier.fillMaxWidth(),
        keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text,
        supportingText = if (error) stringResource(R.string.scenario_field_invalid) else null)
}

@Composable
internal fun ScenarioCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val theme = AtharTheme
    Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)
        .toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        Checkbox(checked, onCheckedChange = null, colors = CheckboxDefaults.colors(
            checkedColor = theme.colors.ember, uncheckedColor = theme.colors.muted, checkmarkColor = theme.colors.parchment))
        AtharText(label, modifier = Modifier.weight(1f), style = theme.typography.caption)
    }
}

@Composable
internal fun ScenarioTextAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val theme = AtharTheme
    TextButton(onClick, enabled = enabled, modifier = Modifier.heightIn(min = MinTouchTarget),
        colors = ButtonDefaults.textButtonColors(contentColor = theme.colors.ember)) {
        AtharText(label, style = theme.typography.body,
            color = if (enabled) theme.colors.ember else theme.colors.muted)
    }
}
