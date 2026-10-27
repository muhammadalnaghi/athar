package com.athar.ingestion.smsparser

import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.financial.PostingState
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/**
 * WM049: numeric administrative/authentication notices from known bank senders must
 * be Ignored by the global ignore layer instead of falling through to the universal
 * fallback, which reads bare identifier digits (account/reference/OTP codes) as
 * amounts and posts fake EXPENSE rows.
 *
 * Adversarial controls: any currency-qualified amount or posting verb keeps the body
 * parseable — appended admin/code wording never suppresses real money, and pending /
 * declined / refund evidence is preserved. All names and values are fictional.
 */
class GlobalBankIgnoreNumericServiceNotices049Test {
    private val parser = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates())
    private val received = Instant.parse("2026-10-25T12:00:00Z")

    // --- Positively identified numeric service notices -> Ignored ---

    @Test fun beneficiaryCrossLineActivationWithAccountNumber() =
        ignored(
            "Alinma",
            "Beneficiary: QA Recipient\nHas been successfully activated Account number:11112222\nAt:2026-10-25 12:00",
        )

    @Test fun beneficiaryActivationInlineWithReference() =
        ignored(
            "Alinma",
            "Beneficiary activation: QA Recipient has been activated on2026-10-25 Reference:11112222",
        )

    @Test fun beneficiarySuccessfullyDeletedWithReference() =
        ignored(
            "RiyadBank",
            "Beneficiary: QA Recipient has been successfully deleted Reference:99887766",
        )

    @Test fun plainCodeInstructionForAccountOpening() =
        ignored("AlRajhiBank", "Please use the code:123456\nTo:open a new current account")

    @Test fun arabicOtpWarningWithPlainCode() =
        ignored("SNB-AlAhli", "عزيزي العميل، لا تشارك هذا الرمز مع أي شخص.\nلديك رمز 123456")

    @Test fun arabicOtpWarningWithArabicDigits() =
        ignored("SNB-AlAhli", "عزيزي العميل، لا تشارك هذا الرمز مع أي شخص.\nلديك رمز ١٢٣٤٥٦")

    // --- Adversarial real-money controls stay parseable ---

    @Test fun postedPurchaseWithAppendedCodeWordingStaysParseable() {
        val result = parser.parse(
            event(
                "AlRajhiBank",
                "PoS purchase\nCard:1234\nAt: Example Shop\nAmount:25 SAR\nPlease use the code:123456",
            ),
        )
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val success = result as ParseResult.Success
        assertThat(success.amount.amount.compareTo(java.math.BigDecimal("25"))).isEqualTo(0)
        assertThat(success.type).isEqualTo(TxType.EXPENSE)
    }

    @Test fun arabicPostedDebitWithAppendedOtpWarningStaysParseable() {
        val result = parser.parse(
            event(
                "AlRajhiBank",
                "تمت عملية شراء بمبلغ ١٠٠٫٥٠ ر.س لدى متجر تجريبي\nلا تشارك هذا الرمز مع أي شخص ١٢٣٤٥٦",
            ),
        )
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val success = result as ParseResult.Success
        assertThat(success.amount.amount.compareTo(java.math.BigDecimal("100.50"))).isEqualTo(0)
        assertThat(success.type).isEqualTo(TxType.EXPENSE)
    }

    @Test fun refundWithAppendedBeneficiaryWordingStaysIncome() {
        val result = parser.parse(
            event(
                "Alinma",
                "Refund of 30.50 SAR received from QA Merchant\nBeneficiary: QA Merchant has been activated",
            ),
        )
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val success = result as ParseResult.Success
        assertThat(success.amount.amount.compareTo(java.math.BigDecimal("30.50"))).isEqualTo(0)
        assertThat(success.type).isEqualTo(TxType.INCOME)
    }

    @Test fun declinedPurchaseWithAppendedOtpWordingKeepsDeclinedEvidence() {
        val result = parser.parse(
            event(
                "RiyadBank",
                "Purchase of SAR 25.00 at QA Shop was declined\nلديك رمز 987654",
            ),
        )
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val success = result as ParseResult.Success
        assertThat(success.amount.amount.compareTo(java.math.BigDecimal("25"))).isEqualTo(0)
        assertThat(success.posting.state).isEqualTo(PostingState.DECLINED)
    }

    // --- Unknown numeric text stays diagnosable (never blanket-ignored) ---

    @Test fun unknownNumericBankTextIsNotIgnored() {
        val result = parser.parse(event("AlRajhiBank", "Unrecognized bank notice with reference 11112222"))
        assertThat(result).isNotInstanceOf(ParseResult.Ignored::class.java)
    }

    private fun ignored(sender: String, body: String) {
        assertThat(parser.parse(event(sender, body))).isEqualTo(ParseResult.Ignored)
    }

    private fun event(sender: String, body: String) = RawIngestEvent(
        id = "wm049-synthetic-notice", rawId = "wm049-synthetic-notice", source = IngestSource.SMS,
        sender = sender, body = body, receivedAt = received,
    )
}
