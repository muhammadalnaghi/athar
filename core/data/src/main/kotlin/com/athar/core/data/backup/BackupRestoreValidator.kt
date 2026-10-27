package com.athar.core.data.backup

import com.athar.core.data.ledger.backup.CanonicalBackupRevision
import com.athar.core.data.ledger.backup.CanonicalBackupRows
import com.athar.core.data.ledger.backup.CanonicalBackupSection
import com.athar.core.data.ledger.backup.toRows
import com.athar.core.data.ledger.db.CanonicalLedgerRevisionEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Typed failure for every backup validation rule. Thrown before any table is
 * cleared or written: pure incoming rejections precede the restore
 * transaction, and live-state rejections (legacy mixing, epoch advance) abort
 * the transaction before its first clear, so a rejected restore leaves the
 * database untouched. Messages name row keys only — payload contents are
 * never echoed.
 */
internal class BackupValidationException internal constructor(
    val code: BackupValidationCode,
    message: String,
) : RuntimeException(message)

internal enum class BackupValidationCode {
    MISSING_VERSION,
    UNSUPPORTED_VERSION,
    V4_REQUIRES_CANONICAL_LEDGER,
    LEGACY_CANNOT_CARRY_CANONICAL_LEDGER,
    CANONICAL_LEDGER_PRESENT,
    V4_REQUIRES_AUXILIARY_TABLES,
    LEGACY_CANNOT_CARRY_AUXILIARY_TABLES,
    AUXILIARY_DATA_PRESENT,
    LEGACY_REFERENCE_MISSING,
    CANONICAL_REFERENCE_MISSING,
    DUPLICATE_BACKUP_KEY,
    INVALID_REVISION,
    CANONICAL_SNAPSHOT_BINDING_BROKEN,
    CANONICAL_DENORMALIZATION_MISMATCH,
    CANONICAL_AUTHORITY_UNSUPPORTED,
}

/** Fully validated restore intent; [canonicalReplaced] is false only for v1–v3 payloads. */
internal data class BackupRestorePlan(
    val snapshot: BackupSnapshot,
    val canonicalReplaced: Boolean,
    val canonicalRows: CanonicalBackupRows,
    val revisionRows: List<CanonicalLedgerRevisionEntity>,
)

/**
 * Pure backup-envelope validation: explicit version gate, legacy and canonical
 * referential/resource checks, and revision monotonicity. Everything here runs
 * before any write. Amounts, currencies and embedded JSON stay opaque strings
 * and are never parsed or reinterpreted (original bytes, exact money spellings
 * and literal currencies must survive capture→restore unchanged).
 */
internal object BackupRestoreValidator {

    const val MIN_SUPPORTED_VERSION = 1
    const val MAX_SUPPORTED_VERSION = BackupSnapshot.CURRENT_VERSION

    internal const val REVISION_SINGLETON_ID = "singleton"

    private val versionProbeJson = Json { ignoreUnknownKeys = true }
    private val integerVersionSyntax = Regex("-?(0|[1-9][0-9]*)")

    private data class SnapshotKey(
        val sourceNamespace: String,
        val sourceId: String,
        val sourcePayloadDigest: String,
        val snapshotDigest: String,
    )

    private fun snapshotKey(
        sourceNamespace: String,
        sourceId: String,
        sourcePayloadDigest: String,
        snapshotDigest: String,
    ) = SnapshotKey(sourceNamespace, sourceId, sourcePayloadDigest, snapshotDigest)

    /**
     * Reads the literal top-level `version` straight from the JSON so a
     * missing, nonnumeric or out-of-range version fails closed instead of
     * silently decoding as the current default.
     */
    fun resolveVersion(plainJson: String): Int {
        val root = runCatching { versionProbeJson.parseToJsonElement(plainJson) }
            .getOrElse {
                throw BackupValidationException(
                    BackupValidationCode.MISSING_VERSION,
                    "Backup root is not a JSON object",
                )
            }
        val obj = root as? JsonObject
            ?: throw BackupValidationException(
                BackupValidationCode.MISSING_VERSION,
                "Backup root is not a JSON object",
            )
        val encoded = obj["version"]
            ?: throw BackupValidationException(
                BackupValidationCode.MISSING_VERSION,
                "Backup version field is missing",
            )
        val version = (encoded as? JsonPrimitive)
            ?.takeIf { !it.isString && integerVersionSyntax.matches(it.content) }
            ?.longOrNull
            ?: throw BackupValidationException(
                BackupValidationCode.UNSUPPORTED_VERSION,
                "Backup version is not an integer",
            )
        if (version < MIN_SUPPORTED_VERSION || version > MAX_SUPPORTED_VERSION) {
            throw BackupValidationException(
                BackupValidationCode.UNSUPPORTED_VERSION,
                "Backup version $version is unsupported (supported $MIN_SUPPORTED_VERSION..$MAX_SUPPORTED_VERSION)",
            )
        }
        return version.toInt()
    }

    /**
     * Pure incoming-envelope checks: version shape, legacy referential checks
     * and canonical referential/resource checks. No live database state is
     * read, so this may run before the restore write transaction opens as a
     * cheap fail-fast.
     */
    fun validateIncoming(snapshot: BackupSnapshot) {
        if (snapshot.version !in MIN_SUPPORTED_VERSION..MAX_SUPPORTED_VERSION) {
            throw BackupValidationException(BackupValidationCode.UNSUPPORTED_VERSION, "Unsupported backup version")
        }
        validateLegacy(snapshot)
        when (snapshot.version) {
            BackupSnapshot.CURRENT_VERSION -> {
                val section = snapshot.canonicalLedger
                    ?: throw BackupValidationException(
                        BackupValidationCode.V4_REQUIRES_CANONICAL_LEDGER,
                        "Backup v4 requires the canonicalLedger field",
                    )
                validateCanonicalSection(section, snapshot)
                val auxiliary = snapshot.auxiliaryTables
                    ?: throw BackupValidationException(
                        BackupValidationCode.V4_REQUIRES_AUXILIARY_TABLES,
                        "Backup v4 requires the complete auxiliaryTables field",
                    )
                BackupAuxiliaryValidation.validate(auxiliary, snapshot)
                // Semantic integrity: every incoming event must still be the
                // exact accepted archive its denormalized rows claim (WM028-P).
                BackupFinancialIntegrity.validate(section)
            }
            else -> {
                if (snapshot.canonicalLedger != null) {
                    throw BackupValidationException(
                        BackupValidationCode.LEGACY_CANNOT_CARRY_CANONICAL_LEDGER,
                        "Backup v${snapshot.version} cannot carry a canonical ledger",
                    )
                }
                if (snapshot.auxiliaryTables != null) {
                    throw BackupValidationException(
                        BackupValidationCode.LEGACY_CANNOT_CARRY_AUXILIARY_TABLES,
                        "Legacy backup cannot carry auxiliary tables",
                    )
                }
            }
        }
    }

    /**
     * Incoming checks plus the live-state eligibility decision, the epoch
     * advance and the final write plan. The live-dependent halves (the legacy
     * mixing refusal and the revision advance) are only correct against state
     * read in the same transaction that performs the replacement: callers MUST
     * invoke this from inside the restore write transaction
     * (see `BackupService.restore`).
     */
    fun validate(
        snapshot: BackupSnapshot,
        localRevisions: List<CanonicalBackupRevision>,
        liveCanonicalDataRowCount: Long,
        liveAuxiliaryDataPresent: Boolean,
    ): BackupRestorePlan {
        validateIncoming(snapshot)
        return when (snapshot.version) {
            BackupSnapshot.CURRENT_VERSION -> {
                val section = snapshot.canonicalLedger
                    ?: throw BackupValidationException(
                        BackupValidationCode.V4_REQUIRES_CANONICAL_LEDGER,
                        "Backup v4 requires the canonicalLedger field",
                    )
                val rows = section.toRows()
                val merged = BackupRevisionAdvance.advance(
                    incoming = section.revisions,
                    local = localRevisions,
                    requireSingleton = hasCanonicalRows(section),
                )
                BackupRestorePlan(
                    snapshot = snapshot,
                    canonicalReplaced = true,
                    canonicalRows = rows,
                    revisionRows = merged,
                )
            }
            else -> {
                if (liveCanonicalDataRowCount > 0L) {
                    throw BackupValidationException(
                        BackupValidationCode.CANONICAL_LEDGER_PRESENT,
                        "Legacy backup v${snapshot.version} refused: the database holds canonical rows " +
                            "that a legacy restore would silently strand",
                    )
                }
                if (liveAuxiliaryDataPresent) {
                    throw BackupValidationException(
                        BackupValidationCode.AUXILIARY_DATA_PRESENT,
                        "Legacy backup cannot replace existing auxiliary data",
                    )
                }
                BackupRestorePlan(
                    snapshot = snapshot,
                    canonicalReplaced = false,
                    canonicalRows = CanonicalBackupRows(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
                    revisionRows = emptyList(),
                )
            }
        }
    }

    private fun hasCanonicalRows(section: CanonicalBackupSection): Boolean =
        section.events.isNotEmpty() ||
            section.observations.isNotEmpty() ||
            section.associations.isNotEmpty() ||
            section.components.isNotEmpty() ||
            section.legs.isNotEmpty() ||
            section.snapshots.isNotEmpty() ||
            section.conflicts.isNotEmpty()

    /** Legacy referential checks against the incoming snapshot only (whole replace). */
    private fun validateLegacy(snapshot: BackupSnapshot) {
        requireDistinct(snapshot.accounts.map { it.id }, "legacy account id")
        requireDistinct(snapshot.categories.map { it.id }, "legacy category id")
        requireDistinct(snapshot.transactions.map { it.id }, "legacy transaction id")
        requireDistinct(snapshot.categoryRules.map { it.id }, "legacy category rule id")
        requireDistinct(snapshot.wishlist.map { it.id }, "legacy wishlist id")
        requireDistinct(snapshot.investmentPools.map { it.id }, "legacy investment pool id")
        requireDistinct(snapshot.investmentContributions.map { it.id }, "legacy investment contribution id")
        requireDistinct(snapshot.smsAudit.map { it.id }, "legacy sms audit id")
        requireDistinct(snapshot.receiptAttachments.map { it.id }, "legacy receipt attachment id")

        val accountIds = snapshot.accounts.map { it.id }.toSet()
        val categoryIds = snapshot.categories.map { it.id }.toSet()
        val poolIds = snapshot.investmentPools.map { it.id }.toSet()
        val transactionIds = snapshot.transactions.map { it.id }.toSet()
        snapshot.transactions.forEach { transaction ->
            if (transaction.accountId !in accountIds) {
                throw BackupValidationException(
                    BackupValidationCode.LEGACY_REFERENCE_MISSING,
                    "Transaction ${transaction.id} references missing account ${transaction.accountId}",
                )
            }
        }
        snapshot.categoryRules.forEach { rule ->
            if (rule.categoryId !in categoryIds) {
                throw BackupValidationException(
                    BackupValidationCode.LEGACY_REFERENCE_MISSING,
                    "Category rule ${rule.id} references missing category ${rule.categoryId}",
                )
            }
        }
        snapshot.investmentContributions.forEach { contribution ->
            if (contribution.poolId !in poolIds) {
                throw BackupValidationException(
                    BackupValidationCode.LEGACY_REFERENCE_MISSING,
                    "Investment contribution ${contribution.id} references missing pool ${contribution.poolId}",
                )
            }
        }
        snapshot.receiptAttachments.forEach { receipt ->
            if (receipt.transactionId !in transactionIds) {
                throw BackupValidationException(
                    BackupValidationCode.LEGACY_REFERENCE_MISSING,
                    "Receipt attachment ${receipt.id} references missing transaction ${receipt.transactionId}",
                )
            }
        }
    }

    private fun validateCanonicalSection(section: CanonicalBackupSection, snapshot: BackupSnapshot) {
        requireDistinct(section.events.map { it.eventId }, "canonical event id")
        requireDistinct(section.observations.map { it.sourceNamespace to it.sourceId }, "canonical observation key")
        requireDistinct(section.associations.map { it.sourceNamespace to it.sourceId }, "canonical association key")
        requireDistinct(section.components.map { it.eventId to it.componentRef }, "canonical component key")
        requireDistinct(section.legs.map { it.eventId to it.legRef }, "canonical leg key")
        requireDistinct(
            section.snapshots.map {
                snapshotKey(it.sourceNamespace, it.sourceId, it.sourcePayloadDigest, it.snapshotDigest)
            },
            "canonical snapshot variant key",
        )
        requireDistinct(section.conflicts.map { it.conflictId }, "canonical conflict id")

        val eventIds = section.events.map { it.eventId }.toSet()
        val observationKeys = section.observations.map { it.sourceNamespace to it.sourceId }.toSet()
        val componentKeys = section.components.map { it.eventId to it.componentRef }.toSet()
        val snapshotKeys = section.snapshots
            .map { snapshotKey(it.sourceNamespace, it.sourceId, it.sourcePayloadDigest, it.snapshotDigest) }
            .toSet()
        val accountIds = snapshot.accounts.map { it.id }.toSet()

        section.associations.forEach { association ->
            if (association.eventId !in eventIds) {
                throw missingCanonical(
                    "Association ${association.sourceNamespace}/${association.sourceId} " +
                        "references missing event ${association.eventId}",
                )
            }
            if ((association.sourceNamespace to association.sourceId) !in observationKeys) {
                throw missingCanonical(
                    "Association ${association.sourceNamespace}/${association.sourceId} " +
                        "references missing observation",
                )
            }
        }
        section.components.forEach { component ->
            if (component.eventId !in eventIds) {
                throw missingCanonical(
                    "Component ${component.eventId}/${component.componentRef} references missing event",
                )
            }
        }
        section.legs.forEach { leg ->
            if (leg.eventId !in eventIds) {
                throw missingCanonical("Leg ${leg.eventId}/${leg.legRef} references missing event")
            }
            if (leg.componentRef != null && (leg.eventId to leg.componentRef) !in componentKeys) {
                throw missingCanonical(
                    "Leg ${leg.eventId}/${leg.legRef} references missing component ${leg.componentRef}",
                )
            }
            if (leg.accountRef !in accountIds) {
                throw missingCanonical(
                    "Leg ${leg.eventId}/${leg.legRef} references missing account ${leg.accountRef}",
                )
            }
        }
        section.snapshots.forEach { variant ->
            if ((variant.sourceNamespace to variant.sourceId) !in observationKeys) {
                throw missingCanonical(
                    "Snapshot variant ${variant.sourceNamespace}/${variant.sourceId} " +
                        "references missing observation",
                )
            }
        }
        section.conflicts.forEach { conflict ->
            if (snapshotKey(
                    conflict.sourceNamespace,
                    conflict.sourceId,
                    conflict.sourcePayloadDigest,
                    conflict.snapshotDigest,
                ) !in snapshotKeys
            ) {
                throw missingCanonical(
                    "Conflict ${conflict.conflictId} references missing snapshot variant " +
                        "${conflict.sourceNamespace}/${conflict.sourceId}",
                )
            }
            conflict.existingEventId?.let { existingEventId ->
                if (existingEventId !in eventIds) {
                    throw missingCanonical(
                        "Conflict ${conflict.conflictId} references missing existing event $existingEventId",
                    )
                }
            }
        }
    }

    private fun missingCanonical(message: String) = BackupValidationException(
        BackupValidationCode.CANONICAL_REFERENCE_MISSING,
        message,
    )

    private fun <T> requireDistinct(keys: List<T>, description: String) {
        if (keys.size != keys.toSet().size) {
            throw BackupValidationException(
                BackupValidationCode.DUPLICATE_BACKUP_KEY,
                "Duplicate $description in backup",
            )
        }
    }
}
