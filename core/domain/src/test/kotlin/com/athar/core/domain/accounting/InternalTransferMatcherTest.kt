package com.athar.core.domain.accounting

import com.athar.core.common.money.Money
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

class InternalTransferMatcherTest {

    private val owned = setOf("anb", "d360")

    @Test
    fun `does not match by amount alone across same account`() {
        val legs = listOf(
            leg("a", "anb", TransferDirection.OUT, "1000", 0),
            leg("b", "anb", TransferDirection.IN, "1000", 1),
        )
        val result = InternalTransferMatcher.match(legs, owned)
        assertThat(result.matches).isEmpty()
    }

    @Test
    fun `matches different owned accounts within window`() {
        val legs = listOf(
            leg("out", "anb", TransferDirection.OUT, "1000", 0),
            leg("in", "d360", TransferDirection.IN, "1000", 30 * 60 * 1000L),
        )
        val result = InternalTransferMatcher.match(legs, owned)
        assertThat(result.matches).hasSize(1)
        assertThat(result.matches.single().outgoingId).isEqualTo("out")
        assertThat(result.matches.single().incomingId).isEqualTo("in")
    }

    @Test
    fun `one-to-one prevents one out matching two ins`() {
        val legs = listOf(
            leg("out", "anb", TransferDirection.OUT, "1000", 0),
            leg("in1", "d360", TransferDirection.IN, "1000", 10_000),
            leg("in2", "d360", TransferDirection.IN, "1000", 20_000),
        )
        val result = InternalTransferMatcher.match(legs, owned)
        assertThat(result.matches).hasSize(1)
        assertThat(result.unmatchedNeedsReviewIds).contains("in2")
    }

    @Test
    fun `bank reference beats time proximity`() {
        val legs = listOf(
            leg("out", "anb", TransferDirection.OUT, "1000", 0, ref = "REF-9"),
            leg("near", "d360", TransferDirection.IN, "1000", 5_000, ref = "OTHER"),
            leg("far", "d360", TransferDirection.IN, "1000", 60_000, ref = "REF-9"),
        )
        val result = InternalTransferMatcher.match(legs, owned)
        assertThat(result.matches.single().incomingId).isEqualTo("far")
    }

    private fun leg(
        id: String,
        accountId: String,
        direction: TransferDirection,
        amount: String,
        offsetMs: Long,
        ref: String? = null,
    ) = TransferLeg(
        id = id,
        accountId = accountId,
        direction = direction,
        amount = Money.of(amount),
        occurredAt = Instant.fromEpochMilliseconds(1_700_000_000_000L + offsetMs),
        type = TxType.TRANSFER,
        bankReference = ref,
    )
}
