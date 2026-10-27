package com.athar.feature.plan.cashforecast

import com.athar.core.common.money.Money
import com.athar.core.domain.model.cashforecast.CurrencyPartitionForecast
import com.athar.core.domain.model.cashforecast.DatedCashDay
import com.athar.core.domain.model.cashforecast.ForecastEffectReference
import com.athar.core.domain.model.cashforecast.PartitionHeadline
import com.athar.core.domain.model.cashforecast.PartitionStatus
import com.athar.core.domain.model.cashforecast.SubjectKind
import com.google.common.truth.Truth.assertThat
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class CashResultPresentationTest {
    private fun partition(status: PartitionStatus, currency: String = "KWD", days: Int = 5): CurrencyPartitionForecast {
        val path = (1..days).map { day ->
            DatedCashDay(
                LocalDate(2026, 11, day), Money.of("10.123", currency), null, null,
                listOf(ForecastEffectReference(SubjectKind.CASH_EFFECT, "effect-$day")).toPersistentList(),
            )
        }.toPersistentList()
        return CurrencyPartitionForecast(
            currency, status, path.firstOrNull(), path,
            PartitionHeadline(Money.of("-0.001", currency), LocalDate(2026, 11, 1),
                Money.of("0.001", currency), LocalDate(2026, 11, 1), Money.of("10.123", currency)),
        )
    }

    @Test fun `confident headline retains exact one-fils gap and date`() {
        val value = partition(PartitionStatus.CONFIDENT)
        val shown = requireNotNull(cashDisplayHeadline(value))
        assertThat(shown).isSameInstanceAs(value.headline)
        assertThat(shown.minimumAvailable.amount.toPlainString()).isEqualTo("-0.001")
        assertThat(shown.shortfall?.currency).isEqualTo("KWD")
        assertThat(shown.firstNegativeAvailableDate).isEqualTo(LocalDate(2026, 11, 1))
    }

    @Test fun `confident model without headline does not invent one`() {
        assertThat(cashDisplayHeadline(partition(PartitionStatus.CONFIDENT).copy(headline = null))).isNull()
    }

    @Test fun `partial model cannot expose an accidentally supplied headline`() {
        assertThat(cashDisplayHeadline(partition(PartitionStatus.PARTIAL))).isNull()
    }

    @Test fun `withheld model cannot expose an accidentally supplied headline`() {
        assertThat(cashDisplayHeadline(partition(PartitionStatus.WITHHELD))).isNull()
    }

    @Test fun `known prefix retains exact first three original points and evidence`() {
        val value = partition(PartitionStatus.PARTIAL)
        val shown = knownCashPathPrefix(value)
        assertThat(shown).containsExactlyElementsIn(value.path.take(3)).inOrder()
        assertThat(value.path).hasSize(5)
        assertThat(shown.first()).isSameInstanceAs(value.path.first())
        assertThat(shown.first().cash.amount.toPlainString()).isEqualTo("10.123")
        assertThat(shown.first().available).isNull()
        assertThat(shown.first().reserved).isNull()
        assertThat(shown.first().effectIds).containsExactly("effect-1")
    }

    @Test fun `withheld known prefix stays empty even for malformed supplied points`() {
        assertThat(knownCashPathPrefix(partition(PartitionStatus.WITHHELD))).isEmpty()
    }

    @Test fun `empty known path stays empty rather than gaining synthetic zero`() {
        assertThat(knownCashPathPrefix(partition(PartitionStatus.PARTIAL, days = 0))).isEmpty()
    }

    @Test fun `separate literal currencies remain on their own original points`() {
        val kwd = knownCashPathPrefix(partition(PartitionStatus.CONFIDENT))
        val sar = knownCashPathPrefix(partition(PartitionStatus.CONFIDENT, "SAR"))
        assertThat(kwd.map { it.cash.currency }.distinct()).containsExactly("KWD")
        assertThat(sar.map { it.cash.currency }.distinct()).containsExactly("SAR")
    }
}
