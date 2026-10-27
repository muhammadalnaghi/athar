package com.athar.feature.plan.cashforecast

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.athar.core.common.money.Money
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.model.cashforecast.CashForecastDisclosure
import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.CashForecastRejectionReason
import com.athar.core.domain.model.cashforecast.CashForecastResult
import com.athar.core.domain.model.cashforecast.CurrencyPartitionForecast
import com.athar.core.domain.model.cashforecast.DatedCashDay
import com.athar.core.domain.model.cashforecast.ForecastEffectReference
import com.athar.core.domain.model.cashforecast.PartitionHeadline
import com.athar.core.domain.model.cashforecast.PartitionStatus
import com.athar.core.domain.model.cashforecast.SubjectKind
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.LocalDate

/**
 * Synthetic previews for the cash-path result states (spec WM028-P: successful,
 * incomplete, unavailable). All figures are preview-only samples — production UI
 * renders only real model output. Preview handlers may be no-ops; the production
 * caller must wire a real full-path drilldown.
 */
@Preview(name = "Cash path - confident", showBackground = true, locale = "ar")
@Composable
private fun CashPathResultConfidentPreview() {
    AtharTheme {
        CashPathResult(outcome = confidentOutcome(), onOpenPath = {})
    }
}

@Preview(name = "Cash path - partial", showBackground = true, locale = "ar")
@Composable
private fun CashPathResultPartialPreview() {
    AtharTheme {
        CashPathResult(outcome = partialOutcome(), onOpenPath = {})
    }
}

@Preview(name = "Cash path - withheld", showBackground = true, locale = "ar")
@Composable
private fun CashPathResultWithheldPreview() {
    AtharTheme {
        CashPathResult(outcome = withheldOutcome(), onOpenPath = {})
    }
}

@Preview(name = "Cash path - rejected", showBackground = true, locale = "ar")
@Composable
private fun CashPathResultRejectedPreview() {
    AtharTheme {
        CashPathResult(outcome = rejectedOutcome(), onOpenPath = {})
    }
}

private const val PreviewCurrency = "SAR"

private fun previewDay(
    date: String,
    cash: String,
    reserved: String?,
    available: String?,
    effectId: String? = null,
): DatedCashDay = DatedCashDay(
    date = LocalDate.parse(date),
    cash = Money.of(cash, PreviewCurrency),
    reserved = reserved?.let { Money.of(it, PreviewCurrency) },
    available = available?.let { Money.of(it, PreviewCurrency) },
    supportingEffects = (effectId?.let { id ->
        listOf(ForecastEffectReference(SubjectKind.CASH_EFFECT, id))
    } ?: emptyList()).toPersistentList(),
)

private fun confidentOutcome(): CashForecastOutcome = CashForecastOutcome.Forecast(
    CashForecastResult(
        partitions = listOf(
            CurrencyPartitionForecast(
                currency = PreviewCurrency,
                status = PartitionStatus.CONFIDENT,
                opening = previewDay("2026-01-31", "1000.00", "100.00", "900.00"),
                path = listOf(
                    previewDay("2026-01-31", "1000.00", "100.00", "900.00"),
                    previewDay("2026-02-05", "700.00", "100.00", "600.00", "bill-feb"),
                    previewDay("2026-02-10", "250.00", "100.00", "150.00", "bill-mar"),
                ).toPersistentList(),
                headline = PartitionHeadline(
                    minimumAvailable = Money.of("150.00", PreviewCurrency),
                    minimumAvailableDate = LocalDate.parse("2026-02-10"),
                    shortfall = null,
                    firstNegativeAvailableDate = null,
                    finalBalance = Money.of("250.00", PreviewCurrency),
                ),
            ),
        ).toPersistentList(),
        disclosures = listOf<CashForecastDisclosure>(
            CashForecastDisclosure.IntradayMinimaUnknown,
        ).toPersistentList(),
        decisionReady = true,
    ),
)

private fun partialOutcome(): CashForecastOutcome = CashForecastOutcome.Forecast(
    CashForecastResult(
        partitions = listOf(
            CurrencyPartitionForecast(
                currency = PreviewCurrency,
                status = PartitionStatus.PARTIAL,
                opening = previewDay("2026-01-31", "1000.00", null, null),
                path = listOf(
                    previewDay("2026-01-31", "1000.00", null, null),
                    previewDay("2026-02-05", "700.00", null, null, "bill-feb"),
                    previewDay("2026-02-10", "250.00", null, null, "bill-mar"),
                ).toPersistentList(),
                headline = null,
            ),
        ).toPersistentList(),
        disclosures = listOf<CashForecastDisclosure>(
            CashForecastDisclosure.IntradayMinimaUnknown,
            CashForecastDisclosure.UnknownInitialReserves(PreviewCurrency),
        ).toPersistentList(),
        decisionReady = false,
    ),
)

private fun withheldOutcome(): CashForecastOutcome = CashForecastOutcome.Forecast(
    CashForecastResult(
        partitions = listOf(
            CurrencyPartitionForecast(
                currency = "USD",
                status = PartitionStatus.WITHHELD,
                opening = null,
                path = emptyList<DatedCashDay>().toPersistentList(),
                headline = null,
            ),
        ).toPersistentList(),
        disclosures = listOf<CashForecastDisclosure>(
            CashForecastDisclosure.IntradayMinimaUnknown,
            CashForecastDisclosure.MissingAnchor("USD"),
        ).toPersistentList(),
        decisionReady = false,
    ),
)

private fun rejectedOutcome(): CashForecastOutcome = CashForecastOutcome.Rejected(
    reasons = listOf(
        CashForecastRejectionReason.DUPLICATE_ANCHOR_CURRENCY,
        CashForecastRejectionReason.CURRENCY_HINT_CONTRADICTION,
    ).toPersistentList(),
    offendingIds = listOf("anchor-sar", "effect-9").toPersistentList(),
)
