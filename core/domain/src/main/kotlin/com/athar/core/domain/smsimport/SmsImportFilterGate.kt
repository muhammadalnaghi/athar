package com.athar.core.domain.smsimport

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/**
 * Pure predicate applied before parse / audit / persistence for both historical and live SMS.
 */
object SmsImportFilterGate {

    fun allows(
        sender: String,
        receivedAt: Instant,
        settings: SmsImportFilterSettings,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        requireLiveEnabled: Boolean = false,
    ): Boolean {
        if (requireLiveEnabled && !settings.liveTrackingEnabled) return false
        if (!SmsSenderIds.matches(sender, settings.selectedSenders)) return false
        return inDateRange(receivedAt, settings, zone)
    }

    fun inDateRange(
        receivedAt: Instant,
        settings: SmsImportFilterSettings,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): Boolean {
        val start = settings.startDate
        if (start != null) {
            val startInclusive = start.atStartOfDayIn(zone)
            if (receivedAt < startInclusive) return false
        }
        if (!settings.openEnded) {
            val end = settings.endDate ?: return false
            val endExclusive = end.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
            if (receivedAt >= endExclusive) return false
        }
        return true
    }

    /**
     * Provider query helpers (epoch millis). End is exclusive next-day midnight so the
     * SQL `date < ?` bound stays inclusive of the chosen calendar end day.
     */
    fun startEpochMillisInclusive(start: LocalDate?, zone: TimeZone): Long? =
        start?.atStartOfDayIn(zone)?.toEpochMilliseconds()

    fun endEpochMillisExclusive(settings: SmsImportFilterSettings, zone: TimeZone): Long? {
        if (settings.openEnded) return null
        val end = settings.endDate ?: return null
        return end.plus(DatePeriod(days = 1)).atStartOfDayIn(zone).toEpochMilliseconds()
    }

    fun validateHistoricalRange(settings: SmsImportFilterSettings): String? {
        if (settings.selectedSenders.isEmpty()) return "no_senders"
        val start = settings.startDate ?: return "missing_start"
        if (!settings.openEnded) {
            val end = settings.endDate ?: return "missing_end"
            if (start > end) return "start_after_end"
        }
        return null
    }
}
