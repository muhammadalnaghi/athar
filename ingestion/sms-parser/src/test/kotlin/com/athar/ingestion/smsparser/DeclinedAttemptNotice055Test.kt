package com.athar.ingestion.smsparser

import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.financial.PostingState
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/**
 * WM055 — DeclinedAttemptNotice055 is a pure public classifier for a single
 * genuinely declined payment attempt in prose (event-linked refusal grammar,
 * English and Arabic). Controls keep merchants named DECLINED/FAILED,
 * conditional failures, quoted error text, and separately booked
 * fees/credits/refunds/reversals/retries out, and pin the complete legacy
 * parser outputs (including the WM053 header family) unchanged.
 */
class DeclinedAttemptNotice055Test {
    private val parser = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates())

    // --- Positives: event-linked refusal grammar ---

    @Test fun prosePurchaseDeclinedWithoutAmountSpaceMatches() {
        assertThat(DeclinedAttemptNotice055.matches("Purchase of SAR25.00 at QA was declined")).isTrue()
    }

    @Test fun prosePurchaseDeclinedMatches() {
        assertThat(DeclinedAttemptNotice055.matches("Purchase of SAR 25.00 at Jarir was declined")).isTrue()
    }

    @Test fun paymentWasNotCompletedMatches() {
        assertThat(DeclinedAttemptNotice055.matches("Your payment was not completed")).isTrue()
    }

    @Test fun transactionWasRejectedMatches() {
        assertThat(DeclinedAttemptNotice055.matches("Your transaction was rejected by the bank")).isTrue()
    }

    @Test fun transferHasBeenDeclinedMatches() {
        assertThat(DeclinedAttemptNotice055.matches("Your transfer of SAR 500.00 has been declined")).isTrue()
    }

    @Test fun directPaymentFailedMatches() {
        assertThat(DeclinedAttemptNotice055.matches("Your payment failed due to insufficient funds")).isTrue()
    }

    @Test fun arabicRejectedPurchaseMatches() {
        assertThat(DeclinedAttemptNotice055.matches("عملية شراء مرفوضة بمبلغ 25.00 ريال")).isTrue()
    }

    @Test fun arabicPurchaseDidNotCompleteMatches() {
        assertThat(DeclinedAttemptNotice055.matches("لم تتم عملية الشراء")).isTrue()
    }

    @Test fun arabicVerbedRefusalMatches() {
        assertThat(DeclinedAttemptNotice055.matches("تم رفض عملية الشراء بمبلغ 25.00")).isTrue()
    }

    @Test fun arabicIndicDigitsInRefusalStillMatch() {
        assertThat(DeclinedAttemptNotice055.matches("عملية شراء مرفوضة بمبلغ ٢٥٫٠٠ ريال")).isTrue()
    }

    // --- Negatives: insufficient or blocked shapes ---

    @Test fun merchantNamedDeclinedDoesNotMatch() {
        assertThat(DeclinedAttemptNotice055.matches("Purchase of SAR 10.00 at DECLINED Coffee")).isFalse()
    }

    @Test fun merchantNamedFailedDoesNotMatch() {
        assertThat(DeclinedAttemptNotice055.matches("Purchase of SAR 10.00 at FAILED Coffee")).isFalse()
    }

    @Test fun conditionalFailureClauseDoesNotMatch() {
        assertThat(DeclinedAttemptNotice055.matches("If your payment failed, contact support")).isFalse()
    }

    @Test fun conditionalNotCompletedClauseDoesNotMatch() {
        assertThat(DeclinedAttemptNotice055.matches("In case your payment was not completed we will retry")).isFalse()
    }

    @Test fun quotedErrorBesideCompletedPaymentDoesNotMatch() {
        assertThat(
            DeclinedAttemptNotice055.matches(
                "Terminal showed error \"payment failed\" but your payment completed successfully",
            ),
        ).isFalse()
    }

    @Test fun separatelyDebitedFeeOnSameLineBlocks() {
        assertThat(
            DeclinedAttemptNotice055.matches("Purchase of SAR 25.00 at QA was declined; a fee of SAR 1.00 was debited"),
        ).isFalse()
    }

    @Test fun laterSuccessfulRetryBlocks() {
        assertThat(
            DeclinedAttemptNotice055.matches("Your payment was not completed\nYour payment completed successfully"),
        ).isFalse()
    }

    @Test fun refundAfterRefusedAttemptBlocks() {
        assertThat(DeclinedAttemptNotice055.matches("عملية شراء مرفوضة\nRefund of SAR 12.50 issued")).isFalse()
    }

    @Test fun reversalAfterDeclinedAttemptBlocks() {
        assertThat(DeclinedAttemptNotice055.matches("Purchase of SAR 25.00 at QA was declined\nTransaction reversed")).isFalse()
    }

    @Test fun completedStatusFieldBlocksProseDecline() {
        assertThat(DeclinedAttemptNotice055.matches("Your payment was declined\nStatus:Completed")).isFalse()
    }

    @Test fun creditLimitCauseAloneStillMatches() {
        assertThat(DeclinedAttemptNotice055.matches("Your payment was declined as it exceeded your credit limit")).isTrue()
    }

    @Test fun cardDeclinedWithoutEventNounDoesNotMatch() {
        assertThat(DeclinedAttemptNotice055.matches("Your card was declined")).isFalse()
    }

    @Test fun pendingWordingDoesNotMatch() {
        assertThat(DeclinedAttemptNotice055.matches("Your payment is pending")).isFalse()
    }

    @Test fun questionFormDoesNotMatch() {
        assertThat(DeclinedAttemptNotice055.matches("Was your payment declined?")).isFalse()
    }

    @Test fun arabicMerchantAdjectiveDoesNotCertifyARefusedOperation() {
        assertThat(DeclinedAttemptNotice055.matches("عملية شراء لدى مرفوضة كوفي بمبلغ 25.00 SAR")).isFalse()
    }

    @Test fun aMerchantFieldContainingAnEntireRefusalSentenceIsNotBankEvidence() {
        assertThat(DeclinedAttemptNotice055.matches("Purchase of SAR 25.00\nMerchant: YOUR PAYMENT WAS DECLINED SHOP")).isFalse()
    }

    @Test fun merchantFieldAloneCannotCertifyTheBanksRefusal() {
        assertThat(DeclinedAttemptNotice055.matches("Amount: 25.00 SAR\nMerchant: YOUR PAYMENT WAS DECLINED SHOP")).isFalse()
    }

    @Test fun aSingleInsufficientBalanceNotificationWithPurchaseKindMatches() {
        assertThat(DeclinedAttemptNotice055.matches("Notification: Declined due to Insufficient balance\nTransaction: شراء عبر الإنترنت\nAmount: 25.00 SAR\nCard: 9999")).isTrue()
    }

    @Test fun aRefusedNotificationWithoutPurchaseContextDoesNotMatch() {
        assertThat(DeclinedAttemptNotice055.matches("Notification: Declined due to Insufficient balance\nAmount: 25.00 SAR")).isFalse()
    }

    @Test fun completedRetryBeneathARefusalNotificationStillBlocks() {
        assertThat(DeclinedAttemptNotice055.matches("Notification: Declined due to Insufficient balance\nTransaction: purchase\nStatus: Completed; SAR 25.00 debited")).isFalse()
    }

    @Test fun blankAndAmountOnlyShapesDoNotMatch() {
        assertThat(DeclinedAttemptNotice055.matches("")).isFalse()
        assertThat(DeclinedAttemptNotice055.matches("   ")).isFalse()
        assertThat(DeclinedAttemptNotice055.matches("SAR 25.00")).isFalse()
        assertThat(DeclinedAttemptNotice055.matches("declined")).isFalse()
    }

    // --- Legacy parser outputs remain unchanged (helper is not wired in) ---

    @Test fun legacyProseDeclineStillParsesAsDeclinedSuccess() {
        val result = parse("Purchase of SAR 25.00 at Jarir was declined")
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val row = result as ParseResult.Success
        assertThat(row.posting.state).isEqualTo(PostingState.DECLINED)
        assertThat(row.amount.amount.compareTo(BigDecimal("25.00"))).isEqualTo(0)
        assertThat(row.amount.currency).isEqualTo("SAR")
    }

    @Test fun legacyArabicProseDeclineStillParsesAsDeclinedSuccess() {
        val result = parse("عملية شراء مرفوضة بمبلغ 25.00 ريال")
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val row = result as ParseResult.Success
        assertThat(row.posting.state).isEqualTo(PostingState.DECLINED)
        assertThat(row.amount.amount.compareTo(BigDecimal("25.00"))).isEqualTo(0)
    }

    @Test fun wm053HeaderFamilyStillIgnoredByParser() {
        assertThat(
            parse("Status:Declined\nTransaction:Purchase\nCard:9999\nAmount:12.50 SAR\nAt:QA Market\nDate:2026-10-25"),
        ).isEqualTo(ParseResult.Ignored)
    }

    @Test fun legacyDeclinePlusFeeControlStaysParseable() {
        val result = parse("Purchase of SAR 25.00 at Jarir was declined. A fee of SAR 1.00 was debited")
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
    }

    private fun parse(body: String) = parser.parse(
        RawIngestEvent(
            id = "wm055-synthetic-declined",
            rawId = "wm055-synthetic-declined",
            source = IngestSource.SMS,
            sender = "BSF",
            body = body,
            receivedAt = Instant.parse("2026-10-25T12:00:00Z"),
        ),
    )
}
