package com.athar.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.model.CatalogRefreshPhase
import com.athar.core.domain.model.MerchantCatalogState

/**
 * Settings card for the opt-in signed shared merchant catalog (ENH-009).
 * Off by default; candid privacy disclosure; honest busy/offline/failed/
 * unsupported states; refresh + reset actions. A downloaded catalog stays
 * usable offline even with updates disabled — the copy says so.
 */
@Composable
internal fun MerchantCatalogCard(
    state: MerchantCatalogState,
    onToggleUpdates: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReset: () -> Unit,
) {
    val theme = AtharTheme
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(
                text = stringResource(R.string.settings_merchant_catalog_title),
                style = theme.typography.headline,
            )
            AtharText(
                text = if (state.updatesEnabled) {
                    stringResource(R.string.settings_merchant_catalog_body_on)
                } else {
                    stringResource(R.string.settings_merchant_catalog_body_off)
                },
                style = theme.typography.body,
                color = theme.colors.muted,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) { }
                    .toggleable(
                        value = state.updatesEnabled,
                        enabled = state.networkSupported,
                        role = Role.Switch,
                        onValueChange = onToggleUpdates,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AtharText(
                    text = if (state.networkSupported) {
                        stringResource(R.string.settings_merchant_catalog_updates_label)
                    } else {
                        stringResource(R.string.settings_merchant_catalog_status_unsupported)
                    },
                    style = theme.typography.body,
                    modifier = Modifier.weight(1f).padding(end = theme.spacing.s),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(theme.spacing.s))
                        .background(
                            when {
                                !state.networkSupported -> theme.colors.divider
                                state.updatesEnabled -> theme.colors.ember
                                else -> theme.colors.divider
                            },
                        )
                        .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
                ) {
                    AtharText(
                        text = if (state.updatesEnabled) {
                            stringResource(R.string.settings_merchant_catalog_toggle_on)
                        } else {
                            stringResource(R.string.settings_merchant_catalog_toggle_off)
                        },
                        style = theme.typography.caption,
                        color = if (state.updatesEnabled && theme.colors.isDark) theme.colors.parchment else theme.colors.ink,
                    )
                }
            }

            val version = state.version
            val entryCount = state.entryCount
            if (version != null && entryCount != null) {
                AtharText(
                    text = stringResource(R.string.settings_merchant_catalog_version, version, entryCount),
                    style = theme.typography.caption,
                    color = theme.colors.olive,
                )
            }
            val checkedAt = state.checkedAt
            if (checkedAt != null) {
                AtharText(
                    text = stringResource(
                        R.string.settings_merchant_catalog_checked,
                        checkedAt.toString().take(10),
                    ),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }

            if (!state.disablePersistencePending) when (state.phase) {
                CatalogRefreshPhase.CHECKING -> AtharText(
                    text = stringResource(R.string.settings_merchant_catalog_status_checking),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
                CatalogRefreshPhase.CURRENT -> AtharText(
                    text = stringResource(R.string.settings_merchant_catalog_status_current),
                    style = theme.typography.caption,
                    color = theme.colors.olive,
                )
                CatalogRefreshPhase.UPDATED -> AtharText(
                    text = stringResource(R.string.settings_merchant_catalog_status_updated),
                    style = theme.typography.caption,
                    color = theme.colors.olive,
                )
                CatalogRefreshPhase.FAILED -> AtharText(
                    text = stringResource(R.string.settings_merchant_catalog_status_failed),
                    style = theme.typography.caption,
                    color = theme.colors.ink,
                )
                CatalogRefreshPhase.UNAVAILABLE -> if (state.networkSupported && state.updatesEnabled) {
                    AtharText(
                        text = stringResource(R.string.settings_merchant_catalog_status_unavailable),
                        style = theme.typography.caption,
                        color = theme.colors.muted,
                    )
                }
                CatalogRefreshPhase.IDLE -> Unit
            }

            if (state.disablePersistencePending) {
                AtharText(
                    text = stringResource(R.string.settings_merchant_catalog_status_unsaved_disable),
                    style = theme.typography.caption,
                    color = theme.colors.ink,
                )
                MerchantCatalogButton(
                    text = stringResource(R.string.settings_merchant_catalog_action_retry_disable),
                    onClick = { onToggleUpdates(false) },
                )
            }

            AtharText(
                text = stringResource(R.string.settings_merchant_catalog_offline_note),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )

            // Refresh only while opted in; Reset stays available even with updates
            // disabled (offline cached library) and even mid-check — the repository
            // cancels the in-flight download when resetting.
            if (state.updatesEnabled || state.version != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                ) {
                    if (state.updatesEnabled) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            MerchantCatalogButton(
                                text = if (state.phase == CatalogRefreshPhase.CHECKING) {
                                    stringResource(R.string.settings_status_in_progress)
                                } else {
                                    stringResource(R.string.settings_merchant_catalog_action_refresh)
                                },
                                onClick = onRefresh,
                                enabled = state.phase != CatalogRefreshPhase.CHECKING,
                                modifier = Modifier.fillMaxHeight(),
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        MerchantCatalogButton(
                            text = stringResource(R.string.settings_merchant_catalog_action_reset),
                            onClick = onReset,
                            enabled = true,
                            modifier = Modifier.fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** Card-local copy of the SettingsScreen primary button, with a disabled state. */
@Composable
private fun MerchantCatalogButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(if (enabled) theme.colors.ember else theme.colors.divider)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(theme.spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(
            text = text,
            style = theme.typography.headline,
            color = if (enabled && theme.colors.isDark) theme.colors.parchment else theme.colors.ink,
        )
    }
}
