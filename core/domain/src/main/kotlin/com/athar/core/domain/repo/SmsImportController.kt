package com.athar.core.domain.repo

import com.athar.core.domain.smsimport.DiscoveredSmsSender
import com.athar.core.domain.smsimport.SmsImportPreview
import com.athar.core.domain.smsimport.SmsImportResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Historical SMS discover / preview / import. Implementation lives in `app`
 * (ContentResolver). Feature modules depend only on this interface.
 */
interface SmsImportController {
    val progress: StateFlow<BackfillProgress>

    suspend fun discoverSenders(): List<DiscoveredSmsSender>

    /** Dry-run against the inbox using persisted filter settings. No persistence. */
    suspend fun preview(): Result<SmsImportPreview>

    /** Enqueue matching inbox rows into the existing ingestion pipeline. */
    suspend fun importSelected(): SmsImportResult
}
