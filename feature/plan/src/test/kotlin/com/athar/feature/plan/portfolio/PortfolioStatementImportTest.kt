package com.athar.feature.plan.portfolio

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Focused proof for the local statement import engine: exact header contract,
 * inline security registration, row-level evidence with exact line numbers,
 * duplicate skipping, zero/negative quote handling and JSON row typing. The merge
 * must replay through the same workbench engines the saved document uses.
 */
class PortfolioStatementImportTest {

    private val header = "type,eventId,date,securityId,securityName,assetClass,quantity,unitPrice,fee,amount,destination,note"

    private val current = PortfolioPlanDocument(
        currency = "SAR",
        openingCashAmount = "2000",
        securities = listOf(SecurityInput("ALRAJHI", "الراجحي", "EQUITY")),
        events = listOf(
            EventInput(
                kind = PortfolioEntryKind.BUY, eventId = "e1", date = "2026-01-15",
                securityId = "ALRAJHI", quantity = "10", unitPrice = "95", fee = "0",
            ),
        ),
        quotes = emptyList(),
    )

    private fun preview(text: String) = PortfolioStatementImport.preview(
        current,
        if (text.trim().startsWith("[")) {
            PortfolioStatementImport.parseJsonRows(text)
        } else {
            PortfolioStatementImport.parseCsv(text)
        },
    )

    @Test
    fun `csv happy path registers inline security and merges sorted quotes and events`() {
        val csv = """
            $header
            BUY,e2,2026-01-20,TADAWUL,سهم تجريبي,EQUITY,5,100,1.5
            QUOTE,,2026-02-01,TADAWUL,,,,98.5
            CONTRIBUTION,e3,2026-01-25,,,,,,,500,,
        """.trimIndent()
        val result = preview(csv)
        assertThat(result.canApply).isTrue()
        assertThat(result.rejection).isNull()
        assertThat(result.validEventCount).isEqualTo(2)
        assertThat(result.validQuoteCount).isEqualTo(1)
        assertThat(result.newSecurities.single().securityId).isEqualTo("TADAWUL")
        val merged = result.merged!!
        // Entries stay date-sorted across old and imported events.
        assertThat(merged.events.map { it.eventId }).containsExactly("e1", "e2", "e3").inOrder()
        assertThat(merged.quotes.single().unitPrice).isEqualTo("98.5")
        // The merged document replays through the same reviewed engines before any write.
        assertThat(PortfolioWorkbench.build(merged).rejection).isNull()
    }

    @Test
    fun `bad header is a failed parse that disables apply`() {
        val result = preview("wrong,header\nBUY,e2,2026-01-20")
        assertThat(result.canApply).isFalse()
        assertThat(result.merged).isNull()
        assertThat(result.rejection).isEqualTo("BAD_HEADER")
    }

    @Test
    fun `row errors carry exact line numbers and disable apply`() {
        val csv = """
            $header
            BUY,e2,2026-13-40,TADAWUL,,,,5,100
            BUY,e4,2026-01-20,GHOST,,,,5,100
        """.trimIndent()
        val result = preview(csv)
        assertThat(result.canApply).isFalse()
        assertThat(result.merged).isNull()
        assertThat(result.errors.map { it.lineNumber to it.problem }).containsExactly(
            2 to RowProblem.BAD_DATE,
            3 to RowProblem.UNKNOWN_SECURITY,
        ).inOrder()
    }

    @Test
    fun `duplicate event id is skipped and listed while other valid rows still apply`() {
        val csv = """
            $header
            BUY,e1,2026-02-10,ALRAJHI,,,,1,99
            QUOTE,,2026-02-01,ALRAJHI,,,,97.5
        """.trimIndent()
        val result = preview(csv)
        assertThat(result.duplicateEventIds).containsExactly("e1")
        assertThat(result.validEventCount).isEqualTo(0)
        assertThat(result.validQuoteCount).isEqualTo(1)
        assertThat(result.canApply).isTrue()
        assertThat(result.merged!!.quotes.single().unitPrice).isEqualTo("97.5")
    }

    @Test
    fun `quote rows accept zero as known-worthless but refuse negative`() {
        val zero = preview(header + "\nQUOTE,,2026-02-02,ALRAJHI,,,,0")
        assertThat(zero.canApply).isTrue()
        assertThat(zero.validQuoteCount).isEqualTo(1)
        val negative = preview(header + "\nQUOTE,,2026-02-03,ALRAJHI,,,,-1")
        assertThat(negative.canApply).isFalse()
        assertThat(negative.errors.single().problem).isEqualTo(RowProblem.MISSING_POSITIVE_NUMBER)
    }

    @Test
    fun `json rows require string fields and refuse unknown keys`() {
        val good = preview("""[{"type":"BUY","eventId":"j1","date":"2026-01-15","securityId":"ALRAJHI","quantity":"1","unitPrice":"95","fee":"0"}]""")
        assertThat(good.canApply).isTrue()
        assertThat(good.validEventCount).isEqualTo(1)
        val numericAmount = preview("""[{"type":"CONTRIBUTION","eventId":"j2","date":"2026-01-16","amount":500}]""")
        assertThat(numericAmount.canApply).isFalse()
        assertThat(numericAmount.errors.single().problem).isEqualTo(RowProblem.BAD_QUOTING)
        val unknownKey = preview("""[{"type":"CONTRIBUTION","eventId":"j3","date":"2026-01-16","amount":"500","extra":"x"}]""")
        assertThat(unknownKey.errors.single().problem).isEqualTo(RowProblem.BAD_COLUMN_COUNT)
    }

    @Test
    fun `empty input is refused with a localized-safe token`() {
        val result = preview("   ")
        assertThat(result.canApply).isFalse()
        assertThat(result.rejection).isEqualTo("EMPTY_INPUT")
    }
}
