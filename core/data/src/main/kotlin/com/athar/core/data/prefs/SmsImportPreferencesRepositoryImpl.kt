package com.athar.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.athar.core.domain.repo.SmsImportPreferencesRepository
import com.athar.core.domain.smsimport.SmsImportFilterSettings
import com.athar.core.domain.smsimport.SmsSenderIds
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.smsImportPrefs by preferencesDataStore(name = "sms_import_prefs")

@Singleton
internal class SmsImportPreferencesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsImportPreferencesRepository {

    private val sendersKey = stringPreferencesKey("selected_senders_csv")
    private val startKey = stringPreferencesKey("start_date_iso")
    private val endKey = stringPreferencesKey("end_date_iso")
    private val openEndedKey = booleanPreferencesKey("open_ended")
    private val liveKey = booleanPreferencesKey("live_tracking_enabled")

    override fun settings(): Flow<SmsImportFilterSettings> =
        context.smsImportPrefs.data.map { p ->
            SmsImportFilterSettings(
                selectedSenders = decodeSenders(p[sendersKey]),
                startDate = p[startKey]?.let(::parseDate),
                endDate = p[endKey]?.let(::parseDate),
                openEnded = p[openEndedKey] ?: true,
                liveTrackingEnabled = p[liveKey] ?: false,
            )
        }

    override suspend fun update(settings: SmsImportFilterSettings) {
        context.smsImportPrefs.edit { p ->
            p[sendersKey] = encodeSenders(settings.selectedSenders)
            val start = settings.startDate
            if (start == null) p.remove(startKey) else p[startKey] = start.toString()
            val end = settings.endDate
            if (end == null) p.remove(endKey) else p[endKey] = end.toString()
            p[openEndedKey] = settings.openEnded
            p[liveKey] = settings.liveTrackingEnabled
        }
    }

    private fun encodeSenders(senders: Set<String>): String =
        senders.mapNotNull(SmsSenderIds::preserve).sorted().joinToString("\u001f")

    private fun decodeSenders(raw: String?): Set<String> =
        raw.orEmpty()
            .split('\u001f', ',')
            .mapNotNull(SmsSenderIds::preserve)
            .toSet()

    private fun parseDate(raw: String): LocalDate? =
        runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
}
