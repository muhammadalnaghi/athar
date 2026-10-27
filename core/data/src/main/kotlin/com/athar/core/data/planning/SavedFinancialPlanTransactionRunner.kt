package com.athar.core.data.planning

import androidx.room.withTransaction
import com.athar.core.data.db.AtharDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WM028 fast-store: the atomicity seam of the saved-plan write boundary.
 *
 * Production binds this to Room's real suspend transaction
 * ([androidx.room.withTransaction] on [AtharDatabase]), so the precheck read,
 * the CAS write and the caller-visible result share ONE database transaction:
 * concurrent writers are serialized by SQLite itself — never by a
 * process-local mutex. JVM tests substitute a direct or lock-serialized
 * runner to emulate those same semantics deterministically.
 */
internal interface SavedFinancialPlanTransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/** Real encrypted-Room transaction boundary used in production. */
@Singleton
internal class RoomSavedFinancialPlanTransactionRunner @Inject constructor(
    private val db: AtharDatabase,
) : SavedFinancialPlanTransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }
}
