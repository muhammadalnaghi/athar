package com.athar.feature.plan.cashforecast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.model.cashforecast.CashForecastDisclosure
import com.athar.core.domain.model.cashforecast.CashForecastRejectionReason
import com.athar.core.domain.model.cashforecast.SettlementIncompatibility
import com.athar.core.domain.model.cashforecast.SubjectKind
import com.athar.feature.plan.R

@Composable
internal fun DisclosuresBlock(disclosures: List<CashForecastDisclosure>) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(
            text = stringResource(R.string.plan_cash_path_result_disclosures_header),
            style = theme.typography.overline,
            color = theme.colors.muted,
        )
        disclosures.forEach { disclosure ->
            disclosureLines(disclosure).forEach { line ->
                AtharText(text = line, style = theme.typography.caption, color = theme.colors.muted)
            }
        }
    }
}

/** Typed disclosure meanings; one disclosure may expand to more than one honest line. */

@Composable
internal fun disclosureLines(disclosure: CashForecastDisclosure): List<String> = when (disclosure) {
    is CashForecastDisclosure.IntradayMinimaUnknown ->
        listOf(stringResource(R.string.plan_cash_path_result_disc_intraday))
    is CashForecastDisclosure.DuplicateCollapsed ->
        listOf(
            stringResource(
                R.string.plan_cash_path_result_disc_duplicate,
                disclosure.id,
                kindLabel(disclosure.kind),
                disclosure.occurrences,
            ),
        )
    is CashForecastDisclosure.ContradictoryWithheld ->
        listOf(
            stringResource(
                R.string.plan_cash_path_result_disc_contradictory,
                disclosure.id,
                kindLabel(disclosure.kind),
                disclosure.occurrences,
            ),
        )
    is CashForecastDisclosure.VerifiedSettlement ->
        listOf(
            stringResource(
                R.string.plan_cash_path_result_disc_verified,
                disclosure.expectationId,
                disclosure.postedEffectId,
            ),
        )
    is CashForecastDisclosure.UnresolvedSettlementReference ->
        listOf(
            stringResource(
                R.string.plan_cash_path_result_disc_unresolved,
                disclosure.postedEffectId,
                disclosure.referencedExpectationId,
            ),
        )
    is CashForecastDisclosure.IncompatibleSettlementLink ->
        listOf(
            stringResource(
                R.string.plan_cash_path_result_disc_incompatible,
                disclosure.expectationId,
                disclosure.postedEffectId,
                incompatibilityLabel(disclosure.reason),
            ),
        )
    is CashForecastDisclosure.UnknownInitialReserves ->
        listOf(
            stringResource(R.string.plan_cash_path_result_disc_unknown_initial_reserves, disclosure.currency),
        )
    is CashForecastDisclosure.MissingAnchor ->
        listOf(stringResource(R.string.plan_cash_path_result_disc_missing_anchor, disclosure.currency))
    is CashForecastDisclosure.UnsupportedCurrency ->
        listOf(stringResource(R.string.plan_cash_path_result_disc_unsupported_currency, disclosure.currency))
    is CashForecastDisclosure.PrecisionExceeded ->
        listOf(
            stringResource(
                R.string.plan_cash_path_result_disc_precision,
                disclosure.sourceId,
                disclosure.currency,
                disclosure.scale,
                disclosure.maxScale,
            ),
        )
    is CashForecastDisclosure.AttestationIncomplete -> buildList {
        if (!disclosure.scopeComplete) {
            add(stringResource(R.string.plan_cash_path_result_disc_attestation_scope))
        }
        if (!disclosure.anchorFresh) {
            add(stringResource(R.string.plan_cash_path_result_disc_attestation_fresh))
        }
    }
    is CashForecastDisclosure.UnavailableAmount ->
        listOf(
            stringResource(
                R.string.plan_cash_path_result_disc_unavailable_amount,
                disclosure.id,
                kindLabel(disclosure.kind),
                disclosure.currency,
            ),
        )
    is CashForecastDisclosure.UnavailableDate ->
        listOf(
            stringResource(
                R.string.plan_cash_path_result_disc_unavailable_date,
                disclosure.id,
                kindLabel(disclosure.kind),
                disclosure.currency,
            ),
        )
    is CashForecastDisclosure.AmbiguousSettlementClaimants ->
        listOf(
            stringResource(
                R.string.plan_cash_path_result_disc_ambiguous,
                disclosure.expectationId,
                disclosure.claimantIds.joinToString(", "),
            ),
        )
    is CashForecastDisclosure.ReserveUnderflowUnknown ->
        listOf(
            stringResource(
                R.string.plan_cash_path_result_disc_reserve_underflow,
                disclosure.currency,
                disclosure.reservationId,
            ),
        )
}

@Composable
internal fun rejectionLine(reason: CashForecastRejectionReason): String = stringResource(
    when (reason) {
        CashForecastRejectionReason.HORIZON_END_BEFORE_KNOWLEDGE_ASOF ->
            R.string.plan_cash_path_result_rej_horizon_before_knowledge
        CashForecastRejectionReason.HORIZON_EXCEEDS_LIMIT ->
            R.string.plan_cash_path_result_rej_horizon_exceeds_limit
        CashForecastRejectionReason.EFFECT_LIMIT_EXCEEDED ->
            R.string.plan_cash_path_result_rej_effect_limit
        CashForecastRejectionReason.ANCHOR_AFTER_KNOWLEDGE_ASOF ->
            R.string.plan_cash_path_result_rej_anchor_after_knowledge
        CashForecastRejectionReason.POSTED_EFFECT_BEYOND_KNOWLEDGE_ASOF ->
            R.string.plan_cash_path_result_rej_posted_beyond_knowledge
        CashForecastRejectionReason.BLANK_EFFECT_IDENTITY ->
            R.string.plan_cash_path_result_rej_blank_identity
        CashForecastRejectionReason.DUPLICATE_ANCHOR_CURRENCY ->
            R.string.plan_cash_path_result_rej_duplicate_anchor
        CashForecastRejectionReason.UNAVAILABLE_AMOUNT_WITHOUT_CURRENCY ->
            R.string.plan_cash_path_result_rej_amount_without_currency
        CashForecastRejectionReason.UNRESOLVABLE_CURRENCY_HINT ->
            R.string.plan_cash_path_result_rej_unresolvable_hint
        CashForecastRejectionReason.CURRENCY_HINT_CONTRADICTION ->
            R.string.plan_cash_path_result_rej_hint_contradiction
        CashForecastRejectionReason.NEGATIVE_RESERVE_MAGNITUDE ->
            R.string.plan_cash_path_result_rej_negative_reserve
        CashForecastRejectionReason.INITIAL_RESERVE_CURRENCY_MISMATCH ->
            R.string.plan_cash_path_result_rej_reserve_currency_mismatch
        CashForecastRejectionReason.ARITHMETIC_UNSUPPORTED ->
            R.string.plan_cash_path_result_rej_arithmetic
    },
)

@Composable
internal fun kindLabel(kind: SubjectKind): String = stringResource(
    when (kind) {
        SubjectKind.CASH_EFFECT -> R.string.plan_cash_path_result_kind_cash
        SubjectKind.RESERVATION -> R.string.plan_cash_path_result_kind_reservation
    },
)

@Composable
internal fun incompatibilityLabel(reason: SettlementIncompatibility): String = stringResource(
    when (reason) {
        SettlementIncompatibility.CURRENCY_MISMATCH ->
            R.string.plan_cash_path_result_incompat_currency_mismatch
        SettlementIncompatibility.SIGN_MISMATCH ->
            R.string.plan_cash_path_result_incompat_sign_mismatch
        SettlementIncompatibility.INSUFFICIENT_AMOUNT ->
            R.string.plan_cash_path_result_incompat_insufficient
        SettlementIncompatibility.TARGET_NOT_EXPECTED ->
            R.string.plan_cash_path_result_incompat_target_not_expected
        SettlementIncompatibility.DOUBLE_SETTLEMENT ->
            R.string.plan_cash_path_result_incompat_double_settlement
        SettlementIncompatibility.CLAIMANT_NOT_POSTED ->
            R.string.plan_cash_path_result_incompat_claimant_not_posted
        SettlementIncompatibility.AMOUNT_UNAVAILABLE ->
            R.string.plan_cash_path_result_incompat_amount_unavailable
        SettlementIncompatibility.DATE_UNAVAILABLE ->
            R.string.plan_cash_path_result_incompat_date_unavailable
        SettlementIncompatibility.CONFLICTING_IDENTITY ->
            R.string.plan_cash_path_result_incompat_conflicting_identity
    },
)

/** Localized end-of-day label via the resource-reactive locale, never the JVM default. */
