package com.athar.ingestion

import android.content.Context
import android.content.SharedPreferences
import com.athar.core.domain.repo.FinancialRecordingPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Local rollout only. Never backfills, changes provider settings or grants
 * category authority. WM033: implements the domain seam so Settings observes
 * the same persisted `certified_purchase_rollout/enabled` flag the canonical
 * purchase path gates on — no second store, default OFF unchanged;
 * [isEnabled]/[setEnabled] semantics are untouched.
 */
@Singleton
class FinancialRecordingRollout @Inject constructor(@ApplicationContext context: Context) :
    FinancialRecordingPreferences {
    private val prefs = context.getSharedPreferences("certified_purchase_rollout", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    private val writes = Mutex()

    override suspend fun setEnabled(enabled: Boolean) = writes.withLock {
        withContext(Dispatchers.IO) {
            check(prefs.edit().putBoolean(KEY_ENABLED, enabled).commit())
        }
    }

    /**
     * Emits the persisted value on collection, then follows every change to it,
     * so the Settings control stays truthful even if the flag changes elsewhere.
     */
    override fun enabled(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == KEY_ENABLED) trySend(sp.getBoolean(KEY_ENABLED, false))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getBoolean(KEY_ENABLED, false))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}
