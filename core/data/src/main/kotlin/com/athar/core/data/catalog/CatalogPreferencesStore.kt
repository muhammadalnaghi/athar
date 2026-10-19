package com.athar.core.data.catalog

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Nonfinancial opt-in/timing/version metadata for the shared catalog. */
internal data class CatalogPrefsSnapshot(
    val updatesEnabled: Boolean = false,
    val highestVersion: Long? = null,
    val highestDigest: String? = null,
    val lastCheckEpochSecond: Long? = null,
    val lastFailureEpochSecond: Long? = null,
)

/** Preferences seam; tests inject an in-memory fake. */
internal interface CatalogPreferencesStore {
    suspend fun load(): CatalogPrefsSnapshot
    suspend fun setOptIn(enabled: Boolean)
    suspend fun recordAcceptance(version: Long, digest: String)
    suspend fun recordCheckSuccess(epochSecond: Long)
    suspend fun recordFailure(epochSecond: Long)
}

/**
 * Dedicated DataStore file (intentionally separate from user_prefs so no
 * existing UserPreferencesRepository fake changes). Holds ONLY nonfinancial
 * metadata: opt-in flag, durable highest accepted version + payload digest,
 * and check/failure times. No merchant or financial values, ever.
 */
private val Context.catalogPrefs by preferencesDataStore(name = "merchant_catalog_prefs")

@Singleton
internal class DataStoreCatalogPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) : CatalogPreferencesStore {

    private val optInKey = booleanPreferencesKey("merchant_catalog_opt_in")
    private val highestVersionKey = longPreferencesKey("merchant_catalog_highest_version")
    private val highestDigestKey = stringPreferencesKey("merchant_catalog_highest_digest")
    private val lastCheckKey = longPreferencesKey("merchant_catalog_last_check_epoch_seconds")
    private val lastFailureKey = longPreferencesKey("merchant_catalog_last_failure_epoch_seconds")

    override suspend fun load(): CatalogPrefsSnapshot {
        val prefs = context.catalogPrefs.data.first()
        return CatalogPrefsSnapshot(
            updatesEnabled = prefs[optInKey] ?: false,
            highestVersion = prefs[highestVersionKey],
            highestDigest = prefs[highestDigestKey],
            lastCheckEpochSecond = prefs[lastCheckKey],
            lastFailureEpochSecond = prefs[lastFailureKey],
        )
    }

    override suspend fun setOptIn(enabled: Boolean) {
        context.catalogPrefs.edit { it[optInKey] = enabled }
    }

    override suspend fun recordAcceptance(version: Long, digest: String) {
        context.catalogPrefs.edit {
            it[highestVersionKey] = version
            it[highestDigestKey] = digest
        }
    }

    override suspend fun recordCheckSuccess(epochSecond: Long) {
        context.catalogPrefs.edit {
            it[lastCheckKey] = epochSecond
            it.remove(lastFailureKey)
        }
    }

    override suspend fun recordFailure(epochSecond: Long) {
        context.catalogPrefs.edit { it[lastFailureKey] = epochSecond }
    }
}
