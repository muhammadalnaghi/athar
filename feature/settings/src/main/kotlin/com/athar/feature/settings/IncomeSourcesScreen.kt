package com.athar.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.smsimport.SmsSenderIds

@Composable
fun IncomeSourcesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: IncomeSourcesViewModel = hiltViewModel(),
) {
    val theme = AtharTheme
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val draft by viewModel.manualDraft.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshDiscovered() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.colors.parchment)
            .verticalScroll(rememberScrollState())
            .padding(theme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.l),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AtharText(
                text = stringResource(R.string.settings_income_sources_overline),
                style = theme.typography.overline,
                color = theme.colors.muted,
            )
            AtharText(
                text = stringResource(R.string.settings_action_back),
                style = theme.typography.body,
                color = theme.colors.muted,
                modifier = Modifier.clickable(onClick = onBack),
            )
        }

        AtharCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                AtharText(
                    text = stringResource(R.string.settings_income_sources_title),
                    style = theme.typography.headline,
                )
                AtharText(
                    text = stringResource(R.string.settings_income_sources_body),
                    style = theme.typography.body,
                    color = theme.colors.muted,
                )
            }
        }

        AtharCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                AtharText(
                    text = stringResource(R.string.settings_income_sources_selected),
                    style = theme.typography.headline,
                )
                if (settings.eligibleSenders.isEmpty()) {
                    AtharText(
                        text = stringResource(R.string.settings_income_sources_none),
                        style = theme.typography.body,
                        color = theme.colors.muted,
                    )
                } else {
                    settings.eligibleSenders.sorted().forEach { sender ->
                        SenderToggleRow(
                            label = sender,
                            checked = true,
                            onCheckedChange = { viewModel.toggleSender(sender, false) },
                        )
                    }
                }
            }
        }

        AtharCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                AtharText(
                    text = stringResource(R.string.settings_sms_import_discovered),
                    style = theme.typography.headline,
                )
                AtharText(
                    text = stringResource(R.string.settings_income_sources_discover_hint),
                    style = theme.typography.body,
                    color = theme.colors.muted,
                )
                discovered.forEach { item ->
                    val selected = settings.eligibleSenders.any {
                        SmsSenderIds.normalize(it) == SmsSenderIds.normalize(item.address)
                    }
                    SenderToggleRow(
                        label = "${item.address} (${item.messageCount})",
                        checked = selected,
                        onCheckedChange = { viewModel.toggleSender(item.address, it) },
                    )
                }
                AtharTextField(
                    value = draft,
                    onValueChange = viewModel::setManualDraft,
                    label = stringResource(R.string.settings_sms_import_manual_label),
                    modifier = Modifier.fillMaxWidth(),
                )
                AtharText(
                    text = stringResource(R.string.settings_sms_import_manual_add),
                    style = theme.typography.body,
                    color = theme.colors.ember,
                    modifier = Modifier.clickable(onClick = viewModel::addManualSender),
                )
            }
        }
    }
}

@Composable
internal fun SenderToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val theme = AtharTheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        AtharText(text = label, style = theme.typography.body, modifier = Modifier.weight(1f))
    }
}
