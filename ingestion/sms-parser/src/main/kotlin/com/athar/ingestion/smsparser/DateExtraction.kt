package com.athar.ingestion.smsparser

import kotlin.math.abs
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Best-effort date extractor for bank SMS bodies. Handles every shape observed in
 * the user's corpus (`docs/sms-corpus-analysis.md`):
 *   - `Date:27-12-2025 23:04`        (Al Rajhi Online Purchase)
 *   - `Date:31-12-2025 20:43`        (Al Rajhi PoS purchase)
 *   - `Date:25-12-27 20:10`          (Al Rajhi Internal Transfer, YY-MM-DD)
 *   - `9\1\26 21:33`                 (Al Rajhi Reverse, no label)
 *   - `25/1/26 20:26`                (Al Rajhi Loan Instalment)
 *   - `26/1/25 09:04`                (Al Rajhi Credit Transfer Local, DD/MM/YY)
 *   - `26-1-6 19:46`                 (Al Rajhi Bill Payment, YY-MM-DD)
 *   - `On: 2025-07-11 11:35`         (D360 / Barq, ISO)
 *   - `At:22/11/25 01:16`            (STC Bank, DD/MM/YY)
 *   - `On: 22/08/2025 03:19:04`      (D360 Account Funding, DD/MM/YYYY)
 *
 * Falls back to `null` so the caller uses the SMS receivedAt as the date — the
 * pipeline never crashes on an unparseable shape.
 *
 * Two-digit-year heuristic: any year < 100 is interpreted as `2000 + year`.
 *
 * Two-digit slash dates (`a/b/c`) are ambiguous between day-first and year-first
 * order. When [receivedAt] is supplied, the day-first reading is kept unless the
 * year-first reading is a valid date within 2 days of the receive date and the
 * day-first reading is at least 7 days farther from it — or the day-first
 * reading is impossible (invalid calendar date or year outside 2015..2099)
 * while the year-first reading is valid within 2 days of the receive date
 * (see [slashTwoDigit]).
 * All other shapes are never rewritten; when no candidate is valid the caller
 * falls back to the SMS receive time.
 */
fun parseSmsDate(body: String, receivedAt: Instant? = null): Instant? {
    val candidates = listOf(
        // YYYY-MM-DD HH:MM[:SS]  — D360 / Barq
        Regex("""(\d{4})-(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?"""),
        // DD-MM-YYYY HH:MM       — Al Rajhi Online Purchase
        Regex("""(\d{1,2})-(\d{1,2})-(\d{4})\s+(\d{1,2}):(\d{2})"""),
        // YY-MM-DD HH:MM         — Al Rajhi Internal Transfer
        Regex("""(\d{2})-(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})\b"""),
        // DD/MM/YYYY HH:MM[:SS]  — D360 Account Funding
        Regex("""(\d{1,2})/(\d{1,2})/(\d{4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?"""),
        // DD/MM/YY HH:MM         — STC Bank, Al Rajhi Loan
        Regex("""(\d{1,2})/(\d{1,2})/(\d{2})\s+(\d{1,2}):(\d{2})\b"""),
        // a/b/c HH:MM (1-2 digit third) — Al Rajhi PoS YY/M/D with single-digit
        // day ("26/7/3 19:17"). Disambiguated receive-grounded, same as above.
        Regex("""(\d{1,2})/(\d{1,2})/(\d{1,2})\s+(\d{1,2}):(\d{2})\b"""),
        // DD/MM/YYYY (date only, no time) — STC Bank "Date 19/02/2026"
        Regex("""(\d{1,2})/(\d{1,2})/(\d{4})\b"""),
        // YY-M-D HH:MM           — Al Rajhi Bill Payment (single-digit month/day)
        Regex("""(\d{2})-(\d{1,2})-(\d{1,2})\b"""),
        // D\M\YY HH:MM           — Al Rajhi Reverse Transaction
        Regex("""(\d{1,2})\\(\d{1,2})\\(\d{2})\s+(\d{1,2}):(\d{2})"""),
    )

    for ((idx, regex) in candidates.withIndex()) {
        val m = regex.find(body) ?: continue
        val g = m.groupValues
        val (year, month, day, hour, minute) = when (idx) {
            0 -> Five(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt())                  // YYYY-MM-DD HH:MM
            1 -> Five(g[3].toInt(), g[2].toInt(), g[1].toInt(), g[4].toInt(), g[5].toInt())                  // DD-MM-YYYY HH:MM
            2 -> Five(2000 + g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt())           // YY-MM-DD HH:MM
            3 -> Five(g[3].toInt(), g[2].toInt(), g[1].toInt(), g[4].toInt(), g[5].toInt())                  // DD/MM/YYYY HH:MM
            4 -> slashTwoDigit(g, receivedAt)                                                               // DD/MM/YY HH:MM
            5 -> slashTwoDigit(g, receivedAt)                                                               // a/b/c HH:MM (1-2 digit third)
            6 -> Five(g[3].toInt(), g[2].toInt(), g[1].toInt(), 12, 0)                                      // DD/MM/YYYY date-only
            7 -> Five(2000 + g[1].toInt(), g[2].toInt(), g[3].toInt(), 12, 0)                                // YY-M-D (no time)
            8 -> Five(2000 + g[3].toInt(), g[2].toInt(), g[1].toInt(), g[4].toInt(), g[5].toInt())           // D\M\YY HH:MM
            else -> continue
        }

        val date = runCatching { LocalDate(year, month, day) }.getOrNull() ?: continue
        // Sanity: ignore dates from before 2015 or > 1 year in the future
        if (year < 2015 || year > 2099) continue
        val time = runCatching { LocalTime(hour, minute) }.getOrNull() ?: LocalTime(12, 0)
        return LocalDateTime(date, time).toInstant(TimeZone.currentSystemDefault())
    }
    return null
}

private const val MIN_YEAR = 2015
private const val MAX_YEAR = 2099

/** Year-first reading must be within this many days of the receive date. */
private const val RECEIVE_TOLERANCE_DAYS = 2

/** …and beat the day-first reading by at least this many days before flipping. */
private const val FLIP_MARGIN_DAYS = 7

/**
 * Two-digit slash token `a/b/c HH:MM`. Saudi bank convention is day-first, but
 * some messages use year-first (`26/08/29` received 2026-08-29). Ground the
 * choice on the SMS receive time: keep the day-first reading unless the
 * year-first reading is a valid date within [RECEIVE_TOLERANCE_DAYS] of the
 * receive date AND the day-first reading is at least [FLIP_MARGIN_DAYS] farther
 * from it. Without a receive time the legacy day-first decoding stands. When
 * the day-first reading is impossible (invalid calendar date or year outside
 * [MIN_YEAR]..[MAX_YEAR]) a valid year-first reading within
 * [RECEIVE_TOLERANCE_DAYS] of the receive date is accepted with the token's own
 * time; any other invalid reading keeps the legacy behavior (`null` → receive
 * fallback). The 7-day margin never applies to this rescue: there is no valid
 * day-first distance to beat. The receive date uses the same zone policy
 * as the parse conversion (`currentSystemDefault`); no device-locale inference.
 */
private fun slashTwoDigit(g: List<String>, receivedAt: Instant?): Five {
    val dmy = Five(2000 + g[3].toInt(), g[2].toInt(), g[1].toInt(), g[4].toInt(), g[5].toInt())
    if (receivedAt == null) return dmy
    val day = g[3].toInt()
    val month = g[2].toInt()
    val ymdYear = 2000 + g[1].toInt()
    val dmyDate = runCatching { LocalDate(dmy.a, dmy.b, dmy.c) }.getOrNull()
        ?.takeIf { it.year in MIN_YEAR..MAX_YEAR }
    val ymdDate = runCatching { LocalDate(ymdYear, month, day) }.getOrNull()
        ?.takeIf { it.year in MIN_YEAR..MAX_YEAR }
    if (dmyDate == null && ymdDate == null) return dmy
    val receivedDate = receivedAt.toLocalDateTime(TimeZone.currentSystemDefault()).date
    if (dmyDate == null) {
        // Day-first is impossible: accept the year-first reading only when it is
        // receive-grounded within tolerance (no 7-day margin — nothing to beat).
        val ymdDist = abs(ymdDate!!.toEpochDays() - receivedDate.toEpochDays())
        return if (ymdDist <= RECEIVE_TOLERANCE_DAYS) {
            Five(ymdYear, month, day, g[4].toInt(), g[5].toInt())
        } else {
            dmy
        }
    }
    if (ymdDate == null) return dmy
    val dmyDist = abs(dmyDate.toEpochDays() - receivedDate.toEpochDays())
    val ymdDist = abs(ymdDate.toEpochDays() - receivedDate.toEpochDays())
    return if (ymdDist <= RECEIVE_TOLERANCE_DAYS && dmyDist >= ymdDist + FLIP_MARGIN_DAYS) {
        Five(ymdYear, month, day, g[4].toInt(), g[5].toInt())
    } else {
        dmy
    }
}

private data class Five(val a: Int, val b: Int, val c: Int, val d: Int, val e: Int)
