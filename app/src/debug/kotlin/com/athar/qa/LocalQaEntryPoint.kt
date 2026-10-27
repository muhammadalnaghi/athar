package com.athar.qa

import com.athar.core.domain.repo.BackupRepository
import com.athar.core.domain.repo.SmsAuditRepository
import com.athar.core.domain.repo.TransactionRepository
import com.athar.ingestion.SmsIngestionPipeline
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * WM028 private-replay harness access to the REAL production singleton graph.
 *
 * Debug-source-set only (app/src/debug): compiled into every debug variant, never release.
 * A normal non-exported Hilt entry point — no test reimplementation, no fake repositories.
 * Resolved from the app-under-test via
 * `EntryPointAccessors.fromApplication(targetContext, LocalQaEntryPoint::class.java)`.
 *
 * The queued dispatcher is intentionally not exposed: it is fire-and-forget over a Channel
 * and cannot be awaited deterministically. The harness calls the same public suspend
 * [SmsIngestionPipeline.process] the dispatcher's worker calls, so each row's pipeline
 * work is genuinely awaited before the next row starts.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocalQaEntryPoint {
    fun smsIngestionPipeline(): SmsIngestionPipeline
    fun transactionRepository(): TransactionRepository
    fun smsAuditRepository(): SmsAuditRepository
    fun backupRepository(): BackupRepository
    fun financialRecordingRollout(): com.athar.ingestion.FinancialRecordingRollout
}
