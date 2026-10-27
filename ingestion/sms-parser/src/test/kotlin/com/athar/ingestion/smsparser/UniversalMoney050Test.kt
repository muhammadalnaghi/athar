package com.athar.ingestion.smsparser

import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.financial.PostingState
import com.athar.ingestion.smsparser.universal.UniversalAmountTemplate
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

class UniversalMoney050Test {
    private val received = Instant.parse("2026-10-25T12:00:00Z")
    private val universal = UniversalAmountTemplate()
    private val parser = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates())

    @Test fun realRegistrySelectsForeignAmountNotCardOrSarBalance() {
        val result = success(parser.parse(event("International transaction\nCard:9999\nAmount:USD5.25\nBalance:SAR900.00\nDate:2026-10-25")))
        money(result, "5.25", "USD")
        assertThat(result.templateId).isEqualTo("universal-amount")
    }

    @Test fun labelledAmountWinsOverEarlierBalanceReferenceAndDate() {
        money(parse("Reference:123456\nDate:2026-10-25\nBalance:900.00 SAR\nAmount:12.50 SAR"), "12.50", "SAR")
    }

    @Test fun arabicLabelAndDigitsPreserveMeaningfulKwdPrecision() {
        money(parse("رقم البطاقة:٩٩٩٩\nالمبلغ:١٢٫٣٤٥ KWD\nالرصيد:٩٠٠٫٠٠٠ KWD"), "12.345", "KWD")
    }

    @Test fun leadingCurrencyAndInlineFollowingMerchantStaySupported() {
        money(parse("Card:9999 Amount:USD5.25 at QA Market"), "5.25", "USD")
    }

    @Test fun missingPrimaryValueCannotFallForwardToBalance() {
        assertThat(universal.tryParse("Card:9999\nAmount:\nBalance:SAR900.00", received)).isInstanceOf(ParseResult.Failed::class.java)
    }

    @Test fun malformedExponentIsNotRepairedIntoOneDollar() {
        assertThat(universal.tryParse("Card:9999\nAmount:USD1e3\nBalance:SAR900.00", received)).isInstanceOf(ParseResult.Failed::class.java)
    }

    @Test fun repeatedPrimaryFieldsStayAmbiguous() {
        assertThat(universal.tryParse("Amount:SAR12.50\nAmount:SAR99.00", received)).isInstanceOf(ParseResult.Failed::class.java)
    }

    @Test fun originalForeignPrincipalIsNotAddedToSettlementCountervalue() {
        money(parse("Card:9999\nAmount:5.25 USD 19.69 SAR"), "5.25", "USD")
    }

    @Test fun unlabelledExistingMoneyKeepsItsLiteralCurrency() {
        money(parse("Paid USD5.25 at QA Market"), "5.25", "USD")
    }

    @Test fun amountSelectionDoesNotPromoteDeclinedOrPendingEvidence() {
        for ((word, state) in listOf("declined" to PostingState.DECLINED, "pending" to PostingState.AUTHORIZED)) {
            val result = success(parser.parse(event("International transaction $word\nCard:9999\nAmount:USD5.25\nBalance:SAR900.00")))
            money(result, "5.25", "USD")
            assertThat(result.posting.state).isEqualTo(state)
        }
    }

    @Test fun incomeAndTransferClassificationStayUnchanged() {
        val refund = parse("Refund\nCard:9999\nAmount:USD5.25\nBalance:SAR900.00")
        money(refund, "5.25", "USD")
        assertThat(refund.type).isEqualTo(TxType.INCOME)
        val transfer = parse("Outgoing transfer\nAccount:11112222\nAmount:SAR12.50")
        money(transfer, "12.50", "SAR")
        assertThat(transfer.type).isEqualTo(TxType.TRANSFER)
    }

    private fun parse(body: String) = success(universal.tryParse(body, received))
    private fun success(result: ParseResult): ParseResult.Success {
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        return result as ParseResult.Success
    }
    private fun money(result: ParseResult.Success, amount: String, currency: String) {
        assertThat(result.amount.amount.compareTo(BigDecimal(amount))).isEqualTo(0)
        assertThat(result.amount.currency).isEqualTo(currency)
    }
    private fun event(body: String) = RawIngestEvent(
        id = "wm050-synthetic-money", rawId = "wm050-synthetic-money", source = IngestSource.SMS,
        sender = "BSF", body = body, receivedAt = received,
    )
}
