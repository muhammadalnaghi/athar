package com.athar.ingestion.smsparser

import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.TxType
import com.athar.ingestion.smsparser.stcbank.StcBankIncomingTransferTemplate
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Synthetic regressions for WM028-06: two-digit slash dates grounded on the SMS
 * receive time (docs/specs/WM028-06-parser-dates.md). Every input below is
 * fabricated for the test; no real corpus record is copied.
 *
 * Determinism: parseSmsDate converts with TimeZone.currentSystemDefault(); every
 * expected value is built through the identical in-process conversion, so results
 * do not depend on the machine timezone. Receive instants are fixed UTC instants
 * chosen so tolerance decisions hold across zone offsets.
 */
class DateExtractionReceivedGroundingTest {

    private val zone = TimeZone.currentSystemDefault()

    private fun expected(y: Int, m: Int, d: Int, hh: Int, mm: Int): Instant =
        LocalDateTime(y, m, d, hh, mm).toInstant(zone)

    private fun at(iso: String): Instant = Instant.parse(iso)

    // --- year-first token flips to the receive date (the real-data defect) ---

    @Test
    fun `year-first slash token resolves to receive date not three years out`() {
        val received = at("2026-08-29T12:05:00Z")
        assertThat(parseSmsDate("26/08/29 12:10", received))
            .isEqualTo(expected(2026, 8, 29, 12, 10))
    }

    @Test
    fun `day-first slash token stays when it matches the receive date`() {
        val received = at("2026-08-29T12:05:00Z")
        assertThat(parseSmsDate("29/08/26 12:10", received))
            .isEqualTo(expected(2026, 8, 29, 12, 10))
    }

    @Test
    fun `null receivedAt keeps legacy day-first decoding`() {
        assertThat(parseSmsDate("26/08/29 12:10"))
            .isEqualTo(expected(2029, 8, 26, 12, 10))
    }

    // --- conservative margins: no flips on delay or noise ---

    @Test
    fun `delayed receive within days keeps day-first reading`() {
        val received = at("2026-08-29T21:00:00Z")
        assertThat(parseSmsDate("25/08/26 12:10", received))
            .isEqualTo(expected(2026, 8, 25, 12, 10))
    }

    @Test
    fun `same-year token with year-first reading outside tolerance keeps day-first`() {
        val received = at("2026-01-29T12:00:00Z")
        assertThat(parseSmsDate("25/1/26 20:26", received))
            .isEqualTo(expected(2026, 1, 25, 20, 26))
    }

    @Test
    fun `historical receive timestamp is independent of the current year`() {
        val received = at("2019-03-01T09:00:00Z")
        assertThat(parseSmsDate("26/08/29 12:10", received))
            .isEqualTo(expected(2029, 8, 26, 12, 10))
    }

    // --- explicit shapes are never rewritten ---

    @Test
    fun `explicit ISO date is unchanged regardless of receive time`() {
        val received = at("2026-08-29T12:05:00Z")
        assertThat(parseSmsDate("On: 2025-07-11 11:35", received))
            .isEqualTo(expected(2025, 7, 11, 11, 35))
    }

    @Test
    fun `explicit four-digit slash date is unchanged regardless of receive time`() {
        val received = at("2022-08-25T03:30:00Z")
        assertThat(parseSmsDate("On: 22/08/2025 03:19:04", received))
            .isEqualTo(expected(2025, 8, 22, 3, 19))
    }

    @Test
    fun `hyphen YY-MM-DD convention is not disambiguated`() {
        val received = at("2027-12-25T20:15:00Z")
        assertThat(parseSmsDate("Date:25-12-27 20:10", received))
            .isEqualTo(expected(2025, 12, 27, 20, 10))
    }

    @Test
    fun `backslash DMY convention is not disambiguated`() {
        val received = at("2026-01-09T21:35:00Z")
        assertThat(parseSmsDate("9\\1\\26 21:33", received))
            .isEqualTo(expected(2026, 1, 9, 21, 33))
    }

    // --- calendar edges ---

    @Test
    fun `leap day keeps day-first reading on a real leap date`() {
        val received = at("2024-02-29T10:05:00Z")
        assertThat(parseSmsDate("29/02/24 10:00", received))
            .isEqualTo(expected(2024, 2, 29, 10, 0))
    }

    @Test
    fun `invalid calendar date in both readings returns null for receive fallback`() {
        val received = at("2026-02-28T10:05:00Z")
        assertThat(parseSmsDate("29/02/26 10:00", received)).isNull()
    }

    @Test
    fun `month-end day-first reading survives`() {
        val received = at("2026-01-31T08:05:00Z")
        assertThat(parseSmsDate("31/01/26 08:00", received))
            .isEqualTo(expected(2026, 1, 31, 8, 0))
    }

    // --- noise resistance and time preservation ---

    @Test
    fun `multiple date-like numbers still find the timestamped token`() {
        val received = at("2026-08-29T12:05:00Z")
        val body = "Card:1234\nAmount:25.00 SAR\nAt: 26/08/29 12:10"
        assertThat(parseSmsDate(body, received))
            .isEqualTo(expected(2026, 8, 29, 12, 10))
    }

    @Test
    fun `slash token without time still returns null as before`() {
        val received = at("2026-08-29T12:05:00Z")
        assertThat(parseSmsDate("26/08/29", received)).isNull()
    }

    // --- F5B: day-first reading impossible, near year-first is receive-grounded ---

    @Test
    fun `out-of-window day-first yields to explicit near year-first bank day`() {
        // 26/02/14: day-first = 2014-02-26 (outside 2015..2099); year-first = 2026-02-14.
        val received = at("2026-02-15T12:05:00Z")
        assertThat(parseSmsDate("Date:26/02/14 12:10", received))
            .isEqualTo(expected(2026, 2, 14, 12, 10))
    }

    @Test
    fun `impossible day-first calendar date yields to receive-grounded year-first`() {
        // 30/02/28: day-first = 2028-02-30 (invalid); year-first = 2030-02-28.
        val received = at("2030-02-28T10:05:00Z")
        assertThat(parseSmsDate("30/02/28 09:00", received))
            .isEqualTo(expected(2030, 2, 28, 9, 0))
    }

    @Test
    fun `invalid year-first with valid day-first keeps legacy day-first decode`() {
        // 14/02/26: day-first = 2026-02-14 (valid); year-first = 2014-02-26 (outside window).
        val received = at("2026-02-15T12:05:00Z")
        assertThat(parseSmsDate("14/02/26 12:10", received))
            .isEqualTo(expected(2026, 2, 14, 12, 10))
    }

    @Test
    fun `valid year-first far from receive with impossible day-first stays null`() {
        // 31/04/26: day-first = 2026-04-31 (invalid); year-first = 2031-04-26 (far from receive).
        val received = at("2026-08-29T12:05:00Z")
        assertThat(parseSmsDate("31/04/26 09:00", received)).isNull()
    }

    @Test
    fun `no receive reference with impossible day-first stays legacy null`() {
        assertThat(parseSmsDate("31/04/26 09:00")).isNull()
    }

    // --- end to end through TemplateBasedSmsParser ---

    private fun stcParser() = TemplateBasedSmsParser(listOf(StcBankIncomingTransferTemplate()))

    private fun stcEvent(id: String, body: String, received: Instant) = RawIngestEvent(
        id = id,
        source = IngestSource.SMS,
        sender = "STC Bank",
        body = body,
        receivedAt = received,
        rawId = "raw-$id",
    )

    @Test
    fun `stc parser grounds year-first token on receive time`() {
        val body = """
            Internal incoming transfer
            Amount:25.00SAR
            From:SYNTH MERCHANT
            Acc:1234
            At:26/08/29 12:10
        """.trimIndent()
        val result = stcParser().parse(stcEvent("id-1", body, at("2026-08-29T12:05:00Z")))
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val success = result as ParseResult.Success
        assertThat(success.occurredAt).isEqualTo(expected(2026, 8, 29, 12, 10))
        assertThat(success.amount.amount).isEqualTo(BigDecimal("25.00"))
        assertThat(success.type).isEqualTo(TxType.INCOME)
        assertThat(success.merchant).isEqualTo("SYNTH MERCHANT")
    }

    @Test
    fun `stc parser falls back to receive time when body has no date`() {
        val body = """
            Internal incoming transfer
            Amount:25.00SAR
            From:SYNTH MERCHANT
            Acc:1234
        """.trimIndent()
        val received = at("2026-08-29T12:05:00Z")
        val result = stcParser().parse(stcEvent("id-2", body, received))
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        assertThat((result as ParseResult.Success).occurredAt).isEqualTo(received)
    }
}
