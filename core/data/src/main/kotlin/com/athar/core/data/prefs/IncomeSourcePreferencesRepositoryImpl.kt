package com.athar.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.athar.core.domain.repo.IncomeSourcePreferencesRepository
import com.athar.core.domain.repo.IncomeSourceSettings
import com.athar.core.domain.smsimport.SmsSenderIds
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.incomeSourcePrefs by preferencesDataStore(name = "income_source_prefs")

@Singleton
internal class IncomeSourcePreferencesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : IncomeSourcePreferencesRepository {

    private val sendersKey = stringPreferencesKey("eligible_senders")

    override fun settings(): Flow<IncomeSourceSettings> =
        context.incomeSourcePrefs.data.map { p ->
            IncomeSourceSettings(eligibleSenders = decode(p[sendersKey]))
        }

    override suspend fun update(settings: IncomeSourceSettings) {
        context.incomeSourcePrefs.edit { p ->
            p[sendersKey] = encode(settings.eligibleSenders)
        }
    }

    private fun encode(senders: Set<String>): String =
        senders.mapNotNull(SmsSenderIds::preserve).sorted().joinToString("\u001f")

    private fun decode(raw: String?): Set<String> =
        raw.orEmpty()
            .split('\u001f', ',')
            .mapNotNull(SmsSenderIds::preserve)
            .toSet()
}
