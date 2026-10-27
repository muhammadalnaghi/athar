package com.athar.core.data.backup

import androidx.room.withTransaction
import com.athar.core.common.crypto.AtharCrypto
import com.athar.core.data.backup.input.BackupEnvelopeDecodeException
import com.athar.core.data.backup.input.BackupInputLimits
import com.athar.core.data.backup.input.BackupPayloadBoundException
import com.athar.core.data.backup.input.BoundedBackupPayload
import com.athar.core.data.backup.input.StrictBackupJsonGuard
import com.athar.core.data.db.AtharDatabase
import com.athar.core.data.ledger.backup.CanonicalBackupRevision
import com.athar.core.data.ledger.backup.CanonicalBackupRows
import com.athar.core.data.ledger.backup.toSection
import com.athar.core.domain.repo.BackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup write + restore service.
 *
 * Format: gzip(JSON([BackupSnapshot])) → AES-256-GCM → file.
 * The user-facing extension is `.athar`. See [AtharCrypto] for on-disk layout.
 *
 * Restore strategy: REPLACE — clears existing tables and writes the snapshot atomically
 * via Room transaction. Append/merge land later if user feedback demands them.
 */
@Singleton
class BackupService internal constructor(
    private val db: AtharDatabase,
    private val clock: Clock,
    private val inputLimits: BackupInputLimits,
) : BackupRepository {
    @Inject internal constructor(db: AtharDatabase, clock: Clock) :
        this(db, clock, BackupInputLimits.Production)

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun export(output: OutputStream, passphrase: CharArray): Unit = withContext(Dispatchers.IO) {
        val snapshot = capture()
        val plainJson = json.encodeToString(BackupSnapshot.serializer(), snapshot)
        val plainBytes = plainJson.toByteArray(Charsets.UTF_8)
        if (plainBytes.size > inputLimits.maxExpandedBytes) {
            throw BackupPayloadBoundException("expanded backup exceeds the budget of ${inputLimits.maxExpandedBytes} bytes")
        }
        guard(plainJson)
        currentCoroutineContext().ensureActive()
        val gzipped = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write(plainBytes) }
            baos.toByteArray()
        }
        val encrypted = AtharCrypto.encrypt(gzipped, passphrase)
        checkCiphertextSize(encrypted)
        currentCoroutineContext().ensureActive()
        output.use { it.write(encrypted) }
        val canonical = snapshot.canonicalLedger
        Timber.i(
            "Backup exported: v%d · %d tx · %d cat · %d rules · canonical events %d · variants %d · conflicts %d · ciphertext %d B",
            snapshot.version, snapshot.transactions.size, snapshot.categories.size,
            snapshot.categoryRules.size, canonical?.events?.size ?: 0,
            canonical?.snapshots?.size ?: 0, canonical?.conflicts?.size ?: 0, encrypted.size,
        )
    }

    override suspend fun import(bytes: ByteArray, passphrase: CharArray): Unit = withContext(Dispatchers.IO) {
        importAuthenticated(bytes, passphrase)
    }

    override suspend fun import(input: InputStream, passphrase: CharArray): Unit = withContext(Dispatchers.IO) {
        val bytes = BoundedBackupPayload.readCiphertext(input, inputLimits.maxCiphertextBytes)
        importAuthenticated(bytes, passphrase)
    }

    private suspend fun importAuthenticated(bytes: ByteArray, passphrase: CharArray) {
        checkCiphertextSize(bytes)
        currentCoroutineContext().ensureActive()
        val decrypted = AtharCrypto.decrypt(bytes, passphrase)
        currentCoroutineContext().ensureActive()
        val plainJson = BoundedBackupPayload.decodeAuthenticatedGzip(decrypted, inputLimits.maxExpandedBytes)
        guard(plainJson)
        currentCoroutineContext().ensureActive()
        val snapshot = try {
            json.decodeFromString(BackupSnapshot.serializer(), plainJson)
        } catch (_: IllegalArgumentException) {
            throw BackupEnvelopeDecodeException()
        }
        restore(snapshot)
        Timber.i(
            "Backup imported: v%d · %d tx · %d cat · canonical replaced=%b",
            snapshot.version, snapshot.transactions.size, snapshot.categories.size,
            snapshot.version == BackupSnapshot.CURRENT_VERSION,
        )
    }

    private fun checkCiphertextSize(bytes: ByteArray) {
        if (bytes.size > inputLimits.maxCiphertextBytes) {
            throw BackupPayloadBoundException("ciphertext exceeds the budget of ${inputLimits.maxCiphertextBytes} bytes")
        }
    }

    private fun guard(plainJson: String) {
        StrictBackupJsonGuard.validate(
            plainJson, inputLimits.maxDepth, inputLimits.maxTokens, inputLimits.maxStringChars,
        )
    }

    /**
     * Legacy + canonical capture inside one transaction so the envelope is a
     * consistent point-in-time slice. The canonical section is always emitted
     * (all-empty when the ledger is empty) — canonical data is never omitted.
     */
    private suspend fun capture(): BackupSnapshot = db.withTransaction {
        BackupSnapshot(
            exportedAt = clock.now(),
            accounts = db.accountDao().all().map { it.toBackup() },
            categories = db.categoryDao().all().map { it.toBackup() },
            transactions = db.transactionDao().all().map { it.toBackup() },
            categoryRules = db.categoryRuleDao().all().map { it.toBackup() },
            wishlist = db.wishlistDao().all().map { it.toBackup() },
            investmentPools = db.investmentDao().allPools().map { it.toBackup() },
            investmentContributions = db.investmentDao().allContributions().map { it.toBackup() },
            smsAudit = db.smsMessageDao().all().map { it.toBackup() },
            receiptAttachments = db.transactionReceiptDao().all().map { it.toBackup() },
            auxiliaryTables = BackupAuxiliarySection(
                activityLog = db.activityLogDao().all().map { it.toBackup() },
                userTemplates = db.userTemplateDao().all().map { it.toBackup() },
                recurringRules = db.recurringRuleDao().all().map { it.toBackup() },
                savedPlans = db.savedFinancialPlanDao().all().map { it.toBackup() },
            ),
            canonicalLedger = CanonicalBackupRows(
                events = db.canonicalLedgerDao().captureEvents(),
                observations = db.canonicalLedgerDao().captureObservations(),
                associations = db.canonicalLedgerDao().captureAssociations(),
                components = db.canonicalLedgerDao().captureComponents(),
                legs = db.canonicalLedgerDao().captureLegs(),
                revisions = db.canonicalLedgerDao().captureRevisions(),
                snapshots = db.canonicalLedgerDao().captureSnapshotVariants(),
                conflicts = db.canonicalLedgerDao().captureConflicts(),
            ).toSection(),
        )
    }

    /**
     * Internal test-only observation seam. Invoked with the fully validated
     * plan from inside the restore write transaction, after the live-state
     * eligibility and epoch decisions and before any clear. It is
     * non-authorizing: it cannot alter eligibility, revision or replacement
     * decisions, and production code never sets it. The public @Inject
     * constructor is unchanged.
     */
    internal var restoreTransactionProbe: (suspend (BackupRestorePlan) -> Unit)? = null

    private suspend fun restore(snapshot: BackupSnapshot) {
        // Pure incoming checks may precede the transaction: a malformed payload
        // is rejected without opening a write transaction at all.
        BackupRestoreValidator.validateIncoming(snapshot)
        db.withTransaction {
            // Every live read, eligibility check, epoch advance and the actual
            // replacement happen inside this single write transaction, so a
            // concurrent admission can neither invalidate the eligibility
            // decision nor lose its epoch increment to the restored rows.
            val localRevisions = db.canonicalLedgerDao().captureRevisions()
                .map { CanonicalBackupRevision(id = it.id, revision = it.revision) }
            val liveCanonicalRows = db.canonicalLedgerDao().countCanonicalLedgerDataRows()
            val liveAuxiliaryDataPresent = db.activityLogDao().count() > 0L ||
                db.userTemplateDao().count() > 0L || db.recurringRuleDao().count() > 0L ||
                db.savedFinancialPlanDao().count() > 0L
            val plan = BackupRestoreValidator.validate(
                snapshot, localRevisions, liveCanonicalRows, liveAuxiliaryDataPresent,
            )
            restoreTransactionProbe?.invoke(plan)
            // Invalidate already-started recording even if a later restore write rolls back.
            com.athar.core.data.ledger.api.FinancialRestoreEpoch.invalidate()
            if (plan.canonicalReplaced) {
                clearCanonicalChildFirst()
                clearAuxiliaryBeforeLegacy()
            }
            clearLegacyChildFirst()
            insertLegacy(snapshot)
            if (plan.canonicalReplaced) {
                insertCanonicalParentFirst(plan)
                insertAuxiliary(requireNotNull(snapshot.auxiliaryTables))
            }
        }
    }

    /**
     * v4 whole replace, canonical side. Clears run child-first and BEFORE the
     * legacy clears because canonical legs reference the legacy account table;
     * any residual FK violation rolls the whole transaction back.
     */
    private suspend fun clearCanonicalChildFirst() {
        with(db.canonicalLedgerDao()) {
            clearConflicts()
            clearSnapshotVariants()
            clearAssociations()
            clearLegs()
            clearComponents()
            clearEvents()
            clearObservations()
            clearRevisions()
        }
    }

    private suspend fun clearAuxiliaryBeforeLegacy() {
        db.savedFinancialPlanDao().clear()
        db.recurringRuleDao().clear()
        db.userTemplateDao().clear()
        db.activityLogDao().clear()
    }

    private suspend fun insertAuxiliary(section: BackupAuxiliarySection) {
        section.activityLog.forEach { db.activityLogDao().insert(it.toEntity()) }
        section.userTemplates.forEach { db.userTemplateDao().upsert(it.toEntity()) }
        section.recurringRules.forEach { db.recurringRuleDao().upsert(it.toEntity()) }
        db.savedFinancialPlanDao().insertAll(section.savedPlans.map { it.toEntity() })
    }

    /** Replace strategy: clear children before parents to satisfy FKs. */
    private suspend fun clearLegacyChildFirst() {
        db.transactionReceiptDao().clear()
        db.transactionDao().clear()
        db.categoryRuleDao().clear()
        db.wishlistDao().clear()
        db.investmentDao().clearContributions()
        db.investmentDao().clearPools()
        db.smsMessageDao().clear()
        db.categoryDao().clear()
        db.accountDao().clear()
    }

    private suspend fun insertLegacy(snapshot: BackupSnapshot) {
        snapshot.accounts.forEach { db.accountDao().upsert(it.toEntity()) }
        db.categoryDao().upsertAll(snapshot.categories.map { it.toEntity() })
        snapshot.categoryRules.forEach { db.categoryRuleDao().upsert(it.toEntity()) }
        snapshot.transactions.forEach { db.transactionDao().upsert(it.toEntity()) }
        snapshot.wishlist.forEach { db.wishlistDao().upsert(it.toEntity()) }
        snapshot.investmentPools.forEach { db.investmentDao().upsertPool(it.toEntity()) }
        snapshot.investmentContributions.forEach { db.investmentDao().upsertContribution(it.toEntity()) }
        snapshot.smsAudit.forEach { db.smsMessageDao().upsert(it.toEntity()) }
        snapshot.receiptAttachments.forEach { db.transactionReceiptDao().upsert(it.toEntity()) }
    }

    /**
     * Parent-first canonical inserts; the ABORT strategies turn any residual
     * duplicate into an exception that rolls the entire replace back.
     */
    private suspend fun insertCanonicalParentFirst(plan: BackupRestorePlan) {
        val rows = plan.canonicalRows
        rows.events.forEach { db.canonicalLedgerDao().insertEvent(it) }
        rows.observations.forEach { db.canonicalLedgerDao().insertObservation(it) }
        rows.associations.forEach { db.canonicalLedgerDao().insertEventSource(it) }
        rows.components.forEach { db.canonicalLedgerDao().insertComponent(it) }
        rows.legs.forEach { db.canonicalLedgerDao().insertLeg(it) }
        plan.revisionRows.forEach { db.canonicalLedgerDao().insertRevisionExact(it) }
        rows.snapshots.forEach { db.canonicalLedgerDao().insertSnapshotVariant(it) }
        rows.conflicts.forEach { db.canonicalLedgerDao().insertConflict(it) }
    }
}
