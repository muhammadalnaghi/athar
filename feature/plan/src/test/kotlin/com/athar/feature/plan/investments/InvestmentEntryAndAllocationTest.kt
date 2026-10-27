package com.athar.feature.plan.investments

import com.athar.core.common.money.Money
import com.athar.core.domain.model.InvestmentContribution
import com.athar.core.domain.model.InvestmentPool
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import org.junit.jupiter.api.Test

class InvestmentEntryAndAllocationTest {
    @Test
    fun `Arabic and plain decimal input are exact while malformed grouping is rejected`() {
        assertThat(InvestmentEntryRules.parseDecimal("١٬٢٣٤٫٥٦")!!.compareTo(BigDecimal("1234.56"))).isEqualTo(0)
        assertThat(InvestmentEntryRules.parseDecimal("-12.5")!!.compareTo(BigDecimal("-12.5"))).isEqualTo(0)
        listOf("1,23", "1 2", "NaN", "1e3", "", "1.2.3").forEach {
            assertThat(InvestmentEntryRules.parseDecimal(it)).isNull()
        }
    }

    @Test
    fun `legacy writes reject both meaningful third decimals and Long overflow`() {
        assertThat(InvestmentEntryRules.isExactlyStorable(Money.of("1.001", "KWD"))).isFalse()
        assertThat(InvestmentEntryRules.isExactlyStorable(Money.of("1E+30"))).isFalse()
        assertThat(InvestmentEntryRules.isExactlyStorable(Money.of("1.2300"))).isTrue()
        assertThat(InvestmentEntryRules.isExactlyStorable(Money.of("100000001.01"))).isTrue()
    }

    @Test
    fun `SAR100 and USD100 cannot allocate a whole-pool SAR10 return to either owner`() {
        val pool = InvestmentPool(id = "p", name = "Synthetic pool", period = "Manual", totalReturn = Money.of("10"))
        val records = listOf(
            InvestmentContribution(id = "a", poolId = "p", ownerName = "A", amount = Money.of("100")),
            InvestmentContribution(id = "b", poolId = "p", ownerName = "B", amount = Money.of("100", "USD")),
        )
        val result = InvestmentsBreakdown.fromRecords(pool, records)
        assertThat(result.allocationAvailable).isFalse()
        assertThat(result.contributors).hasSize(2)
        assertThat(result.contributors.all { it.shareOfDeclaredReturn == null && it.netHolding == null }).isTrue()
        assertThat(result.roundingRemainder).isNull()
        assertThat(result.currencyGroups.map { it.currency }).containsExactly("SAR", "USD")
    }

    @Test
    fun `negative contribution cannot fabricate ownership or crash record inspection`() {
        val pool = InvestmentPool(id = "p", name = "Synthetic pool", period = "Manual", totalReturn = Money.of("10"))
        val records = listOf(
            InvestmentContribution(id = "a", poolId = "p", ownerName = "A", amount = Money.of("200")),
            InvestmentContribution(id = "b", poolId = "p", ownerName = "B", amount = Money.of("-100")),
        )
        val result = InvestmentsBreakdown.fromRecords(pool, records)
        assertThat(result.allocationAvailable).isFalse()
        assertThat(result.contributors.all { it.compositionFraction == null && it.shareOfDeclaredReturn == null }).isTrue()
    }
}
