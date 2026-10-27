package com.athar.feature.plan.portfolio

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PortfolioPersistenceRoundTripTest {
    private val security = SecurityInput("QA029", "Synthetic holding", "EQUITY")
    private fun document(event: EventInput) = PortfolioPlanDocument(
        currency = "SAR", openingCashAmount = "1000", securities = listOf(security), events = listOf(event),
    )
    private fun trade(kind: PortfolioEntryKind) = EventInput(
        kind = kind, eventId = "synthetic-event", date = "2026-10-20", securityId = "QA029",
        quantity = "5", unitPrice = "20", fee = "1", note = "local synthetic",
    )
    private fun roundTrip(event: EventInput) {
        val input = document(event)
        assertThat(PortfolioPlanCodec.decode(PortfolioPlanCodec.encode(input)))
            .isEqualTo(PortfolioDecodeResult.Success(input))
    }

    @Test fun notedBuyRoundTripsWithoutLosingItsAnnotation() = roundTrip(trade(PortfolioEntryKind.BUY))
    @Test fun notedSellRoundTripsWithoutLosingItsAnnotation() = roundTrip(trade(PortfolioEntryKind.SELL))
    @Test fun notedCashDividendRoundTripsWithoutLosingItsAnnotation() = roundTrip(EventInput(
        kind = PortfolioEntryKind.CASH_DIVIDEND, eventId = "dividend", date = "2026-10-21",
        securityId = "QA029", amount = "5", destination = "PORTFOLIO_CASH", note = "dividend memo",
    ))
    @Test fun notedReinvestmentRoundTripsWithoutLosingItsAnnotation() = roundTrip(EventInput(
        kind = PortfolioEntryKind.REINVESTMENT, eventId = "reinvest", date = "2026-10-21",
        securityId = "QA029", amount = "20", unitPrice = "20", quantity = "1", note = "reinvestment memo",
    ))
    @Test fun fundingWithdrawalAndFeeDoNotRequireAnOptionalNote() {
        for (kind in listOf(PortfolioEntryKind.CONTRIBUTION, PortfolioEntryKind.WITHDRAWAL, PortfolioEntryKind.FEE)) {
            roundTrip(EventInput(kind = kind, eventId = "optional-note", date = "2026-10-21", amount = "10"))
        }
    }
    @Test fun actualNativeStatementShapeRemainsReadableAfterEncoding() {
        val csv = PortfolioStatementImport.COLUMNS.joinToString(",") +
            "\nBUY,wm029-buy,2026-10-20,QA029,QA Synthetic Holding,EQUITY,5,20,1,,,local synthetic" +
            "\nQUOTE,,2026-10-21,QA029,,,,22,,,,"
        val preview = PortfolioStatementImport.preview(
            PortfolioPlanDocument(currency = "SAR", openingCashAmount = "1000"),
            PortfolioStatementImport.parseCsv(csv),
        )
        assertThat(preview.canApply).isTrue()
        val input = requireNotNull(preview.merged)
        assertThat(PortfolioPlanCodec.decode(PortfolioPlanCodec.encode(input)))
            .isEqualTo(PortfolioDecodeResult.Success(input))
    }
    @Test fun suppliedNumericFeeCannotSilentlyBecomeAnAbsentFee() {
        val json = PortfolioPlanCodec.encode(document(trade(PortfolioEntryKind.BUY).copy(note = null)))
            .replace("\"fee\":\"1\"", "\"fee\":1")
        assertThat(PortfolioPlanCodec.decode(json)).isInstanceOf(PortfolioDecodeResult.Malformed::class.java)
    }
    @Test fun suppliedNumericAssetClassCannotSilentlyBecomeUnclassified() {
        val json = PortfolioPlanCodec.encode(document(trade(PortfolioEntryKind.BUY).copy(note = null)))
            .replace("\"assetClass\":\"EQUITY\"", "\"assetClass\":1")
        assertThat(PortfolioPlanCodec.decode(json)).isInstanceOf(PortfolioDecodeResult.Malformed::class.java)
    }
    @Test fun importCannotEnableApplyForFieldsThatWouldFailPersistedReload() {
        val csv = PortfolioStatementImport.COLUMNS.joinToString(",") +
            "\nBUY,unsupported-fields,2026-10-20,QA029,Synthetic holding,EQUITY,5,20,1,999,,"
        val preview = PortfolioStatementImport.preview(
            PortfolioPlanDocument(currency = "SAR", openingCashAmount = "1000"),
            PortfolioStatementImport.parseCsv(csv),
        )
        assertThat(preview.canApply).isFalse()
        assertThat(preview.rejection).isEqualTo("UNSUPPORTED_EVENT_FIELDS")
        assertThat(encodeLoadablePortfolio(requireNotNull(preview.merged))).isNull()
    }
    @Test fun unknownFieldsRemainRetainedButUnreadable() {
        val json = PortfolioPlanCodec.encode(document(trade(PortfolioEntryKind.BUY).copy(note = null)))
            .replace("\"eventId\":", "\"unknownField\":\"not accepted\",\"eventId\":")
        assertThat(PortfolioPlanCodec.decode(json)).isInstanceOf(PortfolioDecodeResult.Malformed::class.java)
    }
}
