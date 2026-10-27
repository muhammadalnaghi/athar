package com.athar.ingestion.smsparser

import com.athar.core.common.money.Money
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class RegistryPostingEvidenceTest {
    @Test fun `completed Arabic purchase has independent posted evidence`() {
        val result = parse("شراء بمبلغ 25.00 SAR من متجر عام Date:20-10-2026 12:00")
        assertThat(result.posting.support).isEqualTo(PostingSupport.EXPLICIT_POSTED)
        assertThat(result.posting.state).isEqualTo(PostingState.POSTED)
        assertThat(result.amount).isEqualTo(Money.of("25.00"))
        assertThat(result.bankEventDate).isEqualTo(LocalDate.parse("2026-10-20"))
    }

    @Test fun `completed English purchase has independent posted evidence`() {
        assertThat(parse("Purchase of SAR 25.00 at Public Shop was successful").posting.support)
            .isEqualTo(PostingSupport.EXPLICIT_POSTED)
    }

    @Test fun `parse success without movement language is heuristic`() {
        assertThat(parse("Public merchant reference 25.00").posting.support)
            .isEqualTo(PostingSupport.HEURISTIC_ONLY)
    }

    @Test fun `bank date missing never becomes receive date`() {
        assertThat(parse("شراء بمبلغ 25.00 SAR من متجر").bankEventDate).isNull()
    }

    @Test fun `declined purchase overrides embedded posted wording`() {
        for (body in listOf("Purchase of SAR 25.00 was declined", "شراء بمبلغ 25.00، العملية مرفوضة")) {
            val result = parse(body)
            assertThat(result.posting.state).isEqualTo(PostingState.DECLINED)
            assertThat(result.posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
        }
    }

    @Test fun `negated completed Arabic verb is not a completed payment`() {
        for (body in listOf("لم يتم خصم مبلغ 25.00 SAR", "لم تتم عملية شراء بمبلغ 25.00 SAR")) {
            assertThat(parse(body).posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
        }
    }

    @Test fun `negative English completion is not a completed payment`() {
        for (body in listOf("Purchase of SAR 25.00 was not completed", "Purchase at Public Shop was unsuccessful")) {
            assertThat(parse(body).posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
        }
    }

    @Test fun `future debit is scheduled rather than completed`() {
        for (body in listOf("سيتم خصم مبلغ 25.00 SAR", "SAR 25.00 will be debited for purchase at Public Shop")) {
            assertThat(parse(body).posting.state).isEqualTo(PostingState.SCHEDULED)
        }
    }

    @Test fun `authorization and held purchase are not posted money`() {
        for (body in listOf("Purchase of SAR 25.00 authorization hold", "شراء بمبلغ 25.00، المبلغ محجوز")) {
            assertThat(parse(body).posting.state).isEqualTo(PostingState.AUTHORIZED)
        }
    }

    @Test fun `released purchase hold is not a new purchase`() {
        assertThat(parse("Purchase of SAR 25.00 temporary hold was released").posting.state)
            .isEqualTo(PostingState.RELEASED)
    }

    @Test fun `payment instruction and verification are not completed evidence`() {
        for (body in listOf("To purchase at Public Shop pay SAR 25.00", "Confirm purchase of SAR 25.00 with verification code")) {
            assertThat(parse(body).posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
        }
    }

    @Test fun `merchant paid name cannot certify bare amount shape`() {
        assertThat(parse("Merchant: PAID Amount: SAR 25.00").posting.support)
            .isEqualTo(PostingSupport.HEURISTIC_ONLY)
    }

    @Test fun `pending processor descriptor cannot borrow completed purchase heading`() {
        for (descriptor in listOf("RIDE*PEND", "RIDE* PEND", "RIDE*PENDING")) {
            val result = parse("Online Purchase Amount: SAR 25.00 At: $descriptor Date:20-10-2026 12:00")
            assertThat(result.posting.state).isEqualTo(PostingState.AUTHORIZED)
            assertThat(result.posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
        }
    }

    @Test fun `negative completion with intervening words is never posted evidence`() {
        for (body in listOf("Online Purchase could not be completed", "Purchase was not successfully completed", "Purchase could not be processed")) {
            assertThat(parse(body).posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
        }
    }

    @Test fun `ordinary merchant spelling and conditional safety footer keep purchase proof`() {
        for (body in listOf("Online Purchase At: PEND*BOOKS Amount: SAR 25.00", "Purchase of SAR 25.00 at Public Shop. If you did not make this purchase contact your bank")) {
            assertThat(parse(body).posting.support).isEqualTo(PostingSupport.EXPLICIT_POSTED)
        }
    }

    private fun parse(body: String): ParseResult.Success {
        val result = ParseResult.Success(TxType.EXPENSE, Money.of("25.00"), "Public merchant", null, null, null, .95f, "public-proof")
        val template = object : BankTemplate {
            override val id = "public-proof"
            override val senderMatcher = SenderMatcher.Exact("PublicBank")
            override fun tryParse(body: String, receivedAt: Instant) = result
        }
        return TemplateBasedSmsParser(listOf(template)).parse(
            RawIngestEvent("public-event", IngestSource.SMS, "PublicBank", body, Instant.parse("2026-10-21T10:00:00Z"), "public-source"),
        ) as ParseResult.Success
    }
}
