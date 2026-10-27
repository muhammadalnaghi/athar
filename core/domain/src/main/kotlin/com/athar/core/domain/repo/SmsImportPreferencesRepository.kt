package com.athar.core.domain.repo

import com.athar.core.domain.smsimport.SmsImportFilterSettings
import kotlinx.coroutines.flow.Flow

interface SmsImportPreferencesRepository {
    fun settings(): Flow<SmsImportFilterSettings>
    suspend fun update(settings: SmsImportFilterSettings)
}
