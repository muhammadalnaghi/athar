package com.athar.core.domain.repo

import kotlinx.coroutines.flow.Flow

/**
 * WM033: user-facing control for prospective automatic recording of clearly
 * evidenced completed purchases. Feature/settings reads this seam; the app
 * module binds it to the rollout flag the canonical purchase path already
 * gates on, so there is exactly one source of truth. Default is OFF; the
 * writer persists the user's explicit choice (OFF always wins), and toggling
 * never backfills, mass-confirms, or re-categorizes existing data.
 */
interface FinancialRecordingPreferences {

    /** Observes persisted truth; emits the current value on collection. */
    fun enabled(): Flow<Boolean>

    /** Main-safe write-through of the explicit prospective-recording choice. */
    suspend fun setEnabled(enabled: Boolean)
}
