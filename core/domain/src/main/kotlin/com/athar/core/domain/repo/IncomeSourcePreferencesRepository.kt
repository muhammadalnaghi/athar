package com.athar.core.domain.repo

import com.athar.core.domain.smsimport.SmsSenderIds
import kotlinx.coroutines.flow.Flow

/**
 * Eligible income SMS senders — separate from expense-tracking / SMS-import senders.
 *
 * Default is empty: no automatic income until the user configures sources.
 */
data class IncomeSourceSettings(
    val eligibleSenders: Set<String> = emptySet(),
) {
    fun withSenderToggled(rawSender: String, selected: Boolean): IncomeSourceSettings {
        val exact = SmsSenderIds.preserve(rawSender) ?: return this
        val next = eligibleSenders.toMutableSet()
        next.removeAll { SmsSenderIds.normalize(it) == SmsSenderIds.normalize(exact) }
        if (selected) next.add(exact)
        return copy(eligibleSenders = next)
    }
}

interface IncomeSourcePreferencesRepository {
    fun settings(): Flow<IncomeSourceSettings>
    suspend fun update(settings: IncomeSourceSettings)
}
