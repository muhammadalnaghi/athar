package com.athar.core.domain.smsimport

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.jupiter.api.Test

class SmsImportFilterGateTest {

    private val zone = TimeZone.of("Asia/Riyadh")

    private fun settings(
        senders: Set<String> = setOf("AlRajhiBank"),
        start: LocalDate? = LocalDate(2026, 1, 1),
        end: LocalDate? = LocalDate(2026, 1, 31),
        openEnded: Boolean = false,
        live: Boolean = false,
    ) = SmsImportFilterSettings(
        selectedSenders = senders,
        startDate = start,
        endDate = end,
        openEnded = openEnded,
        liveTrackingEnabled = live,
    )

    private fun at(date: LocalDate, hour: Int = 12): Instant {
        val start = date.atStartOfDayIn(zone).toEpochMilliseconds()
        return Instant.fromEpochMilliseconds(start + hour * 3_600_000L)
    }

    @Test
    fun `one selected sender allows exact match`() {
        val s = settings(senders = setOf("AlRajhiBank"))
        assertThat(SmsImportFilterGate.allows("AlRajhiBank", at(LocalDate(2026, 1, 15)), s, zone)).isTrue()
        assertThat(SmsImportFilterGate.allows("STCBank", at(LocalDate(2026, 1, 15)), s, zone)).isFalse()
    }

    @Test
    fun `multiple selected senders allow each exact id`() {
        val s = settings(senders = setOf("AlRajhiBank", "STC Bank"))
        assertThat(SmsImportFilterGate.allows("AlRajhiBank", at(LocalDate(2026, 1, 15)), s, zone)).isTrue()
        assertThat(SmsImportFilterGate.allows("STC Bank", at(LocalDate(2026, 1, 15)), s, zone)).isTrue()
        assertThat(SmsImportFilterGate.allows("D360", at(LocalDate(2026, 1, 15)), s, zone)).isFalse()
    }

    @Test
    fun `empty sender list imports nothing`() {
        val s = settings(senders = emptySet())
        assertThat(SmsImportFilterGate.allows("AlRajhiBank", at(LocalDate(2026, 1, 15)), s, zone)).isFalse()
    }

    @Test
    fun `unknown sender is rejected`() {
        val s = settings(senders = setOf("AlRajhiBank"))
        assertThat(SmsImportFilterGate.allows("RandomPromo", at(LocalDate(2026, 1, 15)), s, zone)).isFalse()
    }

    @Test
    fun `case and whitespace normalization still exact-id`() {
        val s = settings(senders = setOf("AlRajhiBank"))
        assertThat(SmsImportFilterGate.allows("  alrajhibank  ", at(LocalDate(2026, 1, 15)), s, zone)).isTrue()
        assertThat(SmsImportFilterGate.allows("AlRajhi", at(LocalDate(2026, 1, 15)), s, zone)).isFalse()
        assertThat(SmsImportFilterGate.allows("AlRajhiBank-AD", at(LocalDate(2026, 1, 15)), s, zone)).isFalse()
    }

    @Test
    fun `start and end boundaries are inclusive calendar days`() {
        val s = settings(
            start = LocalDate(2026, 1, 10),
            end = LocalDate(2026, 1, 12),
            openEnded = false,
        )
        assertThat(SmsImportFilterGate.allows("AlRajhiBank", at(LocalDate(2026, 1, 9), 23), s, zone)).isFalse()
        assertThat(SmsImportFilterGate.allows("AlRajhiBank", at(LocalDate(2026, 1, 10), 0), s, zone)).isTrue()
        assertThat(SmsImportFilterGate.allows("AlRajhiBank", at(LocalDate(2026, 1, 12), 23), s, zone)).isTrue()
        assertThat(SmsImportFilterGate.allows("AlRajhiBank", at(LocalDate(2026, 1, 13), 0), s, zone)).isFalse()
    }

    @Test
    fun `open ended ignores end date`() {
        val s = settings(end = LocalDate(2026, 1, 1), openEnded = true)
        assertThat(SmsImportFilterGate.allows("AlRajhiBank", at(LocalDate(2026, 6, 1)), s, zone)).isTrue()
    }

    @Test
    fun `live requires switch and still respects filters`() {
        val off = settings(live = false)
        val on = settings(live = true, end = LocalDate(2026, 1, 1), openEnded = false)
        val now = at(LocalDate(2026, 2, 1))
        assertThat(SmsImportFilterGate.allows("AlRajhiBank", now, off, zone, requireLiveEnabled = true)).isFalse()
        assertThat(SmsImportFilterGate.allows("AlRajhiBank", now, on, zone, requireLiveEnabled = true)).isFalse()
        assertThat(
            SmsImportFilterGate.allows(
                "AlRajhiBank",
                at(LocalDate(2026, 1, 1)),
                on,
                zone,
                requireLiveEnabled = true,
            ),
        ).isTrue()
    }

    @Test
    fun `validate historical range`() {
        assertThat(SmsImportFilterGate.validateHistoricalRange(settings(senders = emptySet())))
            .isEqualTo("no_senders")
        assertThat(SmsImportFilterGate.validateHistoricalRange(settings(start = null)))
            .isEqualTo("missing_start")
        assertThat(
            SmsImportFilterGate.validateHistoricalRange(
                settings(start = LocalDate(2026, 2, 1), end = LocalDate(2026, 1, 1), openEnded = false),
            ),
        ).isEqualTo("start_after_end")
        assertThat(SmsImportFilterGate.validateHistoricalRange(settings())).isNull()
    }

    @Test
    fun `toggle sender preserves exact id and replaces casing variants`() {
        val s = SmsImportFilterSettings()
            .withSenderToggled(" AlRajhiBank ", true)
            .withSenderToggled("alrajhibank", true)
        assertThat(s.selectedSenders).containsExactly("alrajhibank")
        val cleared = s.withSenderToggled("ALRAJHIBANK", false)
        assertThat(cleared.selectedSenders).isEmpty()
    }
}
