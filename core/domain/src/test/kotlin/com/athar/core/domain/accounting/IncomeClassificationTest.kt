package com.athar.core.domain.accounting

import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class IncomeClassificationTest {

    private val owned = setOf("anb", "d360")
    private val incomeSenders = setOf("ANB", "ANBBank")

    @Test
    fun `salary from eligible sender counts as income`() {
        val d = IncomeClassification.decide(
            parsedType = TxType.INCOME,
            sender = "ANB",
            body = "Salary credited SAR 12000",
            accountId = "anb",
            incomeEligibleSenders = incomeSenders,
            ownedAccountIds = owned,
            matchedAsInternalTransfer = false,
        )
        assertThat(d).isEqualTo(IncomeDecision.COUNT_AS_INCOME)
    }

    @Test
    fun `eligible sender without salary language needs review`() {
        val d = IncomeClassification.decide(
            parsedType = TxType.INCOME,
            sender = "ANB",
            body = "Incoming Transfer SAR 1000",
            accountId = "anb",
            incomeEligibleSenders = incomeSenders,
            ownedAccountIds = owned,
            matchedAsInternalTransfer = false,
        )
        assertThat(d).isEqualTo(IncomeDecision.NEEDS_REVIEW)
    }

    @Test
    fun `d360 credit without ownership match needs review not auto-transfer`() {
        val d = IncomeClassification.decide(
            parsedType = TxType.INCOME,
            sender = "D360 Bank",
            body = "Incoming Transfer SAR 1000",
            accountId = "d360",
            incomeEligibleSenders = incomeSenders,
            ownedAccountIds = owned,
            matchedAsInternalTransfer = false,
        )
        assertThat(d).isEqualTo(IncomeDecision.NEEDS_REVIEW)
    }

    @Test
    fun `ownership-matched transfer is internal transfer`() {
        val d = IncomeClassification.decide(
            parsedType = TxType.INCOME,
            sender = "D360 Bank",
            body = "Incoming Transfer SAR 1000",
            accountId = "d360",
            incomeEligibleSenders = incomeSenders,
            ownedAccountIds = owned,
            matchedAsInternalTransfer = true,
        )
        assertThat(d).isEqualTo(IncomeDecision.INTERNAL_TRANSFER)
    }

    @Test
    fun `empty income sources never auto-count`() {
        val d = IncomeClassification.decide(
            parsedType = TxType.INCOME,
            sender = "ANB",
            body = "Salary credited",
            accountId = "anb",
            incomeEligibleSenders = emptySet(),
            ownedAccountIds = owned,
            matchedAsInternalTransfer = false,
        )
        assertThat(d).isEqualTo(IncomeDecision.NEEDS_REVIEW)
    }

    @Test
    fun `refund language is not salary`() {
        val d = IncomeClassification.decide(
            parsedType = TxType.INCOME,
            sender = "ANB",
            body = "Refund SAR 50",
            accountId = "anb",
            incomeEligibleSenders = incomeSenders,
            ownedAccountIds = owned,
            matchedAsInternalTransfer = false,
        )
        assertThat(d).isEqualTo(IncomeDecision.REFUND_OR_REVERSAL)
    }
}
