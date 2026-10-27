package com.athar.core.domain.smsimport

import kotlinx.datetime.LocalDate

/**
 * User-controlled SMS import constraints.
 *
 * Defaults are intentionally closed: no senders selected and live tracking off,
 * so an upgrade never silently imports every bank SMS.
 */
data class SmsImportFilterSettings(
    /** Exact sender IDs as chosen by the user (display / persistence form). */
    val selectedSenders: Set<String> = emptySet(),
    /** Inclusive calendar start day in the device local zone. Null = no lower bound. */
    val startDate: LocalDate? = null,
    /**
     * Inclusive calendar end day. Ignored when [openEnded] is true.
     * Null with [openEnded] false is treated as invalid for historical import validation.
     */
    val endDate: LocalDate? = null,
    /** When true, there is no upper date bound (ongoing imports / live). */
    val openEnded: Boolean = true,
    /** When false, the live BroadcastReceiver path must not ingest. */
    val liveTrackingEnabled: Boolean = false,
) {
    fun withSenderToggled(rawSender: String, selected: Boolean): SmsImportFilterSettings {
        val exact = SmsSenderIds.preserve(rawSender) ?: return this
        val next = selectedSenders.toMutableSet()
        // Drop any prior casing variant of the same logical id.
        next.removeAll { SmsSenderIds.normalize(it) == SmsSenderIds.normalize(exact) }
        if (selected) next.add(exact)
        return copy(selectedSenders = next)
    }
}
