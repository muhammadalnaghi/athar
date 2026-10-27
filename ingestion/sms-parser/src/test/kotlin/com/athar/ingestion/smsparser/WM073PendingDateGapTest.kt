package com.athar.ingestion.smsparser

import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.ZoneId

class WM073PendingDateGapTest {

    // Actual corpus shapes from the user's pending explicit-posted families.
    private val alRajhiPosBody = """
        PoS Purchase
        By:3678;mada(Samsung Pay)
        Amount:SR 2
        At:Muhammad Ali
        26/7/3 19:17
    """.trimIndent()

    private val stcBody = """
        Online Purchase Transaction Amount 93.10
        From: Nahdi Care Clinic
         Card: *******3279
        Date 19/02/2026
    """.trimIndent()

    @Test
    fun alrajhiPosYearFirstStampIsReceiveGroundedAndExtracted() {
        // Received same day (2026-07-03T06:17:49Z) — year-first rescue must accept.
        val parsed = parseSmsDate(alRajhiPosBody, Instant.parse("2026-07-03T06:17:49Z"))
        // 2026-07-03 19:17 in the local zone at parse time.
        val expected = java.time.LocalDateTime.of(2026, 7, 3, 19, 17)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, parsed?.toEpochMilliseconds(), "PoS YY/M/D stamp must parse as 2026-07-03 19:17 local")
    }

    @Test
    fun stcBareDateLineWithoutTimeIsExtracted() {
        val parsed = parseSmsDate(stcBody, Instant.parse("2026-02-18T22:04:09Z"))
        val expected = java.time.LocalDateTime.of(2026, 2, 19, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, parsed?.toEpochMilliseconds(), "bare 'Date 19/02/2026' must parse as 2026-02-19")
    }

    @Test
    fun `no date invention when nothing matches`() {
        assertNull(parseSmsDate("no dates here at all", Instant.parse("2026-07-03T06:17:49Z")))
    }
}
