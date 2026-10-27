package com.athar.core.data.backup

import com.athar.core.data.ledger.backup.CanonicalBackupRevision
import com.athar.core.data.ledger.db.CanonicalLedgerRevisionEntity

/**
 * Pure restore-epoch arithmetic for the v4 whole-replace restore.
 *
 * Policy, grounded in the actual revision contract
 * (`CanonicalLedgerDao` / `canonical_ledger_revision`):
 *  - The only epoch any admission writer advances is the schema-seeded
 *    `singleton` counter (`incrementRevision` / `incrementRevisionIfExpected`);
 *    no code path writes any other id, so non-singleton rows are metadata.
 *  - Every incoming id AND the live singleton are affected by the replace,
 *    including an empty payload with no imported singleton row:
 *    its restored value must be strictly greater than BOTH the live value and
 *    the incoming value, so no graph state can ever reuse an epoch already
 *    consumed by an admission or an earlier restore. Exact formula:
 *    `max(current, incoming) + 1`.
 *  - A missing live row protects no epoch (the table seeds only `singleton`;
 *    `getRevision` returns null and nothing can roll back to an absent row),
 *    so an affected id without a live row restores at `incoming + 1`.
 *  - Only non-singleton live rows absent from the payload are unaffected
 *    metadata. The singleton always advances; absence on both sides starts
 *    from zero, and absence from an empty payload cannot preserve a stale epoch.
 *  - Negative incoming values fail closed. When `max(current, incoming)` is
 *    [Long.MAX_VALUE] a strict advance cannot exist; the overflow fails closed
 *    with `INVALID_REVISION` so the restore aborts during plan creation —
 *    before any table is cleared.
 *
 * This object never touches the database and never mutates the decoded
 * snapshot: only the values written into the live revision table advance, and
 * the imported payload metadata stays byte-identical.
 */
internal object BackupRevisionAdvance {

    /**
     * Computes the exact revision rows to write for a v4 whole replace.
     *
     * @param incoming revision rows decoded from the backup payload.
     * @param local revision rows read inside the restore write transaction.
     * @param requireSingleton true when the payload carries canonical data
     *   rows, in which case the `singleton` counter must be present.
     * @throws BackupValidationException on duplicate incoming ids, negative
     *   incoming revisions, a missing required singleton counter, or a
     *   `Long.MAX_VALUE` advance that cannot strictly advance.
     */
    fun advance(
        incoming: List<CanonicalBackupRevision>,
        local: List<CanonicalBackupRevision>,
        requireSingleton: Boolean,
    ): List<CanonicalLedgerRevisionEntity> {
        val incomingById = incoming.associateBy { it.id }
        if (incomingById.size != incoming.size) {
            throw BackupValidationException(
                BackupValidationCode.DUPLICATE_BACKUP_KEY,
                "Duplicate canonical revision id in backup",
            )
        }
        incoming.forEach { row ->
            if (row.revision < 0L) {
                throw BackupValidationException(
                    BackupValidationCode.INVALID_REVISION,
                    "Canonical revision ${row.id} is negative",
                )
            }
        }
        if (requireSingleton && incomingById.keys.none { it == BackupRestoreValidator.REVISION_SINGLETON_ID }) {
            throw BackupValidationException(
                BackupValidationCode.INVALID_REVISION,
                "Backup carries canonical rows without the " +
                    "${BackupRestoreValidator.REVISION_SINGLETON_ID} revision counter",
            )
        }
        val localById = local.associateBy { it.id }
        val singletonId = BackupRestoreValidator.REVISION_SINGLETON_ID
        return (incomingById.keys + localById.keys + singletonId).map { id ->
            val incomingRow = incomingById[id]
            val localRow = localById[id]
            if (incomingRow == null && id != singletonId) {
                // Only non-live metadata can be unaffected by whole replacement.
                CanonicalLedgerRevisionEntity(id = id, revision = localRow!!.revision)
            } else {
                val current = localRow?.revision
                if (current != null && current < 0L) {
                    throw BackupValidationException(
                        BackupValidationCode.INVALID_REVISION,
                        "Live canonical revision $id is negative",
                    )
                }
                val base = maxOf(current ?: 0L, incomingRow?.revision ?: 0L)
                if (base == Long.MAX_VALUE) {
                    throw BackupValidationException(
                        BackupValidationCode.INVALID_REVISION,
                        "Canonical revision $id cannot strictly advance past Long.MAX_VALUE",
                    )
                }
                CanonicalLedgerRevisionEntity(id = id, revision = base + 1L)
            }
        }.sortedBy { it.id }
    }
}
